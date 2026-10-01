/*
 * Isochronous USB capture over usbfs.
 *
 * Android's Java USB API supports control, bulk and interrupt transfers only. Isochronous is the
 * one thing it cannot do, and it is the only transfer type a USB audio stream uses — so a mixer
 * whose audio sits on a vendor-specific interface, which the platform's audio system will never
 * bind, is unreachable from Kotlin alone.
 *
 * The way through is the file descriptor behind UsbDeviceConnection: it is an open handle on the
 * device's usbfs node, and the URB ioctls are available on it. That is the whole reason this file
 * exists, and it deliberately does no more than that job — no format conversion, no channel
 * selection, no policy. Those live in Kotlin where they can be tested without hardware.
 */

#include <jni.h>

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <time.h>
#include <unistd.h>

#define JNI_FN(name) Java_com_deckrec_usb_host_UsbIsoNative_##name

#define TAG "DeckRec/UsbIso"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

/* How long the pump blocks in poll() before rechecking its stop flag. */
#define POLL_TIMEOUT_MS 100

/* Bound on how long stop() waits for a reader to leave the ring before freeing it. */
#define READER_DRAIN_TIMEOUT_MS 1000

struct iso_stream {
    int fd; /* our own dup: native closes only what native opened */
    int endpoint;
    int slot_bytes;
    int packets_per_urb;
    int urb_count;
    int frame_bytes; /* 1 means "frame size unknown, do not align" */

    struct usbdevfs_urb **urbs;
    uint8_t *slab; /* one allocation backing every packet slot of every URB */

    pthread_t pump;
    bool pump_started;
    atomic_bool running;
    atomic_bool disconnected;
    atomic_int last_errno;

    /* Single producer (the pump), single consumer (the engine's capture thread). */
    uint8_t *ring;
    size_t ring_capacity;
    size_t ring_head;
    size_t ring_size;
    bool ring_closed;
    pthread_mutex_t lock;
    pthread_cond_t filled;
    atomic_int readers_inside;

    uint8_t *staging; /* copy target while the lock is held, so JNI never runs under it */
    size_t staging_bytes;

    atomic_ullong bytes_received;
    atomic_ullong packets_data;
    atomic_ullong packets_empty;
    atomic_ullong packets_error;
    atomic_ullong packets_overflow;
    atomic_ullong ring_dropped;
    atomic_int reap_high_water;

    /* Pump-thread only. */
    int gcd_bytes;
    int distinct_lengths;
    int seen_lengths[8];
};

/* ---- ring ------------------------------------------------------------------------------- */

static void ring_close(struct iso_stream *s) {
    pthread_mutex_lock(&s->lock);
    s->ring_closed = true;
    pthread_cond_broadcast(&s->filled);
    pthread_mutex_unlock(&s->lock);
}

/*
 * Drop-newest on overflow, and always a whole number of frames.
 *
 * Byte-granular truncation would shear a frame in half the first time the consumer stalled, and
 * every sample after that would land one channel to the left — a fault that survives to the file
 * and is inaudible as anything but "the recording is wrong". Device packets always contain whole
 * frames, so aligned drops leave the stream decodable across any overflow.
 */
static void ring_write(struct iso_stream *s, const uint8_t *src, int length) {
    pthread_mutex_lock(&s->lock);
    size_t room = s->ring_capacity - s->ring_size;
    size_t take = (size_t) length < room ? (size_t) length : room;
    if (s->frame_bytes > 1) {
        take -= take % (size_t) s->frame_bytes;
    }
    if (take < (size_t) length) {
        atomic_fetch_add(&s->ring_dropped, (unsigned long long) ((size_t) length - take));
    }
    if (take > 0) {
        size_t tail = (s->ring_head + s->ring_size) % s->ring_capacity;
        size_t first = take < s->ring_capacity - tail ? take : s->ring_capacity - tail;
        memcpy(s->ring + tail, src, first);
        if (first < take) {
            memcpy(s->ring, src + first, take - first);
        }
        s->ring_size += take;
        pthread_cond_broadcast(&s->filled);
    }
    pthread_mutex_unlock(&s->lock);
}

