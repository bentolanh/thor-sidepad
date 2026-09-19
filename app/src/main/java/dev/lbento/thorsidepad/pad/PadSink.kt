package dev.lbento.thorsidepad.pad

import dev.lbento.thorsidepad.inject.IInjector

/**
 * Where a press ends up.
 *
 * The pad itself has no opinion. It works out that a button means a key event or an axis value and
 * hands that over; something else decides whether it lands on this device through the kernel, or on
 * a machine across the room. Keeping that decision behind two methods is what lets the layout, the
 * profiles, turbo, hold and the rest stay exactly as they are whatever the destination turns out to
 * be.
 *
 * Both methods return what was written, or a negative number on failure, the way the injector
 * always has: the engine reads that to notice a destination that has gone away.
 */
interface PadSink {
    fun key(code: Int, down: Boolean): Int
    fun abs(code: Int, value: Int): Int
}

/**
 * A destination that is another machine, whichever radio it is reached over.
 *
 * Two of these exist and both will stay: Classic is what Windows and Android want, Low Energy is
 * the only one where the identity a host reads is ours to choose. They differ in nothing the pad
 * cares about, which is what this is for — the forwarder and the engine talk to this and never
 * learn which is underneath.
 */
interface PadTransport : PadSink {
    /** Whether a machine is on the other end right now. */
    val connected: Boolean
    /** Changes the state without sending. Used when a burst of events ends in a sync. */
    fun setKey(code: Int, down: Boolean)
    fun setAbs(code: Int, value: Int)
    /** Sends whatever the state currently says. */
    fun sync(): Int
    /** Tries the link again after it has dropped. What that means differs per radio. */
    fun reconnect()
    fun close()
}

/** The original destination: this device, through Shizuku and the kernel. */
class LocalSink(private val svc: IInjector) : PadSink {
    override fun key(code: Int, down: Boolean): Int = svc.key(code, down)
    override fun abs(code: Int, value: Int): Int = svc.abs(code, value)
}
