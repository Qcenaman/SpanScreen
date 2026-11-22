package com.qcenaman.spanscreen

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

/** Fullscreen view that shows this device's piece of the wall. */
class DisplayActivity : Activity() {
    private lateinit var root: FrameLayout
    private var current: Show.Content? = null
    private val handler = Handler(Looper.getMainLooper())
    private val onChange: () -> Unit = { render() }
    private var player: MediaPlayer? = null
    private var receiver: GameReceiver? = null
    private var maxRefresh = 60f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        pickFastestMode()
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        hideBars()
        Show.listeners.add(onChange)
        render()
    }

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent)
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onDestroy() {
        Show.listeners.remove(onChange)
        teardown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (Show.isHost) HostSession.stopAll() else finish()
    }

    private fun pickFastestMode() {
        val d = display ?: return
        val cur = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        maxRefresh = best.refreshRate
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    private fun render() {
        val c = Show.content
        Log.i("SpanScreen", "render $c (was $current) host=${Show.isHost}")
        if (c == current) return
        teardown()
        current = c
        when (c) {
            Show.Content.None -> finish()
            is Show.Content.Image -> showImage(c)
            is Show.Content.Video -> showVideo(c)
            is Show.Content.Game -> showGame(c)
        }
    }

    private fun teardown() {
        handler.removeCallbacksAndMessages(null)
        player?.let { try { it.release() } catch (_: Exception) { } }
        player = null
        receiver?.shutdown()
        receiver = null
        root.removeAllViews()
        root.setOnTouchListener(null)
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        (display ?: windowManager.defaultDisplay).getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    // ---------- Picture ----------

    private fun showImage(c: Show.Content.Image) {
        val view = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        val (sw, sh) = screenSize()
        thread(name = "image-piece") {
            val bmp = try { cutPiece(c.path, c.rect, sw, sh) } catch (e: Throwable) { Log.w("SpanScreen", "image: $e"); null }
            runOnUiThread { if (current == c && bmp != null) view.setImageBitmap(bmp) }
        }
    }

    /** Decodes only this screen's part of the picture, at this screen's full resolution. */
    @Suppress("DEPRECATION")
    private fun cutPiece(path: String, r: Rect01, sw: Int, sh: Int): Bitmap {
        val dec = BitmapRegionDecoder.newInstance(path, false)!!
        val iw = dec.width.toFloat(); val ih = dec.height.toFloat()
        val src = RectF(r.left * iw, r.top * ih, r.right * iw, r.bottom * ih)
        val clip = RectF(src)
        val out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        if (!clip.intersect(0f, 0f, iw, ih)) { dec.recycle(); return out }
        var sample = 1
        while (src.width() / (sample * 2) >= sw) sample *= 2
        val region = Rect(clip.left.toInt(), clip.top.toInt(), clip.right.toInt(), clip.bottom.toInt())
        val piece = dec.decodeRegion(region, BitmapFactory.Options().apply { inSampleSize = sample })
        dec.recycle()
        val dst = RectF(
            (clip.left - src.left) / src.width() * sw,
            (clip.top - src.top) / src.height() * sh,
            (clip.right - src.left) / src.width() * sw,
            (clip.bottom - src.top) / src.height() * sh,
        )
        canvas.drawBitmap(piece, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        piece.recycle()
        return out
    }

    // ---------- Video ----------

    private fun showVideo(c: Show.Content.Video) {
        val view = TextureView(this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                applyCrop(view, c.rect, w, h)
                startPlayer(c, Surface(st))
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyCrop(view, c.rect, w, h)
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { player?.release(); player = null; return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun applyCrop(view: TextureView, r: Rect01, w: Int, h: Int) {
        val m = Matrix()
        m.setScale(1f / r.width, 1f / r.height)
        m.postTranslate(-r.left / r.width * w, -r.top / r.height * h)
        view.setTransform(m)
    }

    private fun startPlayer(c: Show.Content.Video, surface: Surface) {
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(c.path)
            mp.setSurface(surface)
            mp.isLooping = true
            if (!Show.isHost) mp.setVolume(0f, 0f)
            mp.setOnPreparedListener {
                if (player !== mp) return@setOnPreparedListener
                val startLocal = c.startHostTime - Show.clockOffset()
                val delay = startLocal - SystemClock.elapsedRealtime()
                if (delay > 0) {
                    handler.postDelayed({ if (player === mp) { mp.start(); scheduleSync(c, mp) } }, delay)
                } else {
                    mp.seekTo(expectedPosition(c, mp.duration).toInt())
                    mp.start()
                    scheduleSync(c, mp)
                }
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w("SpanScreen", "video: $e")
        }
    }

    private fun expectedPosition(c: Show.Content.Video, duration: Int): Long {
        val hostNow = SystemClock.elapsedRealtime() + Show.clockOffset()
        val t = hostNow - c.startHostTime
        return if (duration > 0) ((t % duration) + duration) % duration else t.coerceAtLeast(0)
    }

    /** Keeps every screen on the same frame by nudging playback speed, or seeking if far off. */
    private fun scheduleSync(c: Show.Content.Video, mp: MediaPlayer) {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (player !== mp) return
                try {
                    val dur = mp.duration
                    if (dur > 0 && mp.isPlaying) {
                        var diff = mp.currentPosition - expectedPosition(c, dur)
                        if (diff > dur / 2) diff -= dur
                        if (diff < -dur / 2) diff += dur
                        when {
                            abs(diff) > 250 -> mp.seekTo(((expectedPosition(c, dur) + 60) % dur).toInt())
                            abs(diff) > 12 -> mp.playbackParams = PlaybackParams().setSpeed((1f - diff / 1000f).coerceIn(0.9f, 1.1f))
                            else -> if (mp.playbackParams.speed != 1f) mp.playbackParams = PlaybackParams().setSpeed(1f)
                        }
                    }
                } catch (_: Exception) { }
                handler.postDelayed(this, 500)
            }
        }, 500)
    }

    // ---------- Game ----------

    private fun showGame(c: Show.Content.Game) {
        val view = SurfaceView(this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        view.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i("SpanScreen", "game surface created, host=${Show.isHost}")
                if (Build.VERSION.SDK_INT >= 30) {
                    try { holder.surface.setFrameRate(maxRefresh, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) } catch (_: Exception) { }
                }
                if (Show.isHost) GameController.onLocalSurface(applicationContext, holder.surface)
                else receiver = GameReceiver(holder.surface, VIDEO_PORT).also { it.start() }
            }
            override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i("SpanScreen", "game surface destroyed")
                receiver?.shutdown(); receiver = null
            }
        })
        root.setOnTouchListener { v, e -> forwardTouch(v, e); true }
    }

    private fun forwardTouch(v: View, e: MotionEvent) {
        val w = v.width.toFloat().coerceAtLeast(1f)
        val h = v.height.toFloat().coerceAtLeast(1f)
        fun send(kind: Int, i: Int) {
            val id = e.getPointerId(i)
            val x = e.getX(i) / w
            val y = e.getY(i) / h
            if (Show.isHost) HostSession.touchSelf(kind, id, x, y) else PeerSession.sendTouch(kind, id, x, y)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> send(PointerTracker.DOWN, e.actionIndex)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> send(PointerTracker.UP, e.actionIndex)
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) send(PointerTracker.MOVE, i)
            MotionEvent.ACTION_CANCEL -> for (i in 0 until e.pointerCount) send(PointerTracker.CANCEL, i)
        }
    }
}