static void deadline_after(struct timespec *ts, int millis) {
    clock_gettime(CLOCK_MONOTONIC, ts);
    ts->tv_sec += millis / 1000;
    ts->tv_nsec += (long) (millis % 1000) * 1000000L;
    if (ts->tv_nsec >= 1000000000L) {
        ts->tv_sec += 1;
        ts->tv_nsec -= 1000000000L;
    }
}

/* ---- pump ------------------------------------------------------------------------------- */

static void mark_disconnected(struct iso_stream *s, int err) {
    atomic_store(&s->disconnected, true);
    atomic_store(&s->last_errno, err);
    ring_close(s);
}

static int gcd_of(int a, int b) {
    while (b != 0) {
        int t = a % b;
        a = b;
        b = t;
    }
    return a;
}

/*
 * Tracks the greatest common divisor of the packet sizes actually delivered.
 *
 * An asynchronous source varies how many whole frames it puts in each packet to track its own
 * clock, so the gcd of a few differing sizes converges on the frame size. That is the only way to
 * learn the channel count of hardware with no entry in the endpoint table.
 */
static void observe_length(struct iso_stream *s, int length) {
    s->gcd_bytes = s->gcd_bytes == 0 ? length : gcd_of(s->gcd_bytes, length);
    for (int i = 0; i < s->distinct_lengths; i++) {
        if (s->seen_lengths[i] == length) {
            return;
        }
    }
    if (s->distinct_lengths < (int) (sizeof(s->seen_lengths) / sizeof(s->seen_lengths[0]))) {
        s->seen_lengths[s->distinct_lengths++] = length;
    }
}

static void rearm(struct iso_stream *s, struct usbdevfs_urb *urb) {
    for (int k = 0; k < urb->number_of_packets; k++) {
        urb->iso_frame_desc[k].length = (unsigned int) s->slot_bytes;
        urb->iso_frame_desc[k].actual_length = 0;
        urb->iso_frame_desc[k].status = 0;
    }
    urb->buffer_length = s->packets_per_urb * s->slot_bytes;
    urb->actual_length = 0;
    urb->status = 0;
}

static void *pump_main(void *arg) {
    struct iso_stream *s = (struct iso_stream *) arg;
    setpriority(PRIO_PROCESS, (id_t) gettid(), -19);

    while (atomic_load(&s->running)) {
        /* usbfs reports completed URBs as writability, not readability. */
        struct pollfd p = {.fd = s->fd, .events = POLLOUT, .revents = 0};
        int ready = poll(&p, 1, POLL_TIMEOUT_MS);
        if (ready < 0) {
            if (errno == EINTR) {
                continue;
            }
            mark_disconnected(s, errno);
            break;
        }
        if ((p.revents & (POLLERR | POLLHUP)) != 0) {
            mark_disconnected(s, ENODEV);
            break;
        }
        if (ready == 0) {
            continue;
        }

        int reaped = 0;
        struct usbdevfs_urb *done = NULL;
        while (ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &done) == 0 && done != NULL) {
            reaped++;
            for (int k = 0; k < done->number_of_packets; k++) {
                struct usbdevfs_iso_packet_desc *d = &done->iso_frame_desc[k];
                /* The kernel declares this field unsigned, but writes negative errno values into
                 * it. Compared as unsigned, -EOVERFLOW is 4294967221 and no error would ever
                 * match — the packets would be accepted as valid audio. */
                int packet_status = (int) d->status;
                if (packet_status == -EOVERFLOW) {
                    /* Impossible with max-packet-sized slots; if it ever fires the geometry is
                     * wrong and the recording is suspect, so it gets its own counter for life. */
                    atomic_fetch_add(&s->packets_overflow, 1ULL);
                    continue;
                }
                if (packet_status != 0) {
                    atomic_fetch_add(&s->packets_error, 1ULL);
                    continue;
                }
                if (d->actual_length == 0) {
                    /* Normal for an async endpoint, and the fingerprint of a stream that never
                     * starts if it is the *only* thing that ever arrives. */
                    atomic_fetch_add(&s->packets_empty, 1ULL);
                    continue;
                }
                observe_length(s, (int) d->actual_length);
                ring_write(s, (uint8_t *) done->buffer + (size_t) k * (size_t) s->slot_bytes,
                           (int) d->actual_length);
                atomic_fetch_add(&s->packets_data, 1ULL);
                atomic_fetch_add(&s->bytes_received, (unsigned long long) d->actual_length);
            }

            rearm(s, done);
            if (!atomic_load(&s->running)) {
                break;
            }
            if (ioctl(s->fd, USBDEVFS_SUBMITURB, done) < 0) {
                atomic_store(&s->last_errno, errno);
                if (errno == ENODEV || errno == ESHUTDOWN) {
                    mark_disconnected(s, errno);
                    goto finished;
                }
            }
            done = NULL;
        }

        if (reaped > atomic_load(&s->reap_high_water)) {
            atomic_store(&s->reap_high_water, reaped);
        }
        /* EAGAIN just means the completion queue is empty, which is the normal exit. */
        if (errno == ENODEV || errno == ESHUTDOWN) {
            mark_disconnected(s, errno);
            break;
        }
    }

