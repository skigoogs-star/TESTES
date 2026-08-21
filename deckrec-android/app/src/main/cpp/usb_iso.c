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
 * exists, and it should never grow beyond that job — no threads, no ring buffer, no format
 * conversion. Those live in Kotlin where they can be tested without hardware.
 */

#include <jni.h>
#include <errno.h>
#include <linux/usbdevice_fs.h>
#include <string.h>
#include <sys/ioctl.h>

#define JNI_FN(name) Java_com_deckrec_usb_host_UsbIsoNative_##name

/*
 * Sizes and command numbers the Kotlin side asserts at startup.
 *
 * struct usbdevfs_urb has a trailing flexible array and is passed by pointer into the kernel; if a
 * future NDK laid it out differently, every field the kernel reads would be at the wrong offset and
 * the failure would look like arbitrary corruption rather than a layout problem. Cheaper to check.
 */
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

JNIEXPORT jlong JNICALL
JNI_FN(submitUrbCommand)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jlong) USBDEVFS_SUBMITURB;
}

JNIEXPORT jlong JNICALL
JNI_FN(reapUrbNonBlockingCommand)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jlong) USBDEVFS_REAPURBNDELAY;
}

JNIEXPORT jlong JNICALL
JNI_FN(discardUrbCommand)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jlong) USBDEVFS_DISCARDURB;
}

JNIEXPORT jint JNICALL
JNI_FN(isoUrbType)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    return (jint) USBDEVFS_URB_TYPE_ISO;
}

/*
 * Proves the library loaded, the headers resolved and the ABI is what Kotlin expects.
 *
 * Returns the number of iso packet descriptors that fit in one page, which is a value that could
 * only be computed with the real struct definitions in hand.
 */
JNIEXPORT jint JNICALL
JNI_FN(selfTest)(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;
    struct usbdevfs_urb urb;
    memset(&urb, 0, sizeof(urb));
    urb.type = USBDEVFS_URB_TYPE_ISO;
    urb.endpoint = 0x82;
    if (urb.type != USBDEVFS_URB_TYPE_ISO) {
        return -1;
    }
    return (jint) (4096 / sizeof(struct usbdevfs_iso_packet_desc));
}
