package dev.lbento.thorsidepad.pad

/**
 * What the playing session is doing. The service refreshes it while a media or video unit is on the pad,
 * and every window that draws one reads it.
 */
class NowPlaying {
    @Volatile var app = ""
    @Volatile var pkg = ""
    @Volatile var playing = false
    @Volatile var position = 0L
    @Volatile var duration = 0L

    val fraction: Float get() = if (duration > 0L) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    companion object {
        fun time(ms: Long): String {
            if (ms < 0L) return "--:--"
            val t = ms / 1000
            val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}