finished:
    ring_close(s);
    return NULL;
}

/* ---- lifecycle -------------------------------------------------------------------------- */

static void free_stream(struct iso_stream *s) {
    if (s->urbs != NULL) {
        for (int i = 0; i < s->urb_count; i++) {
            free(s->urbs[i]);
        }
        free(s->urbs);
    }
    free(s->slab);
    free(s->ring);
    free(s->staging);
    if (s->fd >= 0) {
        close(s->fd);
    }
    pthread_cond_destroy(&s->filled);
    pthread_mutex_destroy(&s->lock);
    free(s);
}

/*
 * Discards every URB and reaps until the queue is empty.
 *
 * Not optional before freeing: a discarded URB still completes, and the kernel writes its status
 * and length into memory we are about to hand back to the allocator.
 */
static void drain_urbs(struct iso_stream *s) {
    for (int i = 0; i < s->urb_count; i++) {
        if (s->urbs[i] != NULL) {
            ioctl(s->fd, USBDEVFS_DISCARDURB, s->urbs[i]);
        }
    }
    struct usbdevfs_urb *done = NULL;
    int guard = s->urb_count * 4;
    while (guard-- > 0 && ioctl(s->fd, USBDEVFS_REAPURBNDELAY, &done) == 0) {
        done = NULL;
    }
}

