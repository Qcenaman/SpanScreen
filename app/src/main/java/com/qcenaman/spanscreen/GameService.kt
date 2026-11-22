package com.qcenaman.spanscreen

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.hardware.input.InputManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import android.view.Surface
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.lang.reflect.Method
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val TAG = "SpanScreenGame"

/**
 * Runs in a separate process with shell rights (started by Shizuku).
 * Creates a virtual display the size of the whole wall, launches the game on it,
 * cuts the picture into one tile per screen on the GPU, encodes each tile for its
 * device, and injects touches back into the game.
 */
class GameService() : IGameService.Stub() {
    private var context: Context? = null

    constructor(context: Context) : this() { this.context = context }

    private var pipeline: TilePipeline? = null
    private var display: VirtualDisplay? = null
    private var injector: TouchInjector? = null

    override fun destroy() {
        stop()
        exitProcess(0)
    }

    @Synchronized
    override fun start(
        vdWidth: Int, vdHeight: Int, dpi: Int, fps: Int, component: String?,
        local: Surface?, localRect: FloatArray?,
        hosts: Array<String>?, tileSizes: IntArray?, mimes: Array<String>?, rects: FloatArray?,
    ): String = try {
        if (Build.VERSION.SDK_INT >= 28) HiddenApiBypass.addHiddenApiExemptions("")
        stopInternal()
        val tiles = ArrayList<TilePipeline.Tile>()
        if (local != null && localRect != null) tiles += TilePipeline.Tile.Local(local, Rect01.from(localRect))
        hosts?.forEachIndexed { i, h ->
            tiles += TilePipeline.Tile.Remote(h, tileSizes!![2 * i], tileSizes[2 * i + 1], mimes!![i], Rect01.from(rects!!, 4 * i))
        }
        val p = TilePipeline(vdWidth, vdHeight, fps, tiles)
        pipeline = p
        p.start()
        val vd = VirtualDisplays.create(context, vdWidth, vdHeight, dpi, fps, p.inputSurface)
        display = vd
        val id = vd.display.displayId
        injector = TouchInjector(id, vdWidth, vdHeight)
        if (!component.isNullOrEmpty()) launch(component, id)
        ""
    } catch (t: Throwable) {
        Log.e(TAG, "start failed", t)
        stopInternal()
        "${t.javaClass.simpleName}: ${t.message}"
    }

    override fun touch(kind: Int, key: Int, u: Float, v: Float) {
        try { injector?.touch(kind, key, u, v) } catch (t: Throwable) { Log.w(TAG, "inject: $t") }
    }

    @Synchronized
    override fun stop() = stopInternal()

    private fun stopInternal() {
        injector = null
        try { display?.release() } catch (_: Throwable) { }
        display = null
        try { pipeline?.release() } catch (_: Throwable) { }
        pipeline = null
    }

    private fun launch(component: String, displayId: Int) {
        // NEW_TASK | CLEAR_TASK so the game moves onto the wall display even if it was already open.
        val p = ProcessBuilder("am", "start", "--display", displayId.toString(), "-f", "0x10008000", "-n", component)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        Log.i(TAG, "am start: $out")
        if (out.contains("Error")) throw IllegalStateException(out.trim())
    }
}

/** Context that identifies as the shell package, needed to create a trusted virtual display. */
class ShellContext(base: Context) : ContextWrapper(base) {
    override fun getPackageName(): String = "com.android.shell"
    override fun getOpPackageName(): String = "com.android.shell"
    override fun getApplicationContext(): Context = this

    @SuppressLint("NewApi")
    override fun getAttributionSource(): AttributionSource =
        AttributionSource.Builder(2000).setPackageName("com.android.shell").build()
}

object VirtualDisplays {
    private const val PUBLIC = 1
    private const val OWN_CONTENT_ONLY = 8
    private const val SUPPORTS_TOUCH = 64
    private const val DESTROY_CONTENT_ON_REMOVAL = 256
    private const val TRUSTED = 1024
    private const val OWN_DISPLAY_GROUP = 2048
    private const val ALWAYS_UNLOCKED = 4096
    private const val TOUCH_FEEDBACK_DISABLED = 8192

