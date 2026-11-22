package com.qcenaman.spanscreen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WallLayoutTest {
    private val phone = DeviceSpec("a", 1080, 2400, 400f, 120f)
    private val small = DeviceSpec("b", 1080, 2000, 400f, 60f)
    private val eps = 1e-4f

    @Test fun twoEqualPhonesSplitInHalf() {
        val devs = listOf(phone, phone)
        val r = WallLayout.viewports(devs, Arrangement.ROW, 0f, WallLayout.canvasAspect(devs, Arrangement.ROW, 0f), FitMode.FILL)
        assertEquals(0f, r[0].left, eps); assertEquals(0.5f, r[0].right, eps)
        assertEquals(0.5f, r[1].left, eps); assertEquals(1f, r[1].right, eps)
        assertEquals(0f, r[0].top, eps); assertEquals(1f, r[0].bottom, eps)
    }

    @Test fun gapHidesContentBehindBezels() {
        val devs = listOf(phone, phone)
        val r = WallLayout.viewports(devs, Arrangement.ROW, 10f, WallLayout.canvasAspect(devs, Arrangement.ROW, 10f), FitMode.FILL)
        assertTrue(r[1].left > r[0].right)
        assertEquals(1f, r[1].right, eps)
    }

    @Test fun shorterScreenIsCentredVertically() {
        val devs = listOf(phone, small)
        val r = WallLayout.viewports(devs, Arrangement.ROW, 0f, WallLayout.canvasAspect(devs, Arrangement.ROW, 0f), FitMode.FILL)
        assertEquals(0f, r[0].top, eps)
        assertEquals(1f - r[1].bottom, r[1].top, eps)
        assertTrue(r[1].top > 0f)
    }

    @Test fun fillCropsWideContentAtTheSides() {
        val devs = listOf(phone, phone)
        val r = WallLayout.viewports(devs, Arrangement.ROW, 0f, 16f / 9f, FitMode.FILL)
        assertTrue(r[0].left > 0f)
        assertEquals(0f, r[0].top, eps)
        assertEquals(1f, r[0].bottom, eps)
    }

    @Test fun fitKeepsWholeContentVisible() {
        val devs = listOf(phone, phone, phone)
        val r = WallLayout.viewports(devs, Arrangement.ROW, 0f, 3f, FitMode.FIT)
        assertTrue(r.minOf { it.left } <= 0f + eps)
        assertTrue(r.maxOf { it.right } >= 1f - eps)
        assertTrue(r[0].top < 0f)
    }

    @Test fun columnStacksTopToBottom() {
        val devs = listOf(phone, phone)
        val r = WallLayout.viewports(devs, Arrangement.COLUMN, 0f, WallLayout.canvasAspect(devs, Arrangement.COLUMN, 0f), FitMode.FILL)
        assertEquals(0.5f, r[0].bottom, eps)
        assertEquals(0.5f, r[1].top, eps)
    }

    @Test fun renderSizeIsEvenAndCapped() {
        val devs = List(4) { phone }
        val (w, h) = WallLayout.renderSize(devs, Arrangement.ROW, 4f)
        assertTrue(w <= 3840 && h <= 3840)
        assertTrue(w.toLong() * h <= 3840L * 2160L)
        assertEquals(0, w % 2); assertEquals(0, h % 2)
    }

    @Test fun tileNeverSharperThanItsShare() {
        val (w, h) = WallLayout.tileSize(phone, Rect01(0f, 0f, 0.25f, 1f), 2000, 2000)
        assertEquals(500, w)
        assertTrue(h < 2400)
    }
}
