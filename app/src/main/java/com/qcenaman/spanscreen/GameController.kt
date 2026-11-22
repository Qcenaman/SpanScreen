package com.qcenaman.spanscreen

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import android.view.Surface
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/** Host app side of game mode: talks to [GameService], which runs with shell rights through Shizuku. */
object GameController {
    data class PeerTile(val host: String, val w: Int, val h: Int, val mime: String, val rect: Rect01)
    data class Params(
        val vdW: Int, val vdH: Int, val dpi: Int, val fps: Int, val component: String,
        val localRect: Rect01?, val peers: List<PeerTile>,
    )

    @Volatile private var service: IGameService? = null
    @Volatile private var pending: Params? = null
    private var onError: ((String) -> Unit)? = null
    private var connection: ServiceConnection? = null

    fun shizukuState(): String = when {
        !Shizuku.pingBinder() -> "Shizuku isn't running on this device"
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Shizuku permission not granted yet (asked when you start a game)"
        else -> "Shizuku ready"
    }

    fun prepare(ctx: Context, params: Params, onError: (String) -> Unit) {
        pending = params
        this.onError = onError
        if (params.localRect == null) launch(ctx, null)
    }

    fun onLocalSurface(ctx: Context, surface: Surface) = launch(ctx, surface)

    private fun launch(ctx: Context, surface: Surface?) {
        val p = pending ?: return
        onMain {
            withService(ctx) { svc ->
                thread(name = "game-start") {
                    val err = try {
                        val sizes = IntArray(p.peers.size * 2)
                        val rects = FloatArray(p.peers.size * 4)
                        p.peers.forEachIndexed { i, t ->
                            sizes[2 * i] = t.w; sizes[2 * i + 1] = t.h
                            t.rect.toArray().copyInto(rects, 4 * i)
                        }
                        svc.start(
                            p.vdW, p.vdH, p.dpi, p.fps, p.component,
                            surface, p.localRect?.toArray(),
                            p.peers.map { it.host }.toTypedArray(), sizes,
                            p.peers.map { it.mime }.toTypedArray(), rects,
                        ) ?: ""
                    } catch (e: Exception) { e.toString() }
                    if (err.isNotEmpty()) onError?.invoke("Game failed: $err")
                    else onError?.invoke("Game running. Touch any screen to play; press Back on this device to stop.")
                }
            }
        }
    }

    private fun withService(ctx: Context, cb: (IGameService) -> Unit) {
        if (!Shizuku.pingBinder()) {
            onError?.invoke("Game mode needs Shizuku running on this (host) device. Install Shizuku, start it, then try again.")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.addRequestPermissionResultListener(object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (grantResult == PackageManager.PERMISSION_GRANTED) withService(ctx, cb)
                    else onError?.invoke("Shizuku permission was denied")
                }
            })
            Shizuku.requestPermission(1)
            return
        }
        service?.let { if (it.asBinder().pingBinder()) { cb(it); return } }
        val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, GameService::class.java.name))
            .daemon(false)
            .processNameSuffix("game")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null || !binder.pingBinder()) { onError?.invoke("Game service didn't start"); return }
                val s = IGameService.Stub.asInterface(binder)
                service = s
                cb(s)
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        connection = conn
        Shizuku.bindUserService(args, conn)
    }

    fun touch(kind: Int, key: Int, u: Float, v: Float) {
        try { service?.touch(kind, key, u, v) } catch (e: Exception) { Log.w("SpanScreen", "touch: $e") }
    }

    fun stop() {
        pending = null
        val s = service ?: return
        thread { try { s.stop() } catch (_: Exception) { } }
    }
}
