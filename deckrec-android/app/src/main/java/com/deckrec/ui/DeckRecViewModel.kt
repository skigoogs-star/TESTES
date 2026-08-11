package com.deckrec.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deckrec.DeckRecApp
import com.deckrec.audio.RecorderState
import com.deckrec.audio.dsp.Levels
import com.deckrec.data.AppSettings
import com.deckrec.data.Marker
import com.deckrec.data.RecordingFormat
import com.deckrec.data.RecordingMeta
import com.deckrec.data.RecordingProgress
import com.deckrec.service.RecordingService
import com.deckrec.usb.AudioInput
import com.deckrec.usb.ChannelPair
import com.deckrec.usb.UsbDiagnostics
import com.deckrec.usb.UsbHardware
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Everything the record screen needs, in one snapshot. */
data class RecordUiState(
    val inputs: List<AudioInput> = emptyList(),
    val diagnostics: UsbDiagnostics = UsbDiagnostics(),
    val selectedInput: AudioInput? = null,
    val settings: AppSettings = AppSettings(),
    val state: RecorderState = RecorderState.Idle,
    val levels: Levels = Levels(),
    val progress: RecordingProgress = RecordingProgress(),
    val markers: List<Marker> = emptyList(),
    val remainingSeconds: Long = 0,
    val notice: String? = null,
    /** True while the input is open for metering but nothing is being written. */
    val monitoring: Boolean = false,
    /** Why monitoring is not producing levels, when it is not. */
    val monitorStatus: String? = null,
) {
    val usbHardware: List<UsbHardware> get() = diagnostics.busDevices
    val isRecording: Boolean get() = state is RecorderState.Recording
    val isPaused: Boolean get() = (state as? RecorderState.Recording)?.paused == true
    val isBusy: Boolean get() = state is RecorderState.Starting || state is RecorderState.Stopping

    /**
     * A class-compliant USB audio device is on the bus but the audio system has not turned it into
     * a capture endpoint — worth calling out, because a replug usually fixes it.
     *
     * Deliberately keyed on [UsbDiagnostics.audioClassDevices] rather than "anything on the bus":
     * a hub, a dock or a card reader is not a mixer, and describing one as an unrouted mixer sends
     * the user hunting for a fault that is not there.
     */
    val hasUnroutedDjHardware: Boolean
        get() = diagnostics.audioClassDevices.isNotEmpty() && inputs.none { it.isUsb }

    /**
     * Known DJ hardware that cannot ever work over the stock audio path — see
     * [UsbDiagnostics.vendorSpecificDjHardware]. Checked before everything else, because no other
     * advice applies once this is true.
     */
    val djHardwareIsVendorSpecific: Boolean
        get() = diagnostics.vendorSpecificDjHardware.isNotEmpty()

    /** Audio-capable hardware is attached but offers no audio interface in its current mode. */
    val connectedButNotAudioClass: Boolean
        get() = diagnostics.audioCapableDevices.isNotEmpty() && diagnostics.audioClassDevices.isEmpty()

    val channelPairs: List<ChannelPair>
        get() = ChannelPair.pairsFor(selectedInput?.maxChannelCount ?: 2)
}

class DeckRecViewModel(application: Application) : AndroidViewModel(application) {

    private val app = DeckRecApp.from(application)
    private val engine = app.recordingEngine
    private val store = app.recordingStore
    private val settingsStore = app.settingsStore
    private val scanner = app.usbAudioScanner

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _remainingSeconds = MutableStateFlow(0L)

    val recordings: StateFlow<List<RecordingMeta>> = store.recordings

    /** False until the library's first background scan finishes. */
    val libraryLoaded: StateFlow<Boolean> = store.loaded

    // Split in two because combine() tops out at five flows.
    private val recorderSnapshot = combine(
        combine(
            engine.state,
            engine.levels,
            engine.progress,
            engine.markers,
        ) { state, levels, progress, markers -> RecorderCore(state, levels, progress, markers) },
        engine.notice,
        engine.isMonitoring,
        engine.monitorStatus,
    ) { core, notice, monitoring, monitorStatus ->
        RecorderSnapshot(core, notice, monitoring, monitorStatus)
    }

