package com.deckrec.usb

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches the platform's capture endpoints and the raw USB bus at the same time.
 *
 * The two views disagree in exactly the cases DJs hit in the field: the mixer is plugged in and
 * visible on the USB bus but the audio HAL has not exposed it, or — worse, because it looks
 * identical to nothing being plugged in at all — the phone has been connected to the mixer's
 * thumb-drive port and is itself acting as the USB *peripheral*, so there is no host to enumerate
 * anything. Tracking both views plus the phone's USB role lets the UI name which one it is.
 */
class UsbAudioScanner(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val handler = Handler(Looper.getMainLooper())

    private val _inputs = MutableStateFlow<List<AudioInput>>(emptyList())

    /** Endpoints a third-party app can actually record from. */
    val inputs: StateFlow<List<AudioInput>> = _inputs.asStateFlow()

    private val _diagnostics = MutableStateFlow(UsbDiagnostics())
    val diagnostics: StateFlow<UsbDiagnostics> = _diagnostics.asStateFlow()

    /** Written on the main thread by [refresh], read from the engine's control thread. */
    @Volatile
    private var liveDevices: Array<AudioDeviceInfo> = emptyArray()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    fun start() {
        audioManager.registerAudioDeviceCallback(callback, handler)
        refresh()
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    fun refresh() {
        liveDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val all = liveDevices.map { it.toAudioInput() }

        _inputs.value = all
            .filter { it.isRecordable }
            .sortedWith(compareByDescending<AudioInput> { it.isUsb }.thenBy { it.productName })

        val bus = scanUsbBus()
        val gadget = readGadgetState()
        _diagnostics.value = UsbDiagnostics(
            hostSupported = appContext.packageManager
                .hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            usbConnected = gadget.connected,
            usbConfigured = gadget.configured,
            busDevices = bus,
            allEndpoints = all,
        )
    }

    fun deviceInfoFor(id: Int): AudioDeviceInfo? = liveDevices.firstOrNull { it.id == id }

    /** The endpoint we would pick by default: a USB input if there is one, else anything usable. */
    fun preferredInput(): AudioInput? =
        _inputs.value.firstOrNull { it.isUsb } ?: _inputs.value.firstOrNull()

    /**
     * The phone's own USB gadget state — whether *something else* is acting as the host.
     *
     * This is what distinguishes plugging into a mixer's thumb-drive socket from nothing being
     * plugged in at all: in the first case the mixer is the host and the phone is a peripheral, so
     * no amount of scanning will ever find an audio input, because the phone is not the one
     * enumerating.
     *
     * The two extras have to be read separately. `connected` tracks VBUS, so it goes true for a
     * plain wall charger that never enumerates anything; treating that alone as "some other host
     * has us" told a user sitting at home on a charger that they were plugged into a mixer.
     * `configured` only goes true once a host has issued SET_CONFIGURATION, which a charger never
     * does. Note the converse is not proof either: a host that *rejects* the phone — an RX3 showing
     * E-8307, say — can stop before SET_CONFIGURATION, so connected-but-unconfigured is genuinely
     * ambiguous rather than a "no".
     */
    private fun readGadgetState(): GadgetState {
        val usbState = runCatching {
            appContext.registerReceiver(null, IntentFilter(ACTION_USB_STATE))
        }.getOrNull()
        if (usbState != null) {
            return GadgetState(
                connected = usbState.getBooleanExtra("connected", false),
                configured = usbState.getBooleanExtra("configured", false),
            )
        }
        // No sticky broadcast on this build: charging over USB with an empty host bus is the only
        // public signal left, and it cannot tell configured from merely powered.
        return GadgetState(connected = chargingOverUsb(), configured = false)
    }

    private fun chargingOverUsb(): Boolean {
        val battery: Intent? = runCatching {
            appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        return plugged == BatteryManager.BATTERY_PLUGGED_USB
    }

    private data class GadgetState(val connected: Boolean, val configured: Boolean)

    /** Every device on the bus, not only audio-class ones: a mixer that enumerates with the wrong
     * class is invisible otherwise, and that is worth seeing when nothing works. */
    private fun scanUsbBus(): List<UsbHardware> =
        usbManager.deviceList.values
            .map { device ->
                UsbHardware(
                    deviceName = device.deviceName,
                    productName = device.productName ?: "USB device",
                    manufacturerName = device.manufacturerName ?: "",
                    vendorId = device.vendorId,
                    productId = device.productId,
                    hasPermission = usbManager.hasPermission(device),
                    capability = UsbDeviceClass.classify(device.summarise()),
                )
            }
            .sortedByDescending { it.hasAudioStreamingInterface || it.hasVendorIsochronousAudio }

    fun usbDeviceByName(deviceName: String): UsbDevice? = usbManager.deviceList[deviceName]

    /**
     * Flattens the device's interfaces into the plain summaries [UsbDeviceClass] classifies.
     *
     * `getInterface` enumerates every (interface, alternate setting) pair, so the streaming
     * alternate setting where the isochronous endpoints live is visible here without having to
     * claim anything first.
     */
    private fun UsbDevice.summarise(): List<UsbInterfaceSummary> =
        (0 until interfaceCount).map { index ->
            val iface = getInterface(index)
            var isochronousInputs = 0
            for (e in 0 until iface.endpointCount) {
                val endpoint = iface.getEndpoint(e)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                    endpoint.direction == UsbConstants.USB_DIR_IN
                ) {
                    isochronousInputs++
                }
            }
            UsbInterfaceSummary(
                interfaceClass = iface.interfaceClass,
                interfaceSubclass = iface.interfaceSubclass,
                isochronousInputs = isochronousInputs,
            )
        }

    private fun AudioDeviceInfo.toAudioInput(): AudioInput = AudioInput(
        id = id,
        productName = productName?.toString()?.takeIf { it.isNotBlank() } ?: "Input $id",
        type = type,
        address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) address else "",
        sampleRates = sampleRates.toList(),
        channelCounts = channelCounts.toList(),
        channelIndexMasks = channelIndexMasks.toList(),
        encodings = encodings.toList(),
    )

    private companion object {
        const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
    }
}

