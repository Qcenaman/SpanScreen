package com.qcenaman.spanscreen

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val TAG = "SpanScreen"
private val mainHandler = Handler(Looper.getMainLooper())

fun onMain(block: () -> Unit) { mainHandler.post(block) }

/** One TCP control connection. Writes are serialized; [send] never blocks the caller. */
class PeerConn(val socket: Socket) {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 1 shl 16))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 1 shl 16))
    private val sender = Executors.newSingleThreadExecutor()
    @Volatile var readyLatch: CountDownLatch? = null
    val address: String get() = socket.inetAddress.hostAddress ?: ""

    fun read(): Msg = Msg.read(input)

    fun readRawTo(file: File, length: Long) {
        file.outputStream().buffered(1 shl 16).use { out ->
            val buf = ByteArray(1 shl 16)
            var left = length
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("Connection closed during file transfer")
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    fun send(m: Msg) {
        try {
            sender.execute { sendNow(m) }
        } catch (_: Exception) { }
    }

    fun sendNow(m: Msg) {
        try {
            synchronized(output) { m.write(output); output.flush() }
        } catch (e: IOException) { close() }
    }

    /** Blocking: sends the file as a VideoLoad message. */
    fun sendFile(file: File) {
        synchronized(output) {
            Msg.VideoLoad(file.length()).write(output)
            file.inputStream().use { it.copyTo(output, 1 shl 16) }
            output.flush()
        }
    }

    fun close() {
        try { socket.close() } catch (_: Exception) { }
        sender.shutdownNow()
    }
}

/** What this device should be showing right now. */
object Show {
    sealed class Content {
        object None : Content()
        data class Image(val path: String, val rect: Rect01) : Content()
        data class Video(val path: String, val rect: Rect01, val startHostTime: Long) : Content()
        data class Game(val rect: Rect01) : Content()
    }

    @Volatile var content: Content = Content.None
    @Volatile var isHost = false
    val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** hostTime ≈ elapsedRealtime + offset */
    fun clockOffset(): Long = if (isHost) 0L else PeerSession.clock.offset

    fun set(ctx: Context, c: Content) {
        content = c
        onMain {
            listeners.forEach { it() }
            if (c !is Content.None) {
                ctx.startActivity(
                    Intent(ctx, DisplayActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
        }
    }
}

object LocalDevice {
    fun name(ctx: Context): String =
        Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    @Suppress("DEPRECATION")
    fun spec(activity: Activity): DeviceSpec {
        val display = activity.display ?: activity.windowManager.defaultDisplay
        val dm = DisplayMetrics()
        display.getRealMetrics(dm)
        var dpi = (dm.xdpi + dm.ydpi) / 2f
        if (dpi < 100f || dpi > 1000f) dpi = dm.densityDpi.toFloat()
        val mode = display.mode
        val refresh = display.supportedModes
            .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
            .maxOfOrNull { it.refreshRate } ?: display.refreshRate
        return DeviceSpec(name(activity), dm.widthPixels, dm.heightPixels, dpi, refresh)
    }

    fun hevcDecodes(w: Int, h: Int): Boolean = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", true) } &&
                info.getCapabilitiesForType("video/hevc").videoCapabilities.let {
                    it.isSizeSupported(w, h) || it.isSizeSupported(h, w)
                }
        }
    } catch (_: Exception) { false }
}

/** Host side: accepts devices, decides who shows what. */
object HostSession {
    class Member(val id: Int, @Volatile var spec: DeviceSpec, val hevc: Boolean, val conn: PeerConn?) {
        @Volatile var included = true
        val isSelf get() = conn == null
    }

    val members = CopyOnWriteArrayList<Member>()
    @Volatile var arrangement = Arrangement.ROW
    @Volatile var gapMm = 4f
    @Volatile var fit = FitMode.FILL
    var listener: (() -> Unit)? = null
    var status: ((String) -> Unit)? = null

    private lateinit var app: Context
    private var server: ServerSocket? = null
    private var nextId = 1
    @Volatile private var activeRects: Map<Int, Rect01> = emptyMap()

    val running get() = server != null

    fun start(ctx: Context, self: DeviceSpec, hevc: Boolean) {
        app = ctx.applicationContext
        Show.isHost = true
        if (server != null) { updateSelf(self); return }
        members.clear()
        members.add(Member(0, self, hevc, null))
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(CONTROL_PORT))
        server = ss
        thread(name = "host-accept", isDaemon = true) {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                s.tcpNoDelay = true
                handle(s)
            }
        }
        Discovery.register(app)
        changed()
    }

    fun shutdown() {
        stopAll()
        Discovery.unregister(app)
        try { server?.close() } catch (_: Exception) { }
        server = null
        members.forEach { it.conn?.close() }
        members.clear()
        Show.isHost = false
    }

    fun updateSelf(spec: DeviceSpec) {
        members.firstOrNull { it.isSelf }?.spec = spec
        changed()
    }

    fun move(member: Member, delta: Int) {
        val i = members.indexOf(member)
        val j = i + delta
        if (i < 0 || j < 0 || j >= members.size) return
        val other = members[j]
        members[j] = member
        members[i] = other
        changed()
    }

    fun selected() = members.filter { it.included }

    private fun changed() { onMain { listener?.invoke() } }
    private fun say(s: String) { onMain { status?.invoke(s) } }

    private fun handle(s: Socket) = thread(isDaemon = true, name = "host-peer") {
        val conn = PeerConn(s)
        var member: Member? = null
        try {
            while (true) {
                when (val m = conn.read()) {
                    is Msg.Hello -> {
                        val mm = member
                        if (mm == null) {
                            member = Member(nextId++, m.spec, m.hevc, conn).also { members.add(it) }
                        } else mm.spec = m.spec
                        changed()
                    }
                    is Msg.Ping -> conn.send(Msg.Pong(m.t0, SystemClock.elapsedRealtime()))
                    is Msg.Ready -> conn.readyLatch?.countDown()
                    is Msg.Touch -> member?.let { touch(it, m.kind, m.pointer, m.x, m.y) }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "peer left: $e")
        } finally {
            conn.close()
            member?.let { members.remove(it) }
            conn.readyLatch?.countDown()
            changed()
        }
    }

    private fun specs(sel: List<Member>) = sel.map { it.spec }

    fun showImage(source: File) = thread(name = "host-image") {
        val sel = selected()
        if (sel.isEmpty()) { say("Tick at least one device"); return@thread }
        say("Preparing picture…")
        val bytes = try { normalizeImage(source) } catch (e: Exception) { say("Can't read that picture: ${e.message}"); return@thread }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val rects = WallLayout.viewports(specs(sel), arrangement, gapMm, o.outWidth.toFloat() / o.outHeight, fit)
        stopGameOnly()
        val local = File(app.cacheDir, "host_${System.currentTimeMillis()}.jpg").apply { writeBytes(bytes) }
        sel.forEachIndexed { i, m ->
            if (m.isSelf) Show.set(app, Show.Content.Image(local.path, rects[i]))
            else m.conn!!.send(Msg.Image(bytes, rects[i]))
        }
        say("Showing picture on ${sel.size} device(s)")
    }

    /** Applies EXIF rotation and caps the size, so every device can decode its piece the same way. */
    private fun normalizeImage(source: File): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        if (bounds.outWidth <= 0) throw IOException("not an image")
        val maxSide = 6000
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bmp = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException("decode failed")
        val rot = when (ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val longSide = maxOf(bmp.width, bmp.height)
        val scale = if (longSide > maxSide) maxSide.toFloat() / longSide else 1f
        val out = if (rot != 0f || scale != 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(rot) }
            Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
        } else bmp
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 95, bos)
        out.recycle()
        return bos.toByteArray()
    }

    fun showVideo(file: File) = thread(name = "host-video") {
        val sel = selected()
        if (sel.isEmpty()) { say("Tick at least one device"); return@thread }
        val aspect = try {
            MediaMetadataRetriever().run {
                setDataSource(file.path)
                val w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toFloat()
                val h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toFloat()
                val rot = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                release()
                if (rot % 180 != 0) h / w else w / h
            }
        } catch (e: Exception) { say("Can't read that video"); return@thread }
        val rects = WallLayout.viewports(specs(sel), arrangement, gapMm, aspect, fit)
        stopGameOnly()
        val peers = sel.filter { !it.isSelf }
        val latch = CountDownLatch(peers.size)
        peers.forEach { it.conn!!.readyLatch = latch }
        if (peers.isNotEmpty()) say("Sending video to ${peers.size} device(s)…")
        peers.forEach { p -> thread(isDaemon = true) {
            try { p.conn!!.sendFile(file) } catch (e: Exception) { latch.countDown() }
        } }
        if (!latch.await(15, TimeUnit.MINUTES)) { say("Video transfer timed out"); return@thread }
        val start = SystemClock.elapsedRealtime() + 1500
        sel.forEachIndexed { i, m ->
            if (m.isSelf) Show.set(app, Show.Content.Video(file.path, rects[i], start))
            else m.conn!!.send(Msg.VideoPlay(rects[i], start))
        }
        say("Playing video on ${sel.size} device(s)")
    }

    fun startGame(component: String) = thread(name = "host-game") {
        val sel = selected()
        if (sel.isEmpty()) { say("Tick at least one device"); return@thread }
        val specs = specs(sel)
        val (vw, vh) = WallLayout.renderSize(specs, arrangement, gapMm)
        val rects = WallLayout.viewports(specs, arrangement, gapMm, vw.toFloat() / vh, FitMode.FILL)
        val canvas = WallLayout.place(specs, arrangement, gapMm).second
        val dpi = (vw / (canvas.w / 25.4f)).toInt().coerceIn(120, 640)
        val fps = specs.maxOf { it.refreshHz }.toInt().coerceIn(30, 120)
        activeRects = sel.indices.associate { sel[it].id to rects[it] }

        val tiles = ArrayList<GameController.PeerTile>()
        var localRect: Rect01? = null
        sel.forEachIndexed { i, m ->
            if (m.isSelf) localRect = rects[i]
            else {
                val (tw, th) = WallLayout.tileSize(m.spec, rects[i], vw, vh)
                tiles += GameController.PeerTile(m.conn!!.address, tw, th, if (m.hevc) "video/hevc" else "video/avc", rects[i])
                m.conn.send(Msg.Game(rects[i], VIDEO_PORT))
            }
        }
        say("Starting game: ${vw}x$vh at ${fps}Hz on ${sel.size} device(s)…")
        GameController.prepare(app, GameController.Params(vw, vh, dpi, fps, component, localRect, tiles)) { say(it) }
        if (localRect != null) Show.set(app, Show.Content.Game(localRect!!))
    }

    fun touchSelf(kind: Int, pointer: Int, x: Float, y: Float) {
        members.firstOrNull { it.isSelf }?.let { touch(it, kind, pointer, x, y) }
    }

    private fun touch(m: Member, kind: Int, pointer: Int, x: Float, y: Float) {
        val r = activeRects[m.id] ?: return
        GameController.touch(kind, m.id * 100 + pointer, r.left + x * r.width, r.top + y * r.height)
    }

    private fun stopGameOnly() {
        if (activeRects.isNotEmpty()) {
            activeRects = emptyMap()
            GameController.stop()
        }
    }

    fun stopAll() {
        members.forEach { it.conn?.send(Msg.Stop) }
        activeRects = emptyMap()
        GameController.stop()
        if (::app.isInitialized) Show.set(app, Show.Content.None)
    }
}

