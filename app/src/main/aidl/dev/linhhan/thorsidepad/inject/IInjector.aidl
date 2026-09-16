package dev.linhhan.thorsidepad.inject;

import dev.linhhan.thorsidepad.inject.IInjectorListener;

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

    void key(int code, boolean down) = 7;
    void abs(int code, int value) = 8;

    // Watch a controller node for all `codes` held together for holdMs; fires listener.onChord().
    String watchChord(String path, in int[] codes, int holdMs, IInjectorListener listener) = 9;
    void stopWatch() = 10;
}
