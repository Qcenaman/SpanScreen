package com.qcenaman.spanscreen

import kotlin.math.max
import kotlin.math.sqrt

/** One screen taking part in the wall, in its current orientation. */
data class DeviceSpec(
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val dpi: Float,
    val refreshHz: Float,
) {
    val widthMm: Float get() = widthPx / dpi * 25.4f
    val heightMm: Float get() = heightPx / dpi * 25.4f
    val pxPerMm: Float get() = dpi / 25.4f
    val diagonalInches: Float
        get() = sqrt((widthPx.toFloat() * widthPx + heightPx.toFloat() * heightPx)) / dpi
}

/** A rectangle in normalized content coordinates (0..1 = the whole picture). */
data class Rect01(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun toArray() = floatArrayOf(left, top, right, bottom)

    companion object {
        val FULL = Rect01(0f, 0f, 1f, 1f)
        fun from(a: FloatArray, offset: Int = 0) = Rect01(a[offset], a[offset + 1], a[offset + 2], a[offset + 3])
    }
}

enum class Arrangement { ROW, COLUMN }

/** FILL covers the whole wall (crops the picture); FIT shows all of it (black bars). */
enum class FitMode { FILL, FIT }

data class Placement(val x: Float, val y: Float, val w: Float, val h: Float)

object WallLayout {

    /** Places screens in real millimetres, centred on the cross axis. Returns the screens and the wall's bounds. */
    fun place(devices: List<DeviceSpec>, arrangement: Arrangement, gapMm: Float): Pair<List<Placement>, Placement> {
        require(devices.isNotEmpty()) { "No devices" }
        val out = ArrayList<Placement>(devices.size)
        if (arrangement == Arrangement.ROW) {
            val maxH = devices.maxOf { it.heightMm }
            var x = 0f
            for (d in devices) {
                out += Placement(x, (maxH - d.heightMm) / 2f, d.widthMm, d.heightMm)
                x += d.widthMm + gapMm
            }
            return out to Placement(0f, 0f, x - gapMm, maxH)
        }
        val maxW = devices.maxOf { it.widthMm }
        var y = 0f
        for (d in devices) {
            out += Placement((maxW - d.widthMm) / 2f, y, d.widthMm, d.heightMm)
            y += d.heightMm + gapMm
        }
        return out to Placement(0f, 0f, maxW, y - gapMm)
    }

    fun canvasAspect(devices: List<DeviceSpec>, arrangement: Arrangement, gapMm: Float): Float {
        val c = place(devices, arrangement, gapMm).second
        return c.w / c.h
    }

    /** The part of the content (normalized) each screen shows, so the picture continues across them. */
    fun viewports(
        devices: List<DeviceSpec>,
        arrangement: Arrangement,
        gapMm: Float,
        contentAspect: Float,
        fit: FitMode,
    ): List<Rect01> {
        val (placed, canvas) = place(devices, arrangement, gapMm)
        val canvasAspect = canvas.w / canvas.h
        val cw: Float
        val ch: Float
        if ((contentAspect > canvasAspect) == (fit == FitMode.FILL)) {
            ch = canvas.h; cw = ch * contentAspect
        } else {
            cw = canvas.w; ch = cw / contentAspect
        }
        val cx = (canvas.w - cw) / 2f
        val cy = (canvas.h - ch) / 2f
        return placed.map {
            Rect01((it.x - cx) / cw, (it.y - cy) / ch, (it.x + it.w - cx) / cw, (it.y + it.h - cy) / ch)
        }
    }

    /** Render size for one surface covering the whole wall: as sharp as the sharpest screen, within limits. */
    fun renderSize(
        devices: List<DeviceSpec>,
        arrangement: Arrangement,
        gapMm: Float,
        maxLong: Int = 3840,
        maxPixels: Long = 3840L * 2160L,
    ): Pair<Int, Int> {
        val canvas = place(devices, arrangement, gapMm).second
        val s = devices.maxOf { it.pxPerMm }
        var w = canvas.w * s
        var h = canvas.h * s
        val long = max(w, h)
        if (long > maxLong) { val k = maxLong / long; w *= k; h *= k }
        val px = w * h
        if (px > maxPixels) { val k = sqrt(maxPixels / px); w *= k; h *= k }
        return even(w) to even(h)
    }

    /** Encoded tile size for a screen: its native size, but never sharper than its share of the render. */
    fun tileSize(device: DeviceSpec, rect: Rect01, renderW: Int, renderH: Int): Pair<Int, Int> {
        val k = minOf(1f, rect.width * renderW / device.widthPx, rect.height * renderH / device.heightPx)
        return even(device.widthPx * k) to even(device.heightPx * k)
    }

    fun even(v: Float): Int = max(2, (v / 2f).toInt() * 2)
}
