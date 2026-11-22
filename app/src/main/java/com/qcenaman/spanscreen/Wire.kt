package com.qcenaman.spanscreen

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

const val CONTROL_PORT = 47810
const val VIDEO_PORT = 47811

/** Control messages between the host and the devices that joined it. */
sealed class Msg {
    data class Hello(val spec: DeviceSpec, val hevc: Boolean) : Msg()
    data class Ping(val t0: Long) : Msg()
    data class Pong(val t0: Long, val hostTime: Long) : Msg()
    class Image(val bytes: ByteArray, val rect: Rect01) : Msg()
    /** Followed on the wire by exactly [length] raw bytes of the video file. */
    data class VideoLoad(val length: Long) : Msg()
    data class Ready(val what: Int) : Msg()
    data class VideoPlay(val rect: Rect01, val startHostTime: Long) : Msg()
    data class Game(val rect: Rect01, val port: Int) : Msg()
    data class Touch(val kind: Int, val pointer: Int, val x: Float, val y: Float) : Msg()
    object Stop : Msg()

    fun write(out: DataOutputStream) {
        when (this) {
            is Hello -> {
                out.writeByte(1); out.writeUTF(spec.name); out.writeInt(spec.widthPx); out.writeInt(spec.heightPx)
                out.writeFloat(spec.dpi); out.writeFloat(spec.refreshHz); out.writeBoolean(hevc)
            }
            is Ping -> { out.writeByte(2); out.writeLong(t0) }
            is Pong -> { out.writeByte(3); out.writeLong(t0); out.writeLong(hostTime) }
            is Image -> { out.writeByte(4); writeRect(out, rect); out.writeInt(bytes.size); out.write(bytes) }
            is VideoLoad -> { out.writeByte(5); out.writeLong(length) }
            is Ready -> { out.writeByte(6); out.writeInt(what) }
            is VideoPlay -> { out.writeByte(7); writeRect(out, rect); out.writeLong(startHostTime) }
            is Game -> { out.writeByte(8); writeRect(out, rect); out.writeInt(port) }
            is Touch -> { out.writeByte(9); out.writeByte(kind); out.writeInt(pointer); out.writeFloat(x); out.writeFloat(y) }
            Stop -> out.writeByte(10)
        }
    }

    companion object {
        const val MAX_IMAGE_BYTES = 256 * 1024 * 1024

        fun read(inp: DataInputStream): Msg = when (val type = inp.readUnsignedByte()) {
            1 -> Hello(
                DeviceSpec(inp.readUTF(), inp.readInt(), inp.readInt(), inp.readFloat(), inp.readFloat()),
                inp.readBoolean(),
            )
            2 -> Ping(inp.readLong())
            3 -> Pong(inp.readLong(), inp.readLong())
            4 -> {
                val rect = readRect(inp)
                val n = inp.readInt()
                if (n < 0 || n > MAX_IMAGE_BYTES) throw IOException("Bad image size $n")
                Image(ByteArray(n).also { inp.readFully(it) }, rect)
            }
            5 -> VideoLoad(inp.readLong())
            6 -> Ready(inp.readInt())
            7 -> VideoPlay(readRect(inp), inp.readLong())
            8 -> Game(readRect(inp), inp.readInt())
            9 -> Touch(inp.readUnsignedByte(), inp.readInt(), inp.readFloat(), inp.readFloat())
            10 -> Stop
            else -> throw IOException("Unknown message $type")
        }

        private fun writeRect(out: DataOutputStream, r: Rect01) {
            out.writeFloat(r.left); out.writeFloat(r.top); out.writeFloat(r.right); out.writeFloat(r.bottom)
        }

        private fun readRect(inp: DataInputStream) = Rect01(inp.readFloat(), inp.readFloat(), inp.readFloat(), inp.readFloat())
    }
}

/** Estimates the host clock from ping round trips, trusting the fastest ones. */
class ClockSync(private val keep: Int = 16) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    @Synchronized
    fun add(t0: Long, hostTime: Long, t1: Long) {
        val rtt = t1 - t0
        if (rtt < 0) return
        samples.addLast(rtt to (hostTime - (t0 + t1) / 2))
        while (samples.size > keep) samples.removeFirst()
    }

    /** hostTime ≈ localTime + offset */
    val offset: Long @Synchronized get() = samples.minByOrNull { it.first }?.second ?: 0L
    val bestRtt: Long @Synchronized get() = samples.minOfOrNull { it.first } ?: -1L
}

/**
 * Turns per-finger events from several screens into Android multi-touch events
 * for one display (pointer ids 0..31, actions in MotionEvent encoding).
 */
class PointerTracker {
    data class Pointer(val id: Int, var x: Float, var y: Float)
    data class Event(val action: Int, val pointers: List<Pointer>, val newGesture: Boolean)

    private val keys = HashMap<Int, Int>()
    private val active = ArrayList<Pointer>()

    @Synchronized
    fun update(kind: Int, key: Int, x: Float, y: Float): Event? {
        when (kind) {
            DOWN -> {
                if (keys.containsKey(key)) return update(MOVE, key, x, y)
                val id = (0..31).firstOrNull { c -> active.none { it.id == c } } ?: return null
                keys[key] = id
                active += Pointer(id, x, y)
                return if (active.size == 1) Event(ACTION_DOWN, snapshot(), true)
                else Event(ACTION_POINTER_DOWN or ((active.size - 1) shl SHIFT), snapshot(), false)
            }
            MOVE -> {
                val id = keys[key] ?: return null
                active.first { it.id == id }.apply { this.x = x; this.y = y }
                return Event(ACTION_MOVE, snapshot(), false)
            }
            UP, CANCEL -> {
                val id = keys.remove(key) ?: return null
                val idx = active.indexOfFirst { it.id == id }
                active[idx].x = x; active[idx].y = y
                val snap = snapshot()
                active.removeAt(idx)
                return if (snap.size == 1) Event(if (kind == CANCEL) ACTION_CANCEL else ACTION_UP, snap, false)
                else Event(ACTION_POINTER_UP or (idx shl SHIFT), snap, false)
            }
        }
        return null
    }

    @Synchronized
    fun clear() { keys.clear(); active.clear() }

    private fun snapshot() = active.map { it.copy() }

    companion object {
        // Simplified kinds sent over the wire
        const val DOWN = 0
        const val UP = 1
        const val MOVE = 2
        const val CANCEL = 3

        // MotionEvent encodings
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val ACTION_CANCEL = 3
        const val ACTION_POINTER_DOWN = 5
        const val ACTION_POINTER_UP = 6
        const val SHIFT = 8
    }
}
