package dev.lbento.thorsidepad.inject

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.lbento.thorsidepad.BuildConfig
import rikka.shizuku.Shizuku

/** App-process side: Shizuku permission state and the bound [IInjector] user service. */
object Injector {
    private const val TAG = "SidePadClient"

    enum class ShizukuState { NOT_RUNNING, NO_PERMISSION, READY }

    @Volatile private var service: IInjector? = null
    private val waiters = ArrayList<(IInjector?) -> Unit>()
    private var binding = false
    private val main = Handler(Looper.getMainLooper())
    private val bindTimeout = Runnable {
        Log.w(TAG, "user service bind timed out")
        binding = false
        flush(null)
    }

    fun state(): ShizukuState = try {
        when {
            !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
            else -> ShizukuState.NO_PERMISSION
        }
    } catch (e: Throwable) { ShizukuState.NOT_RUNNING }

    private fun args(ctx: Context) = Shizuku.UserServiceArgs(
        ComponentName(ctx.packageName, InjectorService::class.java.name))
        .daemon(false)
        .processNameSuffix("injector")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            main.removeCallbacks(bindTimeout)
            binding = false
            if (!binder.pingBinder()) { flush(null); return }
            val s = IInjector.Stub.asInterface(binder)
            try { binder.linkToDeath({ Log.w(TAG, "injector died"); service = null }, 0) } catch (_: Exception) {}
            service = s
            flush(s)
        }
        override fun onServiceDisconnected(name: ComponentName) { service = null }
    }

    private fun flush(s: IInjector?) {
        val list: List<(IInjector?) -> Unit>
        synchronized(waiters) { list = ArrayList(waiters); waiters.clear() }
        list.forEach { cb -> main.post { cb(s) } }
    }

    /** Calls back on the main thread with the service, or null if Shizuku is unavailable. */
    fun connect(ctx: Context, cb: (IInjector?) -> Unit) {
        service?.let { s -> if (s.asBinder().pingBinder()) { cb(s); return } else service = null }
        if (state() != ShizukuState.READY) { cb(null); return }
        synchronized(waiters) { waiters.add(cb) }
        if (!binding) {
            binding = true
            try {
                Shizuku.bindUserService(args(ctx.applicationContext), conn)
                main.postDelayed(bindTimeout, 10_000)
            } catch (e: Throwable) {
                Log.e(TAG, "bindUserService failed", e)
                binding = false
                flush(null)
            }
        }
    }

    fun current(): IInjector? = service

    fun disconnect(ctx: Context) {
        try { Shizuku.unbindUserService(args(ctx.applicationContext), conn, true) } catch (_: Throwable) {}
        service = null
    }
}