/** Joining side: one connection to a host. */
object PeerSession {
    @Volatile private var conn: PeerConn? = null
    val clock = ClockSync()
    var status: ((String) -> Unit)? = null
    @Volatile private var pendingVideo: String? = null
    @Volatile var hostName: String? = null

    val connected get() = conn != null

    private fun say(s: String) { onMain { status?.invoke(s) } }

    fun connect(ctx: Context, host: String, spec: DeviceSpec, hevc: Boolean) {
        val app = ctx.applicationContext
        Show.isHost = false
        disconnect()
        thread(name = "peer", isDaemon = true) {
            val c: PeerConn
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, CONTROL_PORT), 5000)
                s.tcpNoDelay = true
                c = PeerConn(s)
            } catch (e: Exception) {
                say("Couldn't reach $host: ${e.message}")
                return@thread
            }
            conn = c
            hostName = host
            c.send(Msg.Hello(spec, hevc))
            say("Connected to $host. Waiting for the host to start something.")
            thread(isDaemon = true, name = "peer-ping") {
                var n = 0
                while (conn === c) {
                    c.send(Msg.Ping(SystemClock.elapsedRealtime()))
                    Thread.sleep(if (n++ < 10) 300L else 3000L)
                }
            }
            try {
                while (true) {
                    when (val m = c.read()) {
                        is Msg.Pong -> clock.add(m.t0, m.hostTime, SystemClock.elapsedRealtime())
                        is Msg.Image -> {
                            cleanCache(app, "img_")
                            val f = File(app.cacheDir, "img_${System.currentTimeMillis()}.jpg").apply { writeBytes(m.bytes) }
                            Show.set(app, Show.Content.Image(f.path, m.rect))
                        }
                        is Msg.VideoLoad -> {
                            say("Receiving video…")
                            Show.set(app, Show.Content.None)
                            cleanCache(app, "vid_")
                            val f = File(app.cacheDir, "vid_${System.currentTimeMillis()}.mp4")
                            c.readRawTo(f, m.length)
                            pendingVideo = f.path
                            c.send(Msg.Ready(1))
                            say("Video received")
                        }
                        is Msg.VideoPlay -> pendingVideo?.let { Show.set(app, Show.Content.Video(it, m.rect, m.startHostTime)) }
                        is Msg.Game -> Show.set(app, Show.Content.Game(m.rect))
                        Msg.Stop -> Show.set(app, Show.Content.None)
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "host connection ended: $e")
            } finally {
                if (conn === c) conn = null
                c.close()
                Show.set(app, Show.Content.None)
                say("Disconnected from $host")
            }
        }
    }

    private fun cleanCache(ctx: Context, prefix: String) {
        ctx.cacheDir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
    }

    fun updateSpec(spec: DeviceSpec, hevc: Boolean) { conn?.send(Msg.Hello(spec, hevc)) }

    fun sendTouch(kind: Int, pointer: Int, x: Float, y: Float) { conn?.send(Msg.Touch(kind, pointer, x, y)) }

    fun disconnect() {
        conn?.let { conn = null; it.close() }
    }
}

