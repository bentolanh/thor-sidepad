package dev.lbento.thorsidepad.pad

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import dev.lbento.thorsidepad.inject.Injector

/**
 * App icons for the video unit's band. A normal app cannot see other packages without a broad
 * visibility permission, so the icon is fetched through the injector, which runs as the shell,
 * and kept here so the pad is not asking for it per frame.
 */
object AppIcons {
    private val cache = HashMap<String, Drawable?>()
    private val pending = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())

    /** The icon if it is already to hand; otherwise null now, and [onLoaded] once it arrives. */
    fun of(ctx: Context, pkg: String, onLoaded: () -> Unit = {}): Drawable? {
        if (pkg.isEmpty()) return null
        if (cache.containsKey(pkg)) return cache[pkg]
        if (!pending.add(pkg)) return null
        Thread {
            val d: Drawable? = try {
                val bytes = Injector.current()?.appIcon(pkg)
                if (bytes != null && bytes.isNotEmpty())
                    BitmapDrawable(ctx.resources, BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                else null
            } catch (_: Throwable) { null }
            main.post { cache[pkg] = d; pending.remove(pkg); onLoaded() }
        }.start()
        return null
    }
}
