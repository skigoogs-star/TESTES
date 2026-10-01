package com.deckrec.usb.host

/**
 * A single-producer, single-consumer byte ring with a blocking read.
 *
 * This sits between the USB pump thread, which must never block or the kernel's URB queue drains
 * and the stream glitches, and the engine's capture thread, which can be held up for as long as
 * half a second when the writer thread is waiting on the disk. `AudioRecord` gets that elasticity
 * from the hardware buffer the platform allocates for it; the direct USB path has to provide it.
 *
 * On overflow the *newest* bytes are dropped rather than the oldest. Overwriting the oldest would
 * corrupt audio the consumer is midway through reading, and for a recording it is better to lose a
 * few milliseconds cleanly at the moment of the stall — counted in [droppedBytes], surfaced to the
 * user — than to splice a discontinuity into the middle of the file.
 */
class ByteRingBuffer(val capacity: Int) {

    private val buffer = ByteArray(capacity)
    private val lock = Object()

    private var head = 0
    private var size = 0
    private var closed = false

    var droppedBytes: Long = 0
        private set

    val available: Int get() = synchronized(lock) { size }

    /**
     * Room for more bytes right now.
     *
     * Only meaningful to the single producer, and only as a lower bound — the consumer can free
     * more at any moment, but nothing else adds, so a write of this size will not be truncated.
     */
    val remaining: Int get() = synchronized(lock) { capacity - size }

    /**
     * Producer side, for a caller that cannot wait.
     *
     * Returns bytes accepted. Anything that did not fit is counted in [droppedBytes] and is gone —
     * this is not a short write to be retried, because the USB pump thread has no way to retry: it
     * has to get back to reaping URBs or the kernel queue drains and the stream breaks properly.
     * Use [remaining] first if you need to avoid dropping.
     */
    fun write(src: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        synchronized(lock) {
            if (closed) return 0
            val room = capacity - size
            val take = minOf(room, length)
            if (take < length) droppedBytes += (length - take)
            if (take == 0) return 0

            val tail = (head + size) % capacity
            val firstChunk = minOf(take, capacity - tail)
            System.arraycopy(src, offset, buffer, tail, firstChunk)
            if (firstChunk < take) {
                System.arraycopy(src, offset + firstChunk, buffer, 0, take - firstChunk)
            }
            size += take
            lock.notifyAll()
            return take
        }
    }

    /**
     * Consumer side. Blocks until at least [minimum] bytes are available, [timeoutMs] elapses or
     * the ring is closed, then copies out as much as fits.
     *
     * Returns the byte count, which is 0 on timeout and -1 once the ring is closed and drained. The
     * caller reads whole frames, so [minimum] is how it avoids being handed a partial one.
     */
    fun read(dest: ByteArray, offset: Int, length: Int, minimum: Int, timeoutMs: Long): Int {
        synchronized(lock) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            while (size < minimum && !closed) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) break
                // Object.wait takes milliseconds and treats 0 as "wait forever", so a sub-millisecond
                // remainder has to be rounded up rather than truncated to 0.
                val waitMs = (remainingNanos / 1_000_000).coerceAtLeast(1)
                try {
                    lock.wait(waitMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return -1
                }
            }
            if (size == 0) return if (closed) -1 else 0

            val take = minOf(size, length)
            val firstChunk = minOf(take, capacity - head)
            System.arraycopy(buffer, head, dest, offset, firstChunk)
            if (firstChunk < take) {
                System.arraycopy(buffer, 0, dest, offset + firstChunk, take - firstChunk)
            }
            head = (head + take) % capacity
            size -= take
            return take
        }
    }

    /** Wakes any blocked reader and makes every subsequent read return -1 once drained. */
    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    fun clear() {
        synchronized(lock) {
            head = 0
            size = 0
            droppedBytes = 0
        }
    }

    companion object {
        /** Four seconds of audio, which comfortably covers the engine's worst writer stall. */
        fun forStream(bytesPerFrame: Int, rate: Int, seconds: Int = 4): ByteRingBuffer =
            ByteRingBuffer(bytesPerFrame * rate * seconds)
    }
}