/** Finds hosts on the local network (mDNS / DNS-SD). */
object Discovery {
    private const val TYPE = "_spanscreen._tcp."
    private var reg: NsdManager.RegistrationListener? = null

    fun register(ctx: Context) {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = LocalDevice.name(ctx)
            serviceType = TYPE
            port = CONTROL_PORT
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(s: NsdServiceInfo?, e: Int) { Log.w(TAG, "nsd register failed $e") }
            override fun onUnregistrationFailed(s: NsdServiceInfo?, e: Int) {}
            override fun onServiceRegistered(s: NsdServiceInfo?) {}
            override fun onServiceUnregistered(s: NsdServiceInfo?) {}
        }
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); reg = l } catch (e: Exception) { Log.w(TAG, "nsd: $e") }
    }

    fun unregister(ctx: Context) {
        val l = reg ?: return
        reg = null
        try { ctx.getSystemService(NsdManager::class.java)?.unregisterService(l) } catch (_: Exception) { }
    }

    /** Starts browsing; returns a function that stops it. */
    @Suppress("DEPRECATION")
    fun browse(ctx: Context, onFound: (String, String) -> Unit): () -> Unit {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return {}
        val wifi = ctx.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("spanscreen")?.apply { setReferenceCounted(false); acquire() }
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        lateinit var next: () -> Unit
        next = {
            synchronized(queue) {
                if (!resolving && queue.isNotEmpty()) {
                    resolving = true
                    val s = queue.removeFirst()
                    nsd.resolveService(s, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo?, e: Int) {
                            synchronized(queue) { resolving = false }; next()
                        }
                        override fun onServiceResolved(si: NsdServiceInfo) {
                            val host = si.host?.hostAddress
                            if (host != null) onMain { onFound(si.serviceName, host) }
                            synchronized(queue) { resolving = false }; next()
                        }
                    })
                }
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, e: Int) {}
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceLost(s: NsdServiceInfo?) {}
            override fun onServiceFound(s: NsdServiceInfo) {
                synchronized(queue) { queue.addLast(s) }
                next()
            }
        }
        try { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, listener) } catch (e: Exception) { Log.w(TAG, "browse: $e") }
        return {
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) { }
            try { lock?.release() } catch (_: Exception) { }
        }
    }
}