    val uiState: StateFlow<RecordUiState> = combine(
        scanner.inputs,
        scanner.diagnostics,
        settingsStore.settings,
        recorderSnapshot,
        _remainingSeconds,
    ) { inputs, diagnostics, settings, snapshot, remaining ->
        // Matched by a stable key, not the platform id: AudioDeviceInfo ids are reassigned across
        // replug and reboot, so a saved id can silently match completely different hardware.
        val selected = inputs.firstOrNull { it.key() == settings.preferredDeviceKey }
            ?: inputs.firstOrNull { it.isUsb }
            ?: inputs.firstOrNull()
        RecordUiState(
            inputs = inputs,
            diagnostics = diagnostics,
            selectedInput = selected,
            settings = settings,
            state = snapshot.core.state,
            levels = snapshot.core.levels,
            progress = snapshot.core.progress,
            markers = snapshot.core.markers,
            remainingSeconds = remaining,
            notice = snapshot.notice,
            monitoring = snapshot.monitoring,
            monitorStatus = snapshot.monitorStatus,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordUiState())

    private data class RecorderCore(
        val state: RecorderState,
        val levels: Levels,
        val progress: RecordingProgress,
        val markers: List<Marker>,
    )

    private data class RecorderSnapshot(
        val core: RecorderCore,
        val notice: String?,
        val monitoring: Boolean,
        val monitorStatus: String?,
    )

    init {
        refreshCapacity()
        // "Time left" is the number a DJ checks before a long set, so it has to keep counting down
        // as the file grows rather than being frozen at whatever it was when the app opened.
        viewModelScope.launch {
            while (true) {
                delay(CAPACITY_POLL_MS)
                if (engine.isRecording) refreshCapacity()
            }
        }
    }

    fun consumeNotice() = engine.consumeNotice()

    fun refreshDevices() {
        scanner.refresh()
        refreshCapacity()
    }

    /** Set once the user has been warned about a non-USB input; the next tap goes through. */
    private var wrongInputConfirmed = false

    /**
     * True only while the record screen is resumed and has asked for monitoring.
     *
     * Settings changes can reconfigure a monitor, but must never *start* one: nothing on the
     * settings screen stops it again, and the engine outlives the screen, so the microphone would
     * stay open for the life of the process.
     */
    private var monitorWanted = false

    /**
     * Runs the input through the record bus without writing, so the meters are live before REC.
     * Driven by the record screen's lifecycle so the microphone is never held in the background.
     */
    fun startMonitoring() {
        monitorWanted = true
        applyMonitor(uiState.value.selectedInput)
    }

    fun stopMonitoring() {
        monitorWanted = false
        // Straight to the engine, which owns a control thread of its own. Routing this through
        // viewModelScope means teardown requested as the UI is destroyed gets cancelled before it
        // runs, and the microphone stays open in a process with no UI left.
        engine.releaseMonitor()
    }

    /** Reopens the monitor so a changed input, channel pair or sample rate takes effect at once. */
    private fun restartMonitoring(input: AudioInput? = uiState.value.selectedInput) {
        if (!monitorWanted) return
        applyMonitor(input)
    }

    private fun applyMonitor(input: AudioInput?) {
        if (input == null || engine.isRecording) return
        // settingsStore.current, not state.settings: uiState is a combine that recomputes
        // asynchronously, so right after changing the channel pair it still holds the old value —
        // and reopening the monitor with the pair the user just moved away from is the one thing
        // this must not do.
        engine.requestMonitor(
            settingsStore.current.toRecorderConfig(
                deviceId = input.id,
                deviceName = input.productName,
                sourceChannelCount = input.maxChannelCount,
            )
        )
    }

    fun availableBytes(): Long = store.availableBytes()

    fun refreshCapacity() {
        viewModelScope.launch {
            val settings = settingsStore.current
            val seconds = withContext(Dispatchers.IO) {
                store.remainingSeconds(settings.format, settings.sampleRate, settings.aacBitrateKbps)
            }
            _remainingSeconds.value = seconds
        }
    }

    // ---- Recording transport -------------------------------------------------------------

    fun startRecording() {
        // Check before the service is started, not after. Starting a microphone-typed foreground
        // service without RECORD_AUDIO is a SecurityException on Android 14+ — a hard crash, not
        // a failed recording.
        if (ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            _message.value =
                "Microphone permission is required — Android gates USB audio input behind it too."
            return
        }

        val state = uiState.value
        val input = state.selectedInput
        if (input == null) {
            _message.value = "No audio input available. Connect your mixer over USB and try again."
            return
        }

        // A USB *audio* device on the bus while a non-USB input is selected usually means routing
        // did not take. Gated on audio-capable hardware, not on the bus being non-empty: with the
        // broad scan that landed with the diagnostics panel, a hub or a charger dock made this fire
        // on every single REC press forever. Warn once and let the second tap through — Pioneer
        // mixers never expose a USB capture endpoint at all, and for that user a room recording
        // beats no recording.
        if (!input.isUsb && state.diagnostics.audioCapableDevices.isNotEmpty() && !wrongInputConfirmed) {
            wrongInputConfirmed = true
            _message.value = "\"${input.productName}\" is not a USB input. " +
                "Tap REC again to record from it anyway."
            return
        }
        wrongInputConfirmed = false

        // Clear any terminal state from a previous session before the service subscribes to it.
        engine.clearTerminalState()

        val settings = state.settings
        val config = settings.toRecorderConfig(
            deviceId = input.id,
            deviceName = input.productName,
            sourceChannelCount = input.maxChannelCount,
        )
        RecordingService.start(getApplication(), config)
    }

    fun stopRecording() {
        RecordingService.send(getApplication(), RecordingService.ACTION_STOP)
    }

    fun togglePause() {
        RecordingService.send(getApplication(), RecordingService.ACTION_TOGGLE_PAUSE)
    }

    fun addMarker() {
        if (!engine.isRecording) return
        engine.addMarker()
    }

    // ---- Live controls -------------------------------------------------------------------

    // Preview applies to the live audio on every drag frame; commit persists once on release.
    // Writing the whole preference file per frame was needless main-thread churn mid-set.
    fun previewGain(db: Float) = engine.updateGain(db)

    fun commitGain(db: Float) {
        settingsStore.update { it.copy(inputGainDb = db) }
        engine.updateGain(db)
    }

    fun previewSubBass(amount: Float) = engine.updateSubBass(amount)

    fun commitSubBass(amount: Float) {
        settingsStore.update { it.copy(subBassAmount = amount) }
        engine.updateSubBass(amount)
    }

    fun previewLoudness(amount: Float) = engine.updateLoudness(amount)

    fun commitLoudness(amount: Float) {
        settingsStore.update { it.copy(loudnessAmount = amount) }
        engine.updateLoudness(amount)
    }

    fun setLimiter(enabled: Boolean) {
        settingsStore.update { it.copy(limiterEnabled = enabled) }
        engine.updateLimiter(enabled)
    }

    fun setAutoMarkers(enabled: Boolean) {
        settingsStore.update { it.copy(autoMarkersEnabled = enabled) }
        engine.updateAutoMarkers(enabled)
    }

    // ---- Settings ------------------------------------------------------------------------

    fun selectInput(input: AudioInput) {
        settingsStore.update { it.copy(preferredDeviceKey = input.key(), channelPairLeft = 0) }
        wrongInputConfirmed = false
        // The input is passed explicitly: uiState has not yet re-derived selectedInput from the
        // id just written, so reading it back would reopen the monitor on the previous device.
        restartMonitoring(input)
    }

    fun selectChannelPair(pair: ChannelPair) {
        settingsStore.update { it.copy(channelPairLeft = pair.left) }
        // Reopened immediately: picking the pair is done by watching the meters respond.
        restartMonitoring()
    }

    fun setFormat(format: RecordingFormat) {
        settingsStore.update { it.copy(format = format) }
        refreshCapacity()
    }

    fun setAacBitrate(kbps: Int) {
        settingsStore.update { it.copy(aacBitrateKbps = kbps) }
        refreshCapacity()
    }

    fun setSampleRate(rate: Int) {
        settingsStore.update { it.copy(sampleRate = rate) }
        refreshCapacity()
        restartMonitoring()
    }

    fun setAutoSplitMinutes(minutes: Int) = settingsStore.update { it.copy(autoSplitMinutes = minutes) }

    fun setMarkerSensitivity(value: Float) {
        settingsStore.update { it.copy(autoMarkerSensitivity = value) }
        // Tuning detection during a set is the natural time to do it, so it has to take effect now
        // rather than at the next recording.
        engine.updateMarkerSensitivity(value)
    }

    fun setMarkerGapSeconds(value: Float) {
        settingsStore.update { it.copy(autoMarkerGapSeconds = value) }
        engine.updateMarkerGapSeconds(value)
    }

    fun setFileNamePrefix(prefix: String) = settingsStore.update { it.copy(fileNamePrefix = prefix) }

    fun setKeepScreenOn(enabled: Boolean) = settingsStore.update { it.copy(keepScreenOn = enabled) }

    // ---- Library -------------------------------------------------------------------------

    fun recording(id: String): RecordingMeta? = store.find(id)

    /**
     * Library writes all go through here so they land on the IO dispatcher. Each save rescans and
     * re-parses the whole directory, which janks the frame it runs on if done inline.
     */
    private fun editLibrary(doneMessage: String? = null, block: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { block() }
            // Reported after the work actually happened, not before it starts.
            doneMessage?.let { _message.value = it }
            refreshCapacity()
        }
    }

