// Thin JNI bridge to Linux evdev + uinput. Runs inside the Shizuku user service
// process (shell UID), which is what makes /dev/input/event* and /dev/uinput reachable.
#include <jni.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <string.h>
#include <poll.h>
#include <sys/ioctl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <android/log.h>
#include <stdlib.h>

#define TAG "SidePadNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define BITS_PER_LONG (8 * sizeof(unsigned long))
#define NBITS(x) ((((x) - 1) / BITS_PER_LONG) + 1)
#define TEST_BIT(bit, arr) (((arr)[(bit) / BITS_PER_LONG] >> ((bit) % BITS_PER_LONG)) & 1)

JNIEXPORT jint JNICALL
Java_dev_lbento_thorsidepad_inject_Native_openDevice(JNIEnv* env, jclass cls, jstring jpath, jboolean rw) {
    const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
    int fd = open(path, (rw ? O_RDWR : O_RDONLY) | O_NONBLOCK | O_CLOEXEC);
    int err = errno;
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return fd >= 0 ? fd : -err;
}

JNIEXPORT void JNICALL
Java_dev_lbento_thorsidepad_inject_Native_closeDevice(JNIEnv* env, jclass cls, jint fd) {
    if (fd >= 0) close(fd);
}

JNIEXPORT jstring JNICALL
Java_dev_lbento_thorsidepad_inject_Native_deviceName(JNIEnv* env, jclass cls, jint fd) {
    char name[256];
    memset(name, 0, sizeof(name));
    if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) return NULL;
    return (*env)->NewStringUTF(env, name);
}

// Returns every code whose capability bit is set for the given event type (EV_KEY or EV_ABS).
JNIEXPORT jintArray JNICALL
Java_dev_lbento_thorsidepad_inject_Native_deviceCodes(JNIEnv* env, jclass cls, jint fd, jint evType) {
    unsigned long bits[NBITS(KEY_MAX + 1)];
    memset(bits, 0, sizeof(bits));
    int maxCode = evType == EV_KEY ? KEY_MAX : (evType == EV_ABS ? ABS_MAX : 0);
    if (maxCode == 0) return NULL;
    if (ioctl(fd, EVIOCGBIT(evType, sizeof(bits)), bits) < 0) return NULL;
    int count = 0;
    for (int i = 0; i <= maxCode; i++) if (TEST_BIT(i, bits)) count++;
    jintArray out = (*env)->NewIntArray(env, count);
    if (count == 0) return out;
    jint* tmp = (jint*) malloc(sizeof(jint) * count);
    int n = 0;
    for (int i = 0; i <= maxCode; i++) if (TEST_BIT(i, bits)) tmp[n++] = i;
    (*env)->SetIntArrayRegion(env, out, 0, count, tmp);
    free(tmp);
    return out;
}

// [min, max, flat, fuzz] for an absolute axis, or null.
JNIEXPORT jintArray JNICALL
Java_dev_lbento_thorsidepad_inject_Native_absInfo(JNIEnv* env, jclass cls, jint fd, jint code) {
    struct input_absinfo ai;
    memset(&ai, 0, sizeof(ai));
    if (ioctl(fd, EVIOCGABS(code), &ai) < 0) return NULL;
    jint v[4] = { ai.minimum, ai.maximum, ai.flat, ai.fuzz };
    jintArray out = (*env)->NewIntArray(env, 4);
    (*env)->SetIntArrayRegion(env, out, 0, 4, v);
    return out;
}

JNIEXPORT jint JNICALL
Java_dev_lbento_thorsidepad_inject_Native_writeEvent(JNIEnv* env, jclass cls, jint fd, jint type, jint code, jint value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = (unsigned short) type;
    ev.code = (unsigned short) code;
    ev.value = value;
    ssize_t n = write(fd, &ev, sizeof(ev));
    return n >= 0 ? (jint) n : -errno;
}

// Blocks up to timeoutMs. Returns [type, code, value]; null on timeout; [-1, -errno, 0] on error.
JNIEXPORT jintArray JNICALL
Java_dev_lbento_thorsidepad_inject_Native_readEvent(JNIEnv* env, jclass cls, jint fd, jint timeoutMs) {
    struct pollfd p = { .fd = fd, .events = POLLIN };
    int r = poll(&p, 1, timeoutMs);
    if (r == 0) return NULL;
    jint v[3];
    if (r < 0) {
        if (errno == EINTR) return NULL;
        v[0] = -1; v[1] = -errno; v[2] = 0;
    } else {
        struct input_event ev;
        ssize_t n = read(fd, &ev, sizeof(ev));
        if (n == (ssize_t) sizeof(ev)) {
            v[0] = ev.type; v[1] = ev.code; v[2] = ev.value;
        } else if (n < 0 && errno == EAGAIN) {
            return NULL;
        } else {
            v[0] = -1; v[1] = n < 0 ? -errno : -EIO; v[2] = 0;
        }
    }
    jintArray out = (*env)->NewIntArray(env, 3);
    (*env)->SetIntArrayRegion(env, out, 0, 3, v);
    return out;
}

