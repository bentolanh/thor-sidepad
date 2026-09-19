package dev.lbento.thorsidepad.inject;

import dev.lbento.thorsidepad.inject.IPadEvents;

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

    // Device levels, 0..1. Brightness per display through the display service; volume = media stream.
    float getBrightness(int displayId) = 13;
    void setBrightness(int displayId, float level) = 14;
    float getVolume() = 15;
    void setVolume(float level) = 16;
    // The Thor's second-screen volume (the media stream's hdmi device), set the way AYN's panel does.
    float getVolume2nd() = 17;
    void setVolume2nd(float level) = 18;
    // Brightness while a slider is being dragged: applied at once, without the system's ramp
    // animation, the way Android's own quick-settings slider does. setBrightness commits it.
    void setBrightnessLive(int displayId, float level) = 19;
    // A media key handed to whatever is playing, whichever app that is.
    void mediaKey(String action) = 20;
    // The playing session, as "package|state|positionMs|durationMs"; empty when nothing is playing.
    String mediaInfo() = 21;
    void mediaSeek(long posMs) = 22;
    void mediaSkip(int deltaMs) = 23;
    // An app's icon as a small PNG. The shell sees every package; a normal app would need a broad
    // visibility permission to ask for this itself.
    byte[] appIcon(String pkg) = 24;

    // Feasibility probe for mouse support: stands up a pointer device of its own, sweeps it, holds
    // it alive for holdMs so a screenshot can catch the cursor, then takes it down. Deliberately
    // does not touch the open pad, so the buttons keep working while it runs.
    String probePointer(int holdMs) = 25;

    // Watchdog. The pad is meant to stay up until the user says otherwise, but the app's own process
    // can be taken by the memory manager while this one cannot. Armed with the app's package and a
    // grace period, it brings the pad back when the heartbeats stop. A grace of 0 disarms it, which
    // is what the Stop button does, so quitting stays quitting.
    void keepAlive(String pkg, int graceMs) = 26;
    void heartbeat() = 27;

    // Read a physical controller's own events and hand them over, so the Thor's real sticks and
    // buttons can drive another machine. With grab set the device is taken away from this machine
    // while it runs, so a press does not also act here. Returns that device's capabilities as JSON,
    // in the same shape as targetCaps, or an "error: " string.
    String forwardStart(String path, boolean grab, IPadEvents cb) = 28;
    void forwardStop() = 29;
}
