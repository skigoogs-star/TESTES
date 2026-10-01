package com.deckrec.usb.host

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the lifetime of a direct USB capture session.
 *
 * Two reasons this is not the engine's job.
 *
 * Opening a session needs a permission dialog, so it suspends. The engine's capture and monitor
 * threads are joined on a three-second timeout, and parking one of them on a user's decision
 * manufactures precisely the wedged-monitor state the engine's generation counter exists to
 * prevent. So the session is opened where the input is chosen, and the engine is only ever handed
 * one that already exists.
 *
 * And a monitor and a recording pass the same stream between them. If each closed the session it
 * was reading from, a monitor thread waking after its join would close the stream the recording had
 * just started using — the same class of bug as a stale monitor overwriting a live DSP chain, one
 * layer further down. Here, exactly one owner decides when a session really ends.
 */
class UsbCaptureController(context: Context) {

    private val appContext = context.applicationContext
    private val mutex = Mutex()

    @Volatile
    private var session: UsbIsoSession? = null

    @Volatile
    private var openFor: String? = null

    private val _status = MutableStateFlow<String?>(null)

    /** Why direct capture is unavailable, or null when it is ready or not wanted. */
    val status: StateFlow<String?> = _status.asStateFlow()

    /** The live session for [deviceName], or null if none is open for that device. */
    fun sessionFor(deviceName: String): UsbIsoSession? =
        if (openFor == deviceName) session else null

    val isOpen: Boolean get() = session != null

    /**
     * Opens a session for the device, replacing any other.
     *
     * Returns true when the stream is running and verified. Failures are reported through [status]
     * rather than thrown: this is called from input selection, where the useful outcome is a
     * message on the record screen, not a crash.
     */
    suspend fun open(deviceName: String, sampleRate: Int): Boolean = mutex.withLock {
        if (openFor == deviceName && session != null) return@withLock true
        closeLocked()

        val usbManager = appContext.getSystemService(Context.USB_SERVICE)
            as android.hardware.usb.UsbManager
        val device = usbManager.deviceList[deviceName]
        if (device == null) {
            _status.value = "The mixer is no longer connected."
            return@withLock false
        }

        return@withLock try {
            val opened = UsbIsoSession.open(appContext, device, sampleRate)
            session = opened
            openFor = deviceName
            _status.value = null
            Log.i(TAG, "direct capture ready for $deviceName")
            true
        } catch (e: UsbCaptureException) {
            _status.value = e.message
            Log.w(TAG, "direct capture unavailable: ${e.message}")
            false
        } catch (e: Throwable) {
            _status.value = "Direct USB capture failed: ${e.message}"
            Log.e(TAG, "direct capture failed", e)
            false
        }
    }

    /** Releases the device back to the system. Safe to call when nothing is open. */
    suspend fun close() = mutex.withLock { closeLocked() }

    private fun closeLocked() {
        session?.let { open ->
            val stats = runCatching { open.stats() }.getOrNull()
            runCatching { open.close() }
            Log.i(TAG, "direct capture closed: ${stats?.describe() ?: "no statistics"}")
        }
        session = null
        openFor = null
    }

    fun clearStatus() {
        _status.value = null
    }

    private companion object {
        const val TAG = "DeckRec/UsbCapture"
    }
}