    fun create(base: Context?, w: Int, h: Int, dpi: Int, fps: Int, surface: Surface): VirtualDisplay {
        val ctx = ShellContext(base ?: systemContext())
        val dm = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(ctx)
        var flags = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or DESTROY_CONTENT_ON_REMOVAL
        if (Build.VERSION.SDK_INT >= 33) flags = flags or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val cfg = VirtualDisplayConfig.Builder("SpanScreen", w, h, dpi)
                    .setFlags(flags).setSurface(surface).setRequestedRefreshRate(fps.toFloat()).build()
                val m = DisplayManager::class.java.getMethod("createVirtualDisplay", VirtualDisplayConfig::class.java)
                return m.invoke(dm, cfg) as VirtualDisplay
            } catch (t: Throwable) {
                Log.w(TAG, "config-based virtual display failed, falling back: $t")
            }
        }
        return dm.createVirtualDisplay("SpanScreen", w, h, dpi, surface, flags)
            ?: throw IllegalStateException("Virtual display was refused")
    }

    @SuppressLint("PrivateApi")
    private fun systemContext(): Context {
        if (Looper.myLooper() == null) Looper.prepare()
        val at = Class.forName("android.app.ActivityThread")
        val sys = at.getMethod("systemMain").invoke(null)
        return at.getMethod("getSystemContext").invoke(sys) as Context
    }
}

class TouchInjector(private val displayId: Int, private val w: Int, private val h: Int) {
    private val tracker = PointerTracker()
    private var downTime = 0L
    private val im: Any
    private val inject: Method
    private val setDisplayId: Method?

    init {
        im = try {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)!!
        } catch (_: Throwable) {
            InputManager::class.java.getMethod("getInstance").invoke(null)!!
        }
        inject = im.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        setDisplayId = try { InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) } catch (_: Throwable) { null }
    }

    @Synchronized
    fun touch(kind: Int, key: Int, u: Float, v: Float) {
        val ev = tracker.update(kind, key, u.coerceIn(0f, 1f) * w, v.coerceIn(0f, 1f) * h) ?: return
        val now = SystemClock.uptimeMillis()
        if (ev.newGesture) downTime = now
        val n = ev.pointers.size
        val props = Array(n) { i ->
            MotionEvent.PointerProperties().apply { id = ev.pointers[i].id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }
        val coords = Array(n) { i ->
            MotionEvent.PointerCoords().apply { x = ev.pointers[i].x; y = ev.pointers[i].y; pressure = 1f; size = 1f }
        }
        val me = MotionEvent.obtain(downTime, now, ev.action, n, props, coords, 0, 0, 1f, 1f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            setDisplayId?.invoke(me, displayId)
            inject.invoke(im, me, 0)
        } finally {
            me.recycle()
        }
    }
}

private class EglCore {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val config: EGLConfig
    val context: EGLContext
    val pbuffer: EGLSurface

