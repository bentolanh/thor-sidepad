package dev.lbento.thorsidepad.inject

/** JNI surface of libsidepad_native.so. Only meaningful inside the Shizuku user service process. */
object Native {
    init { System.loadLibrary("sidepad_native") }

    @JvmStatic external fun openDevice(path: String, readWrite: Boolean): Int
    @JvmStatic external fun closeDevice(fd: Int)
    @JvmStatic external fun deviceName(fd: Int): String?
    @JvmStatic external fun deviceCodes(fd: Int, evType: Int): IntArray?
    @JvmStatic external fun absInfo(fd: Int, code: Int): IntArray?
    @JvmStatic external fun writeEvent(fd: Int, type: Int, code: Int, value: Int): Int
    @JvmStatic external fun readEvent(fd: Int, timeoutMs: Int): IntArray?
    @JvmStatic external fun createUinput(
        name: String, vendor: Int, product: Int,
        keys: IntArray, absCodes: IntArray, absMin: IntArray, absMax: IntArray,
    ): Int
    @JvmStatic external fun destroyUinput(fd: Int)
    @JvmStatic external fun strerror(err: Int): String
}
