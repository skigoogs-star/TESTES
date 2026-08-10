package com.deckrec.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watches the platform's capture endpoints and the raw USB bus at the same time.
 *
 * The two views disagree in exactly the case DJs hit in the field: the mixer is plugged in and
 * visible on the USB bus, but the audio HAL has not exposed it as a capture endpoint (mixer set to
 * the wrong USB mode, hub without power, cable that is charge-only). Tracking both lets the UI say
 * which of those it is instead of showing an empty list.
 */
class UsbAudioScanner(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val handler = Handler(Looper.getMainLooper())

    private val _inputs = MutableStateFlow<List<AudioInput>>(emptyList())
    val inputs: StateFlow<List<AudioInput>> = _inputs.asStateFlow()

    /** USB devices on the bus that expose an Audio Class interface, whether routed or not. */
    private val _usbAudioHardware = MutableStateFlow<List<UsbHardware>>(emptyList())
    val usbAudioHardware: StateFlow<List<UsbHardware>> = _usbAudioHardware.asStateFlow()

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
        _inputs.value = liveDevices
            .map { it.toAudioInput() }
            .sortedWith(compareByDescending<AudioInput> { it.isUsb }.thenBy { it.productName })
        _usbAudioHardware.value = scanUsbBus()
    }

    fun deviceInfoFor(id: Int): AudioDeviceInfo? = liveDevices.firstOrNull { it.id == id }

    /** The endpoint we would pick by default: a USB input if there is one, else anything. */
    fun preferredInput(): AudioInput? =
        _inputs.value.firstOrNull { it.isUsb } ?: _inputs.value.firstOrNull()

    private fun scanUsbBus(): List<UsbHardware> =
        usbManager.deviceList.values
            .filter { it.hasAudioInterface() }
            .map { device ->
                UsbHardware(
                    deviceName = device.deviceName,
                    productName = device.productName ?: "USB device",
                    manufacturerName = device.manufacturerName ?: "",
                    vendorId = device.vendorId,
                    productId = device.productId,
                    hasPermission = usbManager.hasPermission(device),
                )
            }
            .sortedBy { it.productName }

    fun usbDeviceByName(deviceName: String): UsbDevice? = usbManager.deviceList[deviceName]

    private fun UsbDevice.hasAudioInterface(): Boolean {
        for (i in 0 until interfaceCount) {
            if (getInterface(i).interfaceClass == UsbConstants.USB_CLASS_AUDIO) return true
        }
        // Some interfaces only declare the audio class on the device descriptor.
        return deviceClass == UsbConstants.USB_CLASS_AUDIO
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
}

/** A USB device visible on the bus that advertises an Audio Class interface. */
data class UsbHardware(
    val deviceName: String,
    val productName: String,
    val manufacturerName: String,
    val vendorId: Int,
    val productId: Int,
    val hasPermission: Boolean,
) {
    val isKnownDjHardware: Boolean get() = vendorId in DJ_VENDOR_IDS

    fun label(): String =
        if (manufacturerName.isNotBlank() && !productName.startsWith(manufacturerName)) {
            "$manufacturerName $productName"
        } else {
            productName
        }

    companion object {
        /** Pioneer DJ, Pioneer Corp and AlphaTheta. */
        val DJ_VENDOR_IDS = setOf(0x2B73, 0x08E4, 0x08E6, 0x29BA)
    }
}
