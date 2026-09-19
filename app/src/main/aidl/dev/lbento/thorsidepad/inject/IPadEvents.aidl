package dev.lbento.thorsidepad.inject;

/** Events read straight off a physical controller, so they can be sent somewhere else. */
oneway interface IPadEvents {
    void onEvent(int type, int code, int value);
}
