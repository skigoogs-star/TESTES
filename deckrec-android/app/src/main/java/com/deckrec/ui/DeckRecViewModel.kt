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
import com.deckrec.usb.UsbHardware
import kotlinx.coroutines.Dispatchers
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
    val usbHardware: List<UsbHardware> = emptyList(),
    val selectedInput: AudioInput? = null,
    val settings: AppSettings = AppSettings(),
    val state: RecorderState = RecorderState.Idle,
    val levels: Levels = Levels(),
    val progress: RecordingProgress = RecordingProgress(),
    val markers: List<Marker> = emptyList(),
    val remainingSeconds: Long = 0,
    val notice: String? = null,
) {
    val isRecording: Boolean get() = state is RecorderState.Recording
    val isPaused: Boolean get() = (state as? RecorderState.Recording)?.paused == true
    val isBusy: Boolean get() = state is RecorderState.Starting || state is RecorderState.Stopping

    /**
     * True when a DJ mixer is sitting on the USB bus but the audio system has not turned it into a
     * capture endpoint — the single most common failure in the booth, and worth calling out.
     */
    val hasUnroutedDjHardware: Boolean
        get() = usbHardware.any { it.isKnownDjHardware } && inputs.none { it.isUsb }

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

    val uiState: StateFlow<RecordUiState> = combine(
        scanner.inputs,
        scanner.usbAudioHardware,
        settingsStore.settings,
        combine(
            engine.state,
            engine.levels,
            engine.progress,
            engine.markers,
            engine.notice,
        ) { state, levels, progress, markers, notice ->
            RecorderSnapshot(state, levels, progress, markers, notice)
        },
        _remainingSeconds,
    ) { inputs, hardware, settings, snapshot, remaining ->
        val selected = inputs.firstOrNull { it.id == settings.preferredDeviceId }
            ?: inputs.firstOrNull { it.isUsb }
            ?: inputs.firstOrNull()
        RecordUiState(
            inputs = inputs,
            usbHardware = hardware,
            selectedInput = selected,
            settings = settings,
            state = snapshot.state,
            levels = snapshot.levels,
            progress = snapshot.progress,
            markers = snapshot.markers,
            remainingSeconds = remaining,
            notice = snapshot.notice,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordUiState())

    private data class RecorderSnapshot(
        val state: RecorderState,
        val levels: Levels,
        val progress: RecordingProgress,
        val markers: List<Marker>,
        val notice: String?,
    )

    init {
        refreshCapacity()
    }

    fun consumeNotice() = engine.consumeNotice()

    fun refreshDevices() {
        scanner.refresh()
        refreshCapacity()
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

    fun setGain(db: Float) {
        settingsStore.update { it.copy(inputGainDb = db) }
        engine.updateGain(db)
    }

    fun setSubBass(amount: Float) {
        settingsStore.update { it.copy(subBassAmount = amount) }
        engine.updateSubBass(amount)
    }

    fun setLoudness(amount: Float) {
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
        settingsStore.update { it.copy(preferredDeviceId = input.id, channelPairLeft = 0) }
    }

    fun selectChannelPair(pair: ChannelPair) {
        settingsStore.update { it.copy(channelPairLeft = pair.left) }
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
    }

    fun setAutoSplitMinutes(minutes: Int) = settingsStore.update { it.copy(autoSplitMinutes = minutes) }

    fun setMarkerSensitivity(value: Float) = settingsStore.update { it.copy(autoMarkerSensitivity = value) }

    fun setMarkerGapSeconds(value: Float) = settingsStore.update { it.copy(autoMarkerGapSeconds = value) }

    fun setFileNamePrefix(prefix: String) = settingsStore.update { it.copy(fileNamePrefix = prefix) }

    fun setKeepScreenOn(enabled: Boolean) = settingsStore.update { it.copy(keepScreenOn = enabled) }

    // ---- Library -------------------------------------------------------------------------

    fun recording(id: String): RecordingMeta? = store.find(id)

    /**
     * Library writes all go through here so they land on the IO dispatcher. Each save rescans and
     * re-parses the whole directory, which janks the frame it runs on if done inline.
     */
    private fun editLibrary(block: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { block() }
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
        editLibrary {
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
        _message.value = "Details saved"
    }

    fun deleteRecording(meta: RecordingMeta) {
        editLibrary { store.delete(meta) }
        _message.value = "Deleted \"${meta.displayTitle()}\""
        refreshCapacity()
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
}