/** Receives this screen's game tile and decodes it straight onto the screen with low-latency settings. */
class GameReceiver(private val surface: Surface, private val port: Int) {
    @Volatile private var running = true
    @Volatile private var server: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var codec: MediaCodec? = null

    fun start() {
        thread(name = "game-rx", isDaemon = true) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                server = ss
                Log.i("SpanScreen", "game receiver listening on $port")
                while (running) {
                    val s = ss.accept()
                    Log.i("SpanScreen", "game stream from ${s.inetAddress.hostAddress}")
                    socket = s
                    s.tcpNoDelay = true
                    s.receiveBufferSize = 1 shl 20
                    try { play(s) } catch (e: Exception) { Log.i("SpanScreen", "tile stream ended: $e") }
                    finally { try { s.close() } catch (_: Exception) { }; releaseCodec() }
                }
            } catch (e: Exception) {
                Log.w("SpanScreen", "receiver stopped (running=$running): $e")
            }
        }
    }

    private fun play(s: Socket) {
        val inp = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 16))
        val mime = inp.readUTF()
        val w = inp.readInt()
        val h = inp.readInt()
        val free = LinkedBlockingQueue<Int>()
        val ht = HandlerThread("tile-dec").apply { start() }
        val c = MediaCodec.createDecoderByType(mime)
        codec = c
        try {
            c.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) { free.offer(index) }
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try { codec.releaseOutputBuffer(index, true) } catch (_: Exception) { }
                }
                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) { Log.w("SpanScreen", "decoder: $e") }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}
            }, Handler(ht.looper))
            val fmt = MediaFormat.createVideoFormat(mime, w, h).apply {
                if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            }
            c.configure(fmt, surface, null, 0)
            c.start()
            var buf = ByteArray(1 shl 20)
            while (running) {
                val len = inp.readInt()
                val pts = inp.readLong()
                val flags = inp.readByte().toInt()
                if (len > buf.size) buf = ByteArray(len)
                inp.readFully(buf, 0, len)
                var idx: Int? = null
                while (running && idx == null) idx = free.poll(100, TimeUnit.MILLISECONDS)
                if (idx == null) break
                val ib = c.getInputBuffer(idx) ?: continue
                ib.clear()
                if (len > ib.capacity()) { free.offer(idx); continue }
                ib.put(buf, 0, len)
                val f = if (flags and 2 != 0) MediaCodec.BUFFER_FLAG_CODEC_CONFIG
                    else if (flags and 1 != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                c.queueInputBuffer(idx, 0, len, pts, f)
            }
        } finally {
            ht.quitSafely()
        }
    }

    private fun releaseCodec() {
        codec?.let {
            codec = null
            try { it.stop() } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
    }

    fun shutdown() {
        running = false
        try { socket?.close() } catch (_: Exception) { }
        try { server?.close() } catch (_: Exception) { }
    }
}