    init {
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize failed" }
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        check(EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, num, 0) && num[0] > 0) { "no EGL config" }
        config = configs[0]!!
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        makeCurrent(pbuffer)
    }

    fun window(surface: Surface): EGLSurface =
        EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)

    fun makeCurrent(s: EGLSurface) = check(EGL14.eglMakeCurrent(display, s, s, context)) { "eglMakeCurrent failed" }

    fun size(s: EGLSurface): Pair<Int, Int> {
        val w = IntArray(1); val h = IntArray(1)
        EGL14.eglQuerySurface(display, s, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(display, s, EGL14.EGL_HEIGHT, h, 0)
        return w[0] to h[0]
    }

    fun release(surfaces: List<EGLSurface>) {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        surfaces.forEach { EGL14.eglDestroySurface(display, it) }
        EGL14.eglDestroySurface(display, pbuffer)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}

/** Receives the wall-sized picture and draws each screen's piece into its own output. */
class TilePipeline(private val width: Int, private val height: Int, private val fps: Int, private val tiles: List<Tile>) {
    sealed class Tile(val rect: Rect01) {
        class Local(val surface: Surface, rect: Rect01) : Tile(rect)
        class Remote(val host: String, val w: Int, val h: Int, val mime: String, rect: Rect01) : Tile(rect)
    }

    private class Output(val surface: EGLSurface, val w: Int, val h: Int, val tex: FloatBuffer, val encoder: TileEncoder?) {
        @Volatile var dead = false
    }

    private val thread = HandlerThread("tile-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private lateinit var egl: EglCore
    private lateinit var texture: SurfaceTexture
    lateinit var inputSurface: Surface
        private set
    private val outputs = ArrayList<Output>()
    private var program = 0
    private var texId = 0
    private val matrix = FloatArray(16)
    private val quad = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))

    fun start() {
        val done = CountDownLatch(1)
        var error: Throwable? = null
        handler.post {
            try { setup() } catch (t: Throwable) { error = t }
            done.countDown()
        }
        done.await()
        error?.let { throw it }
        outputs.forEach { it.encoder?.start() }
    }

    private fun setup() {
        egl = EglCore()
        for (t in tiles) {
            when (t) {
                is Tile.Local -> {
                    val s = egl.window(t.surface)
                    egl.makeCurrent(s)
                    EGL14.eglSwapInterval(egl.display, 0)
                    val (w, h) = egl.size(s)
                    outputs += Output(s, w, h, texCoords(t.rect), null)
                }
                is Tile.Remote -> {
                    val enc = TileEncoder(t.host, VIDEO_PORT, t.w, t.h, t.mime, fps)
                    outputs += Output(egl.window(enc.inputSurface), enc.width, enc.height, texCoords(t.rect), enc)
                }
            }
        }
        egl.makeCurrent(egl.pbuffer)
        program = buildProgram()
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        texture = SurfaceTexture(texId)
        texture.setDefaultBufferSize(width, height)
        texture.setOnFrameAvailableListener({ draw() }, handler)
        inputSurface = Surface(texture)
    }

    private fun draw() {
        try {
            egl.makeCurrent(egl.pbuffer)
            texture.updateTexImage()
        } catch (t: Throwable) { Log.w(TAG, "updateTexImage: $t"); return }
        texture.getTransformMatrix(matrix)
        val ts = texture.timestamp
        for (o in outputs) {
            if (o.dead) continue
            try {
                egl.makeCurrent(o.surface)
                GLES20.glViewport(0, 0, o.w, o.h)
                GLES20.glUseProgram(program)
                val aPos = GLES20.glGetAttribLocation(program, "aPos")
                val aTex = GLES20.glGetAttribLocation(program, "aTex")
                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
                GLES20.glEnableVertexAttribArray(aTex)
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, o.tex)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTex"), 1, false, matrix, 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                EGLExt.eglPresentationTimeANDROID(egl.display, o.surface, ts)
                if (!EGL14.eglSwapBuffers(egl.display, o.surface)) o.dead = true
            } catch (t: Throwable) {
                Log.w(TAG, "tile output failed: $t")
                o.dead = true
            }
        }
    }

    fun release() {
        outputs.forEach { it.encoder?.stop() }
        val done = CountDownLatch(1)
        handler.post {
            try {
                texture.setOnFrameAvailableListener(null)
                inputSurface.release()
                texture.release()
                egl.release(outputs.map { it.surface })
            } catch (_: Throwable) { }
            outputs.forEach { it.encoder?.releaseCodec() }
            done.countDown()
        }
        done.await(3, TimeUnit.SECONDS)
        thread.quitSafely()
    }

    private fun texCoords(r: Rect01): FloatBuffer {
        // Corners in strip order: bottom-left, bottom-right, top-left, top-right. Image t runs bottom-up.
        return floatBuffer(floatArrayOf(r.left, 1f - r.bottom, r.right, 1f - r.bottom, r.left, 1f - r.top, r.right, 1f - r.top))
    }

    private fun buildProgram(): Int {
        val vs = """
            attribute vec4 aPos;
            attribute vec4 aTex;
            uniform mat4 uTex;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = (uTex * aTex).xy; }
        """.trimIndent()
        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTex;
            uniform samplerExternalOES sTex;
            void main() { gl_FragColor = texture2D(sTex, vTex); }
        """.trimIndent()
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    private fun floatBuffer(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(a); position(0) }
}

/** Hardware-encodes one tile and streams it to its device, dropping frames rather than building lag. */
class TileEncoder(private val host: String, private val port: Int, w0: Int, h0: Int, mime0: String, private val fps: Int) {
    private class Packet(val data: ByteArray, val pts: Long, val flags: Int)

    val mime: String
    val width: Int
    val height: Int
    private val codec: MediaCodec
    val inputSurface: Surface
    private val queue = LinkedBlockingDeque<Packet>()
    @Volatile private var running = true
    @Volatile private var config: Packet? = null

    init {
        val (info, m) = pickEncoder(mime0)
        mime = m
        val caps = info.getCapabilitiesForType(m).videoCapabilities
        var w = w0; var h = h0
        fun align(v: Int, a: Int) = maxOf(a, v / a * a)
        w = align(w, caps.widthAlignment); h = align(h, caps.heightAlignment)
        var tries = 0
        while (!caps.isSizeSupported(w, h) && tries++ < 30) {
            w = align((w * 0.9f).toInt(), caps.widthAlignment)
            h = align((h * 0.9f).toInt(), caps.heightAlignment)
        }
        width = w; height = h
        val bitrate = (w.toLong() * h * fps / 8).coerceIn(8_000_000L, 60_000_000L).toInt()
        val format = MediaFormat.createVideoFormat(m, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, caps.bitrateRange.clamp(bitrate))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger("latency", 1)
            setInteger("max-bframes", 0)
        }
        codec = MediaCodec.createByCodecName(info.name)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        Log.i(TAG, "tile $host ${w}x$h $m ${bitrate / 1_000_000}Mbps via ${info.name}")
    }

    private fun pickEncoder(preferred: String): Pair<MediaCodecInfo, String> {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }
        for (m in listOf(preferred, "video/avc").distinct()) {
            val found = all.filter { i -> i.supportedTypes.any { it.equals(m, true) } }
                .sortedByDescending { it.isHardwareAccelerated }
                .firstOrNull()
            if (found != null) return found to m
        }
        throw IllegalStateException("No video encoder")
    }

    fun start() {
        codec.start()
        thread(name = "enc-drain-$host", isDaemon = true) { drain() }
        thread(name = "enc-send-$host", isDaemon = true) { send() }
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val idx = try { codec.dequeueOutputBuffer(info, 50_000) } catch (_: Throwable) { break }
            if (idx < 0) continue
            val buf = codec.getOutputBuffer(idx)
            if (buf != null && info.size > 0) {
                buf.position(info.offset); buf.limit(info.offset + info.size)
                val data = ByteArray(info.size).also { buf.get(it) }
                var flags = 0
                if (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) flags = flags or 1
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) flags = flags or 2
                val p = Packet(data, info.presentationTimeUs, flags)
                if (flags and 2 != 0) config = p else queue.offer(p)
            }
            try { codec.releaseOutputBuffer(idx, false) } catch (_: Throwable) { break }
        }
    }

    private fun send() {
        var socket: Socket? = null
        var lastError: Exception? = null
        var attempts = 0
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (running && socket == null && SystemClock.elapsedRealtime() < deadline) {
            try {
                socket = Socket().apply {
                    tcpNoDelay = true
                    sendBufferSize = 512 * 1024
                    connect(InetSocketAddress(host, port), 2000)
                }
            } catch (e: Exception) {
                if (lastError == null) Log.w(TAG, "connect $host:$port failed: $e")
                lastError = e
                attempts++
                socket = null
                Thread.sleep(300)
            }
        }
        if (socket == null) { Log.w(TAG, "couldn't reach $host:$port after $attempts tries (running=$running): $lastError"); return }
        Log.i(TAG, "streaming to $host:$port")
        try {
            val out = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 1 shl 16))
            out.writeUTF(mime); out.writeInt(width); out.writeInt(height); out.flush()
            while (running && config == null) Thread.sleep(5)
            config?.let { write(out, it) }
            queue.clear()
            requestKeyFrame()
            var waitingForKey = true
            while (running) {
                val p = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                val key = p.flags and 1 != 0
                if (waitingForKey) {
                    if (!key) continue
                    waitingForKey = false
                } else if (!key && queue.size > 2) {
                    // Falling behind: skip to the next key frame instead of piling up lag.
                    waitingForKey = true
                    queue.clear()
                    requestKeyFrame()
                    continue
                }
                write(out, p)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stream to $host ended: $e")
        } finally {
            try { socket.close() } catch (_: Exception) { }
        }
    }

    private fun write(out: DataOutputStream, p: Packet) {
        out.writeInt(p.data.size); out.writeLong(p.pts); out.writeByte(p.flags); out.write(p.data); out.flush()
    }

    private fun requestKeyFrame() {
        try { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) } catch (_: Throwable) { }
    }

    fun stop() { running = false }

    fun releaseCodec() {
        running = false
        try { codec.stop() } catch (_: Throwable) { }
        try { codec.release() } catch (_: Throwable) { }
        try { inputSurface.release() } catch (_: Throwable) { }
    }
}