// Creates a uinput device. Returns fd >= 0, or -errno.
JNIEXPORT jint JNICALL
Java_dev_lbento_thorsidepad_inject_Native_createUinput(JNIEnv* env, jclass cls, jstring jname, jint vendor, jint product,
        jintArray jkeys, jintArray jabs, jintArray jabsMin, jintArray jabsMax, jintArray jrel) {
    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return -errno;

    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 || ioctl(fd, UI_SET_EVBIT, EV_SYN) < 0) goto fail;

    jsize nk = (*env)->GetArrayLength(env, jkeys);
    jint* keys = (*env)->GetIntArrayElements(env, jkeys, NULL);
    for (jsize i = 0; i < nk; i++) {
        if (ioctl(fd, UI_SET_KEYBIT, keys[i]) < 0) { LOGE("UI_SET_KEYBIT %d: %s", keys[i], strerror(errno)); }
    }
    (*env)->ReleaseIntArrayElements(env, jkeys, keys, JNI_ABORT);

    // Relative axes make the device a pointer as far as Android is concerned, which is what puts
    // a cursor on screen; a gamepad must not declare them.
    jsize nr = jrel ? (*env)->GetArrayLength(env, jrel) : 0;
    if (nr > 0) {
        if (ioctl(fd, UI_SET_EVBIT, EV_REL) < 0) goto fail;
        jint* rel = (*env)->GetIntArrayElements(env, jrel, NULL);
        for (jsize i = 0; i < nr; i++) {
            if (ioctl(fd, UI_SET_RELBIT, rel[i]) < 0) { LOGE("UI_SET_RELBIT %d: %s", rel[i], strerror(errno)); }
        }
        (*env)->ReleaseIntArrayElements(env, jrel, rel, JNI_ABORT);
    }

    jsize na = jabs ? (*env)->GetArrayLength(env, jabs) : 0;
    if (na > 0) {
        if (ioctl(fd, UI_SET_EVBIT, EV_ABS) < 0) goto fail;
        jint* abs = (*env)->GetIntArrayElements(env, jabs, NULL);
        jint* mins = (*env)->GetIntArrayElements(env, jabsMin, NULL);
        jint* maxs = (*env)->GetIntArrayElements(env, jabsMax, NULL);
        for (jsize i = 0; i < na; i++) {
            if (ioctl(fd, UI_SET_ABSBIT, abs[i]) < 0) { LOGE("UI_SET_ABSBIT %d: %s", abs[i], strerror(errno)); continue; }
            struct uinput_abs_setup s;
            memset(&s, 0, sizeof(s));
            s.code = (unsigned short) abs[i];
            s.absinfo.minimum = mins[i];
            s.absinfo.maximum = maxs[i];
            // Sticks get a small flat zone so a centred virtual stick reads as exactly 0.
            if (abs[i] == ABS_X || abs[i] == ABS_Y || abs[i] == ABS_RX || abs[i] == ABS_RY) {
                s.absinfo.flat = 128; s.absinfo.fuzz = 16;
            }
            if (ioctl(fd, UI_ABS_SETUP, &s) < 0) { LOGE("UI_ABS_SETUP %d: %s", abs[i], strerror(errno)); }
        }
        (*env)->ReleaseIntArrayElements(env, jabs, abs, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, jabsMin, mins, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, jabsMax, maxs, JNI_ABORT);
    }

    struct uinput_setup us;
    memset(&us, 0, sizeof(us));
    us.id.bustype = BUS_USB;
    us.id.vendor = (unsigned short) vendor;
    us.id.product = (unsigned short) product;
    us.id.version = 1;
    const char* name = (*env)->GetStringUTFChars(env, jname, NULL);
    strncpy(us.name, name, UINPUT_MAX_NAME_SIZE - 1);
    (*env)->ReleaseStringUTFChars(env, jname, name);
    if (ioctl(fd, UI_DEV_SETUP, &us) < 0) goto fail;
    if (ioctl(fd, UI_DEV_CREATE) < 0) goto fail;
    return fd;

fail: ;
    int err = errno;
    LOGE("createUinput failed: %s", strerror(err));
    close(fd);
    return -err;
}

JNIEXPORT void JNICALL
Java_dev_lbento_thorsidepad_inject_Native_destroyUinput(JNIEnv* env, jclass cls, jint fd) {
    if (fd < 0) return;
    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
}

JNIEXPORT jstring JNICALL
Java_dev_lbento_thorsidepad_inject_Native_strerror(JNIEnv* env, jclass cls, jint err) {
    return (*env)->NewStringUTF(env, strerror(err < 0 ? -err : err));
}
