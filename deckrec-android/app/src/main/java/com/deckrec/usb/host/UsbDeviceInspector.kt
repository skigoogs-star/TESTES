package com.deckrec.usb.host

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Asks the platform for access to a USB device and reports everything it says about itself.
 *
 * This exists because the app cannot record from hardware it has never seen. Pioneer and AlphaTheta
 * units carry their audio on vendor-specific interfaces whose layout is published nowhere — the
 * Linux kernel's entries for them are hand-written, one model at a time, by someone who had the
 * hardware in front of them. For any unit missing from [PioneerQuirks], a report from a user with
 * the device on their desk is the only way it can ever be added.
 *
 * Nothing here changes any state on the device. The one write-shaped thing it does is the DJM-850
 * family's input-switch *read*, which is a documented, side-effect-free vendor request.
 */
class UsbDeviceInspector(context: Context) {

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    suspend fun inspect(deviceName: String, preferredRate: Int = 48000): UsbInspectionReport {
        val device = usbManager.deviceList[deviceName]
            ?: return failed(deviceName, "The device is no longer attached.")

        val quirk = PioneerQuirks.find(device.vendorId, device.productId)
        if (!requestPermission(device)) {
            return UsbInspectionReport(
                productName = device.productName ?: "USB device",
                manufacturerName = device.manufacturerName.orEmpty(),
                vendorId = device.vendorId,
                productId = device.productId,
                permissionGranted = false,
                rawDescriptors = null,
                parsed = null,
                profile = null,
                knownModel = quirk?.name,
                vendorProbe = null,
                failure = "Permission to use the device was not granted.",
            )
        }

        var connection: UsbDeviceConnection? = null
        return try {
            connection = usbManager.openDevice(device)
                ?: return failed(deviceName, "The device could not be opened.", quirk?.name)

            val raw = connection.rawDescriptors
            val parsed = raw?.let { UsbDescriptors.parse(it) }
            val profile = parsed?.let { UsbStreamProfile.resolve(it, quirk, preferredRate) }

            UsbInspectionReport(
                productName = device.productName ?: "USB device",
                manufacturerName = device.manufacturerName.orEmpty(),
                vendorId = device.vendorId,
                productId = device.productId,
                permissionGranted = true,
                rawDescriptors = raw,
                parsed = parsed,
                profile = profile,
                knownModel = quirk?.name,
                vendorProbe = probeVendorRegisters(connection),
                failure = if (raw == null) "The device returned no descriptors." else null,
            )
        } catch (e: Throwable) {
            failed(deviceName, e.message ?: e.javaClass.simpleName, quirk?.name)
        } finally {
            runCatching { connection?.close() }
        }
    }

    /**
     * The DJM-850 family's "read input switch positions" request.
     *
     * Chosen because it is the only vendor request in the reverse-engineered documentation that is
     * unambiguously a read: `bmRequestType 0xC0` is device-to-host, and the captured traffic shows
     * the Windows utility issuing it several times a second purely to refresh its display. Hardware
     * that does not implement it simply stalls the request, which surfaces as a negative return —
     * harmless, and itself a useful answer about which register space the device speaks.
     */
    private fun probeVendorRegisters(connection: UsbDeviceConnection): UsbInspectionReport.VendorProbe {
        val buffer = ByteArray(5)
        val result = runCatching {
            connection.controlTransfer(0xC0, 0x00, 0x0000, 0x8002, buffer, buffer.size, 250)
        }.getOrDefault(-1)
        return UsbInspectionReport.VendorProbe(
            bytes = if (result > 0) buffer.copyOf(result) else null,
            returnCode = result,
        )
    }

    private suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true

        return suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != ACTION_PERMISSION) return
                    runCatching { appContext.unregisterReceiver(this) }
                    if (continuation.isActive) {
                        continuation.resume(
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        )
                    }
                }
            }

            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(ACTION_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            continuation.invokeOnCancellation { runCatching { appContext.unregisterReceiver(receiver) } }

            // The system fills the grant result into this intent, so it has to be mutable; an
            // immutable PendingIntent here simply never reports an answer.
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE

            val pending = PendingIntent.getBroadcast(
                appContext,
                0,
                Intent(ACTION_PERMISSION).setPackage(appContext.packageName),
                flags,
            )
            runCatching { usbManager.requestPermission(device, pending) }
                .onFailure {
                    runCatching { appContext.unregisterReceiver(receiver) }
                    if (continuation.isActive) continuation.resume(false)
                }
        }
    }

    private fun failed(deviceName: String, message: String, knownModel: String? = null) =
        UsbInspectionReport(
            productName = deviceName,
            manufacturerName = "",
            vendorId = 0,
            productId = 0,
            permissionGranted = false,
            rawDescriptors = null,
            parsed = null,
            profile = null,
            knownModel = knownModel,
            vendorProbe = null,
            failure = message,
        )

    private companion object {
        const val ACTION_PERMISSION = "com.deckrec.USB_PERMISSION"
    }
}