/** Everything the app can find out about how this phone is currently wired up. */
data class UsbDiagnostics(
    val hostSupported: Boolean = false,
    /** VBUS is present on the phone's own port — true for a plain charger too. */
    val usbConnected: Boolean = false,
    /** Some other host has issued SET_CONFIGURATION to this phone. A charger never does. */
    val usbConfigured: Boolean = false,
    val busDevices: List<UsbHardware> = emptyList(),
    /** Every capture endpoint the platform reports, including ones apps cannot record from. */
    val allEndpoints: List<AudioInput> = emptyList(),
) {
    /** Devices Android's audio HAL can actually bind — an AudioStreaming interface, not just class 1. */
    val audioClassDevices: List<UsbHardware> get() = busDevices.filter { it.hasAudioStreamingInterface }

    /** Bus devices worth naming as "your mixer"; a hub or a card reader is neither. */
    val audioCapableDevices: List<UsbHardware>
        get() = busDevices.filter {
            it.hasAudioStreamingInterface || it.hasVendorIsochronousAudio || it.isKnownDjHardware
        }

    /**
     * Hardware whose audio is on a vendor-specific interface, so Android will never route it.
     *
     * This is the Pioneer/AlphaTheta case and it is terminal for the stock audio path: the DJM and
     * XDJ families put their PCM on interfaces declaring class 0xFF, which is why Linux carries
     * hand-written endpoint quirks for them and why macOS needs a Pioneer driver. Android's
     * UsbAlsaManager only ever binds class-compliant AudioStreaming interfaces, so no capture
     * endpoint appears no matter what the user changes on the mixer.
     *
     * Note these devices *do* declare USB class 1 — for their AudioControl and MIDI interfaces —
     * so this deliberately keys on the AudioStreaming subclass rather than the class alone.
     */
    val vendorSpecificDjHardware: List<UsbHardware>
        get() = busDevices.filter {
            !it.hasAudioStreamingInterface && (it.hasVendorIsochronousAudio || it.isKnownDjHardware)
        }

    /** The subset we could open ourselves, once the direct USB capture path is wired up. */
    val directCaptureCandidates: List<UsbHardware>
        get() = busDevices.filter { it.capability.needsDirectCapture }

    /** Something else is definitely the host — the classic wrong-port symptom. */
    val looksLikeWrongPort: Boolean
        get() = usbConnected && usbConfigured && busDevices.isEmpty()

    /**
     * Powered over USB with nothing on the host bus. Could be the wrong port, could be a charger:
     * worth mentioning, not worth asserting.
     */
    val mayBeWrongPort: Boolean
        get() = usbConnected && !usbConfigured && busDevices.isEmpty()
}

/** A USB device visible on the bus. */
data class UsbHardware(
    val deviceName: String,
    val productName: String,
    val manufacturerName: String,
    val vendorId: Int,
    val productId: Int,
    val hasPermission: Boolean,
    val capability: UsbAudioCapability = UsbAudioCapability(false, false, false, false),
) {
    val isKnownDjHardware: Boolean get() = vendorId in DJ_VENDOR_IDS

    val hasAudioStreamingInterface: Boolean get() = capability.hasAudioStreaming
    val hasVendorIsochronousAudio: Boolean get() = capability.hasVendorIsochronousInput

    fun label(): String =
        if (manufacturerName.isNotBlank() && !productName.startsWith(manufacturerName)) {
            "$manufacturerName $productName"
        } else {
            productName
        }

    fun describe(): String = buildString {
        append(label())
        append(String.format("  (VID 0x%04X PID 0x%04X)", vendorId, productId))
        // Spelled out rather than reduced to "audio class": the whole diagnosis on this hardware
        // turns on the difference between declaring class 1 and offering a stream Android can bind.
        append(
            when {
                capability.hasAudioStreaming -> " · USB audio streaming"
                capability.hasVendorIsochronousInput -> " · vendor audio (direct capture only)"
                capability.declaresAudioClass -> " · class 1, but MIDI/control only"
                else -> " · no audio"
            }
        )
        if (!hasPermission) append(" · no permission")
    }

    companion object {
        /** Pioneer DJ, Pioneer Corp and AlphaTheta. */
        val DJ_VENDOR_IDS = setOf(0x2B73, 0x08E4, 0x08E6, 0x29BA)
    }
}
