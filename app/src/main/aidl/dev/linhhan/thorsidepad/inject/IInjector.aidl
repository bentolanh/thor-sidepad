package dev.linhhan.thorsidepad.inject;

interface IInjector {
    // Shizuku convention: this transaction code asks the user service to exit.
    void destroy() = 16777114;

    // JSON: {"uid":..,"uinput":"ok"|"<error>","devices":[{"path","name","keys":[..],"abs":[..]}]}
    String probe() = 1;

    // Open an existing controller node for writing. Returns "" on success or an error message.
    String openPhysical(String path) = 2;

    // Create a virtual gamepad via uinput. Returns "" on success or an error message.
    String openVirtual(String name, in int[] keys, in int[] absCodes, in int[] absMin, in int[] absMax) = 3;

    void closeTarget() = 4;
    boolean isOpen() = 5;

    // JSON: {"keys":[..],"abs":{"<code>":[min,max],..}} for the open target.
    String targetCaps() = 6;

    // Return bytes written, or -errno (e.g. -ENODEV once the node has been recreated).
    int key(int code, boolean down) = 7;
    int abs(int code, int value) = 8;

    // Press and release one key on any input node (e.g. the AYN key on gpio-keys). "" or error.
    String pressKeyOn(String path, int code, int holdMs) = 11;

    // Run a shell command as the shell user; returns combined output.
    String shell(String cmd) = 12;
}