    fun updateRecording(meta: RecordingMeta) = editLibrary { store.save(meta) }

    /** Applies the whole details form in one pass: rename the file, then persist the metadata. */
    fun saveDetails(
        meta: RecordingMeta,
        title: String,
        artist: String,
        genre: String,
        tags: String,
        notes: String,
    ) {
        editLibrary(doneMessage = "Details saved") {
            val renamed = if (title.isNotBlank() && title != meta.title) {
                store.rename(meta, title)
            } else {
                meta
            }
            store.save(
                renamed.copy(
                    title = title,
                    artist = artist,
                    genre = genre,
                    tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    notes = notes,
                )
            )
        }
    }

    fun deleteRecording(meta: RecordingMeta) {
        editLibrary(doneMessage = "Deleted \"${meta.displayTitle()}\"") { store.delete(meta) }
    }

    fun addMarkerTo(meta: RecordingMeta, positionMs: Long) = editLibrary {
        val marker = Marker(UUID.randomUUID().toString(), positionMs, "", automatic = false)
        store.save(meta.copy(markers = (meta.markers + marker).sortedBy { it.positionMs }))
    }

    fun updateMarker(meta: RecordingMeta, marker: Marker) = editLibrary {
        val markers = meta.markers.map { if (it.id == marker.id) marker else it }
            .sortedBy { it.positionMs }
        store.save(meta.copy(markers = markers))
    }

    fun deleteMarker(meta: RecordingMeta, marker: Marker) = editLibrary {
        store.save(meta.copy(markers = meta.markers.filterNot { it.id == marker.id }))
    }

    fun shareIntent(meta: RecordingMeta): Intent = Intent.createChooser(
        store.shareIntent(meta),
        "Share ${meta.displayTitle()}",
    )

    fun exportToMusic(meta: RecordingMeta) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { store.exportToMusicLibrary(meta) }
            _message.value = result.fold(
                onSuccess = { "Saved to Music/DeckRec" },
                onFailure = { "Could not save to Music: ${it.message}" },
            )
        }
    }

    /** Formats the track list the way a DJ would paste it into a Mixcloud description. */
    fun trackListText(meta: RecordingMeta): String {
        if (meta.markers.isEmpty()) return meta.displayTitle()
        return buildString {
            appendLine(meta.displayTitle())
            appendLine()
            meta.markers.sortedBy { it.positionMs }.forEachIndexed { index, marker ->
                appendLine("${com.deckrec.data.formatDuration(marker.positionMs)}  ${marker.displayLabel(index)}")
            }
        }.trim()
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    private companion object {
        const val CAPACITY_POLL_MS = 30_000L
    }
}