JNIEXPORT jlong JNICALL
JNI_FN(nativeStart)(JNIEnv *env, jclass clazz, jint javaFd, jint endpoint, jint slotBytes,
                    jint packetsPerUrb, jint urbCount, jint frameBytes, jint ringCapacityBytes) {
    (void) env;
    (void) clazz;

    if (javaFd < 0 || slotBytes <= 0 || packetsPerUrb <= 0 || packetsPerUrb > 128 ||
        urbCount <= 0 || ringCapacityBytes <= 0) {
        return 0;
    }

    struct iso_stream *s = calloc(1, sizeof(struct iso_stream));
    if (s == NULL) {
        return 0;
    }
    s->fd = -1;
    s->endpoint = endpoint;
    s->slot_bytes = slotBytes;
    s->packets_per_urb = packetsPerUrb;
    s->urb_count = urbCount;
    s->frame_bytes = frameBytes > 0 ? frameBytes : 1;
    s->ring_capacity = (size_t) ringCapacityBytes;
    atomic_store(&s->running, true);

    pthread_mutex_init(&s->lock, NULL);
    pthread_condattr_t attr;
    pthread_condattr_init(&attr);
    /* Monotonic, so a timed read is not thrown off by the wall clock moving. */
    pthread_condattr_setclock(&attr, CLOCK_MONOTONIC);
    pthread_cond_init(&s->filled, &attr);
    pthread_condattr_destroy(&attr);

    /* Our own descriptor: the pump must never have the fd closed under a blocking ioctl by a
     * Java-side ordering mistake. It shares the open file description, so the interface claim made
     * through the framework applies to it. */
    s->fd = fcntl(javaFd, F_DUPFD_CLOEXEC, 0);
    if (s->fd < 0) {
        atomic_store(&s->last_errno, errno);
        free_stream(s);
        return 0;
    }

    s->slab = malloc((size_t) urbCount * (size_t) packetsPerUrb * (size_t) slotBytes);
    s->ring = malloc(s->ring_capacity);
    s->staging_bytes = (size_t) packetsPerUrb * (size_t) slotBytes;
    s->staging = malloc(s->staging_bytes);
    s->urbs = calloc((size_t) urbCount, sizeof(struct usbdevfs_urb *));
    if (s->slab == NULL || s->ring == NULL || s->staging == NULL || s->urbs == NULL) {
        free_stream(s);
        return 0;
    }

    size_t urb_bytes = sizeof(struct usbdevfs_urb) +
                       (size_t) packetsPerUrb * sizeof(struct usbdevfs_iso_packet_desc);
    for (int i = 0; i < urbCount; i++) {
        struct usbdevfs_urb *urb = calloc(1, urb_bytes);
        if (urb == NULL) {
            free_stream(s);
            return 0;
        }
        urb->type = USBDEVFS_URB_TYPE_ISO;
        urb->endpoint = (unsigned char) endpoint;
        urb->flags = USBDEVFS_URB_ISO_ASAP;
        urb->buffer = s->slab + (size_t) i * (size_t) packetsPerUrb * (size_t) slotBytes;
        urb->number_of_packets = packetsPerUrb;
        urb->signr = 0;
        urb->usercontext = urb;
        rearm(s, urb);
        s->urbs[i] = urb;
    }

    for (int i = 0; i < urbCount; i++) {
        if (ioctl(s->fd, USBDEVFS_SUBMITURB, s->urbs[i]) < 0) {
            int err = errno;
            LOGW("submit of URB %d failed: %s", i, strerror(err));
            atomic_store(&s->last_errno, err);
            /* Only the ones already queued may be in flight. */
            s->urb_count = i;
            drain_urbs(s);
            s->urb_count = urbCount;
            free_stream(s);
            return 0;
        }
    }

    if (pthread_create(&s->pump, NULL, pump_main, s) != 0) {
        atomic_store(&s->last_errno, errno);
        drain_urbs(s);
        free_stream(s);
        return 0;
    }
    s->pump_started = true;

    LOGI("streaming ep 0x%02X: %d URBs x %d packets x %d bytes, frame %d",
         endpoint, urbCount, packetsPerUrb, slotBytes, s->frame_bytes);
    return (jlong) (intptr_t) s;
}

JNIEXPORT jint JNICALL
JNI_FN(nativeRead)(JNIEnv *env, jclass clazz, jlong handle, jbyteArray dest, jint offset,
                   jint maxBytes, jint minBytes, jint timeoutMs) {
    (void) clazz;
    struct iso_stream *s = (struct iso_stream *) (intptr_t) handle;
    if (s == NULL || dest == NULL || maxBytes <= 0) {
        return -1;
    }

    atomic_fetch_add(&s->readers_inside, 1);

    size_t want = (size_t) maxBytes;
    if (want > s->staging_bytes) {
        want = s->staging_bytes;
    }

    pthread_mutex_lock(&s->lock);
    struct timespec deadline;
    deadline_after(&deadline, timeoutMs);
    while (s->ring_size < (size_t) minBytes && !s->ring_closed) {
        if (pthread_cond_timedwait(&s->filled, &s->lock, &deadline) == ETIMEDOUT) {
            break;
        }
    }

    size_t take = s->ring_size < want ? s->ring_size : want;
    if (s->frame_bytes > 1) {
        take -= take % (size_t) s->frame_bytes;
    }
    if (take > 0) {
        size_t first = take < s->ring_capacity - s->ring_head ? take : s->ring_capacity - s->ring_head;
        memcpy(s->staging, s->ring + s->ring_head, first);
        if (first < take) {
            memcpy(s->staging + first, s->ring, take - first);
        }
        s->ring_head = (s->ring_head + take) % s->ring_capacity;
        s->ring_size -= take;
    }
    bool closed = s->ring_closed;
    pthread_mutex_unlock(&s->lock);

    jint result;
    if (take > 0) {
        /* Copied out from under the lock: a JNI array operation can block on the collector, and
         * the pump must never be able to stall behind one. */
        (*env)->SetByteArrayRegion(env, dest, offset, (jsize) take, (const jbyte *) s->staging);
        result = (jint) take;
    } else {
        result = closed ? -1 : 0;
    }

    atomic_fetch_sub(&s->readers_inside, 1);
    return result;
}

