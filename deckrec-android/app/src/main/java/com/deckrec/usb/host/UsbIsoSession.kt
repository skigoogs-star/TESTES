package com.deckrec.usb.host

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** A capture failure with a message already fit to show a user. */
class UsbCaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * An open isochronous capture stream on a vendor-specific USB device.
 *
 * Opening one takes the device away from the kernel: these mixers are in the upstream USB audio
 * quirk table, so the system's own driver has very likely bound the streaming interface already,
 * and claiming it means forcing that driver off. The kernel does not rebind until the cable is
 * reseated — so for as long as this app has used a mixer directly, the platform will not offer it
 * as an ordinary input, which is expected rather than a fault, and is logged so it cannot be
 * mistaken for one in a field report.
 */
class UsbIsoSession private constructor(
    private val connection: UsbDeviceConnection,
    private val claimed: UsbInterface,
    private val idleSetting: UsbInterface?,
    val profile: UsbStreamProfile,
    private val handle: Long,
    /** False when the device ignored the sample-rate request; the rate check is then the arbiter. */
    val rateRequestAccepted: Boolean,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    val bytesPerFrame: Int get() = profile.bytesPerFrame

    /** Bytes read, always whole frames; 0 on timeout, -1 once the device is gone and drained. */
    fun read(dest: ByteArray, offset: Int, maxBytes: Int, timeoutMs: Int): Int {
        if (closed.get()) return -1
        return UsbIsoNative.nativeRead(handle, dest, offset, maxBytes, bytesPerFrame, timeoutMs)
    }

    fun stats(): UsbIsoStats = UsbIsoNative.statsFor(handle)

    /** Most URBs reaped in one wakeup; near the queue depth means the pump nearly starved. */
    fun reapHighWater(): Int = UsbIsoNative.nativeReapHighWater(handle)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        UsbIsoNative.nativeStop(handle)
        // Back to the alternate setting with no endpoints, so the device stops streaming into a
        // host that is no longer listening.
        idleSetting?.let { runCatching { connection.setInterface(it) } }
        runCatching { connection.releaseInterface(claimed) }
        runCatching { connection.close() }
        Log.i(
            TAG,
            "session closed; the kernel audio driver stays detached until the mixer is replugged, " +
                "so it will not appear as an ordinary input before then",
        )
    }

    companion object {
        private const val TAG = "DeckRec/UsbIso"

        /** Long enough for the byte rate to be unambiguous, short enough not to delay a start. */
        private const val RATE_CHECK_MILLIS = 500L

        private const val RING_SECONDS = 4

        /**
         * Opens a capture stream, or throws [UsbCaptureException] with a message worth showing.
         *
         * Suspends: the permission prompt is a dialog. This must never be called from the engine's
         * capture or monitor thread — those are joined with a three-second timeout, and blocking one
         * on a user decision manufactures exactly the wedged-monitor state the generation counter
         * exists to prevent. Open the session where the input is chosen, and hand the engine one
         * that already exists.
         */
        suspend fun open(
            context: Context,
            device: UsbDevice,
            preferredRate: Int,
        ): UsbIsoSession {
            if (!UsbIsoNative.isAvailable) {
                throw UsbCaptureException("Direct USB capture is not available on this phone.")
            }
            if (!UsbPermission.request(context, device)) {
                throw UsbCaptureException(
                    "Permission to use ${device.productName ?: "the mixer"} was not granted."
                )
            }

            val usbManager = context.applicationContext
                .getSystemService(Context.USB_SERVICE) as UsbManager
            val connection = usbManager.openDevice(device)
                ?: throw UsbCaptureException(
                    "${device.productName ?: "The mixer"} could not be opened — " +
                        "another app may be holding it."
                )

            var handle = 0L
            var claimed: UsbInterface? = null
            try {
                val raw = connection.rawDescriptors
                    ?: throw UsbCaptureException("The mixer returned no USB descriptors.")
                val quirk = PioneerQuirks.find(device.vendorId, device.productId)
                val profile = UsbStreamProfile.resolve(UsbDescriptors.parse(raw), quirk, preferredRate)
                    ?: throw UsbCaptureException(
                        "No isochronous audio input was found on ${device.productName}."
                    )

                claimed = device.interfaceAt(profile.interfaceNumber, alternateSetting = null)
                    ?: throw UsbCaptureException("Interface ${profile.interfaceNumber} is missing.")

                // Forced, because the system's USB audio driver has almost certainly bound this
                // interface already: these devices are in the kernel's quirk table. Without force
                // the claim simply fails and nothing explains why.
                if (!connection.claimInterface(claimed, true)) {
                    // The audio server may still be releasing the stream the meters were using.
                    delay(150)
                    if (!connection.claimInterface(claimed, true)) {
                        throw UsbCaptureException(
                            "The system audio driver would not release the mixer. " +
                                "Unplug it, plug it back in and try again."
                        )
                    }
                }

                val streaming = device.interfaceAt(profile.interfaceNumber, profile.alternateSetting)
                    ?: throw UsbCaptureException(
                        "The mixer does not expose streaming setting ${profile.alternateSetting}."
                    )
                if (!connection.setInterface(streaming)) {
                    throw UsbCaptureException("The mixer refused to enter its streaming mode.")
                }

                val rateAccepted = requestRate(connection, profile)

                handle = UsbIsoNative.nativeStart(
                    connection.fileDescriptor,
                    profile.endpointAddress,
                    profile.slotBytes,
                    UsbStreamProfile.PACKETS_PER_URB,
                    UsbStreamProfile.URBS_IN_FLIGHT,
                    if (profile.channelsAreProvisional) 1 else profile.bytesPerFrame,
                    profile.bytesPerFrame * profile.rate * RING_SECONDS,
                )
                if (handle == 0L) {
                    throw UsbCaptureException("The audio stream could not be started.")
                }

                verifyStream(handle, profile)

                val session = UsbIsoSession(
                    connection = connection,
                    claimed = claimed,
                    idleSetting = device.interfaceAt(profile.interfaceNumber, 0),
                    profile = profile,
                    handle = handle,
                    rateRequestAccepted = rateAccepted,
                )
                Log.i(TAG, "capturing ${profile.channels}ch @ ${profile.rate}Hz from ${device.productName}")
                return session
            } catch (e: Throwable) {
                if (handle != 0L) UsbIsoNative.nativeStop(handle)
                claimed?.let { runCatching { connection.releaseInterface(it) } }
                runCatching { connection.close() }
                throw e
            }
        }

        /**
         * Sends the vendor sample-rate request, tolerating refusal.
         *
         * Refusal is not treated as fatal because the kernel itself does not send this to every
         * multi-rate model — the DJM-A9 is absent from its switch — so a device may simply not
         * implement it while streaming perfectly well at whatever rate its panel is set to. What
         * must not happen is assuming the request worked: [verifyStream] measures instead.
         */
        private fun requestRate(connection: UsbDeviceConnection, profile: UsbStreamProfile): Boolean {
            val index = profile.rateControlIndex ?: return true
            val request = DjmVendorControl.setSampleRate(profile.rate, index)
            val sent = runCatching {
                connection.controlTransfer(
                    request.requestType,
                    request.request,
                    request.value,
                    request.index,
                    request.data,
                    request.length,
                    250,
                )
            }.getOrDefault(-1)
            if (sent != request.length) {
                Log.w(TAG, "the mixer did not accept the ${profile.rate}Hz request (returned $sent)")
                return false
            }
            return true
        }

        /**
         * Confirms audio is actually flowing, at the rate everything downstream assumes.
         *
         * Both halves matter and they fail differently. Nothing arriving at all is the signature of
         * hardware that accepts the stream and never sends — which no descriptor would reveal.
         * Arriving at the wrong rate is worse, because it looks entirely healthy: the meters move,
         * the file is written, and it plays back at the wrong speed.
         *
         * The rate is enforced rather than adopted. The recording session, its WAV header, the DSP
         * and the marker timeline are all built from the requested rate before capture opens, so
         * changing it here would mean mutating a live configuration — the exact class of bug this
         * codebase has been bitten by repeatedly. Fail at the seam instead.
         */
        private suspend fun verifyStream(handle: Long, profile: UsbStreamProfile) {
            val startedAt = System.nanoTime()
            delay(RATE_CHECK_MILLIS)
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
            val stats = UsbIsoNative.statsFor(handle)

            if (stats.disconnected) {
                throw UsbCaptureException("The mixer was disconnected.")
            }
            if (stats.streamingButSilent) {
                throw UsbCaptureException(
                    "The mixer accepted the connection but sent no audio. " +
                        "Check that its USB output is switched on."
                )
            }
            if (stats.bytesReceived == 0L) {
                val detail = if (stats.errorPackets > 0) {
                    "the bus reported ${stats.errorPackets} transfer errors"
                } else {
                    "no data arrived (errno ${stats.lastErrno})"
                }
                throw UsbCaptureException("No audio arrived from the mixer — $detail.")
            }

            val measured = stats.measuredRate(profile.bytesPerFrame, elapsedMillis)
            val nearest = (PioneerQuirks.ALL.flatMap { it.rates } + AudioRateCandidates)
                .distinct()
                .minByOrNull { abs(it - measured) }
                ?: return
            // The candidate rates are at least 8% apart, and half a second of counting resolves
            // far finer than that, so a mismatch here is real rather than measurement noise.
            if (nearest != profile.rate && abs(measured - profile.rate) > profile.rate / 20) {
                throw UsbCaptureException(
                    "The mixer is running at ${nearest / 1000f} kHz, not " +
                        "${profile.rate / 1000f} kHz. Change it on the mixer, or pick $nearest Hz " +
                        "in settings."
                )
            }
            Log.i(TAG, "stream verified: ${stats.describe()}, measured ${measured}Hz")
        }

        private val AudioRateCandidates = listOf(44100, 48000, 88200, 96000)

        /** The interface object for a number, optionally for one specific alternate setting. */
        private fun UsbDevice.interfaceAt(number: Int, alternateSetting: Int?): UsbInterface? {
            for (index in 0 until interfaceCount) {
                val candidate = getInterface(index)
                if (candidate.id != number) continue
                if (alternateSetting == null || candidate.alternateSetting == alternateSetting) {
                    return candidate
                }
            }
            return null
        }
    }
}
