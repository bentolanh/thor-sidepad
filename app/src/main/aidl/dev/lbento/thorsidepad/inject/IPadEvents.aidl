package dev.lbento.thorsidepad.inject;

/** Events read straight off a physical controller, so they can be sent somewhere else. */
oneway interface IPadEvents {
    void onEvent(int type, int code, int value);

    /**
     * The same, but saying which of the opened nodes it came from.
     *
     * A handheld does not necessarily put all of its controls on one input device. The Odin 2
     * Mini delivers Start and Select from a different node than its sticks and face buttons, so
     * a reader that opens one device silently loses them — which is not distinguishable, from
     * the outside, from the buttons not existing. Knowing the node is also what lets a player
     * calibrate: the same code can mean different things on different devices.
     *
     * `node` indexes the paths given to forwardStartMulti. -1 means the single-device path.
     */
    void onEventAt(int node, int type, int code, int value);
}