JNIEXPORT void JNICALL
JNI_FN(nativeStats)(JNIEnv *env, jclass clazz, jlong handle, jlongArray out) {
    (void) clazz;
    struct iso_stream *s = (struct iso_stream *) (intptr_t) handle;
    if (s == NULL || out == NULL || (*env)->GetArrayLength(env, out) < 10) {
        return;
    }
    jlong values[10];
    values[0] = (jlong) atomic_load(&s->bytes_received);
    values[1] = (jlong) atomic_load(&s->packets_data);
    values[2] = (jlong) atomic_load(&s->packets_empty);
    values[3] = (jlong) atomic_load(&s->packets_error);
    values[4] = (jlong) atomic_load(&s->packets_overflow);
    values[5] = (jlong) atomic_load(&s->ring_dropped);
    values[6] = (jlong) s->gcd_bytes;
    values[7] = (jlong) s->distinct_lengths;
    values[8] = atomic_load(&s->disconnected) ? 1 : 0;
    values[9] = (jlong) atomic_load(&s->last_errno);
    (*env)->SetLongArrayRegion(env, out, 0, 10, values);
}

JNIEXPORT jint JNICALL
JNI_FN(nativeReapHighWater)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    struct iso_stream *s = (struct iso_stream *) (intptr_t) handle;
    return s == NULL ? 0 : atomic_load(&s->reap_high_water);
}

JNIEXPORT void JNICALL
JNI_FN(nativeStop)(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    struct iso_stream *s = (struct iso_stream *) (intptr_t) handle;
    if (s == NULL) {
        return;
    }

    atomic_store(&s->running, false);
    if (s->pump_started) {
        pthread_join(s->pump, NULL);
    }
    drain_urbs(s);
    ring_close(s);

    /* A reader can be blocked inside the ring when the UI tears a session down, and the engine's
     * joins tolerate a thread that outlives them. Waiting here is what makes that survivable
     * rather than a use-after-free. */
    for (int waited = 0; waited < READER_DRAIN_TIMEOUT_MS && atomic_load(&s->readers_inside) > 0;
         waited++) {
        struct timespec ms = {.tv_sec = 0, .tv_nsec = 1000000L};
        nanosleep(&ms, NULL);
    }
    if (atomic_load(&s->readers_inside) > 0) {
        /* Leaking is the lesser evil: freeing under a live reader corrupts memory, and a session
         * is torn down at most a handful of times in an app's life. */
        LOGW("a reader is still inside the ring; leaking the stream rather than freeing it");
        return;
    }

    LOGI("stopped: %llu bytes, %llu data / %llu empty / %llu error packets, %llu dropped",
         (unsigned long long) atomic_load(&s->bytes_received),
         (unsigned long long) atomic_load(&s->packets_data),
         (unsigned long long) atomic_load(&s->packets_empty),
         (unsigned long long) atomic_load(&s->packets_error),
         (unsigned long long) atomic_load(&s->ring_dropped));
    free_stream(s);
}

/* ---- ABI self-check --------------------------------------------------------------------- */

JNIEXPORT jint JNICALL
JNI_FN(urbStructSize)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jint) sizeof(struct usbdevfs_urb);
}

JNIEXPORT jint JNICALL
JNI_FN(isoPacketStructSize)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jint) sizeof(struct usbdevfs_iso_packet_desc);
}

JNIEXPORT jint JNICALL
JNI_FN(isoUrbType)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jint) USBDEVFS_URB_TYPE_ISO;
}

JNIEXPORT jint JNICALL
JNI_FN(selfTest)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jint) (4096 / sizeof(struct usbdevfs_iso_packet_desc));
}
