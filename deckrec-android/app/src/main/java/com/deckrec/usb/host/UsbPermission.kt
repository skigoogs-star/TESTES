package com.deckrec.usb.host

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Asks the user for access to a USB device.
 *
 * Shared rather than duplicated because the details are easy to get subtly wrong and the failure is
 * silence: an immutable PendingIntent, or a receiver registered without [ContextCompat.RECEIVER_NOT_EXPORTED]
 * on a recent Android, produces a dialog whose answer never comes back, and the caller waits
 * forever with no error to report.
 */
object UsbPermission {

    private const val ACTION = "com.deckrec.USB_PERMISSION"

    suspend fun request(context: Context, device: UsbDevice): Boolean {
        val appContext = context.applicationContext
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) return true

        return suspendCancellableCoroutine { continuation ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != ACTION) return
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
                IntentFilter(ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            continuation.invokeOnCancellation { runCatching { appContext.unregisterReceiver(receiver) } }

            // The system fills the grant result into this intent, so it must be mutable; an
            // immutable one simply never reports an answer.
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE

            val pending = PendingIntent.getBroadcast(
                appContext,
                0,
                Intent(ACTION).setPackage(appContext.packageName),
                flags,
            )
            runCatching { usbManager.requestPermission(device, pending) }
                .onFailure {
                    runCatching { appContext.unregisterReceiver(receiver) }
                    if (continuation.isActive) continuation.resume(false)
                }
        }
    }
}
