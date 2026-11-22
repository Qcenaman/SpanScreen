package com.qcenaman.spanscreen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class WireTest {
    private fun roundTrip(m: Msg): Msg {
        val bos = ByteArrayOutputStream()
        m.write(DataOutputStream(bos))
        return Msg.read(DataInputStream(ByteArrayInputStream(bos.toByteArray())))
    }

    @Test fun messagesSurviveTheWire() {
        val spec = DeviceSpec("Fold", 2184, 1968, 374f, 120f)
        assertEquals(Msg.Hello(spec, true), roundTrip(Msg.Hello(spec, true)))
        assertEquals(Msg.Pong(5, 9), roundTrip(Msg.Pong(5, 9)))
        assertEquals(Msg.VideoPlay(Rect01(0f, 0.1f, 0.5f, 0.9f), 1234L), roundTrip(Msg.VideoPlay(Rect01(0f, 0.1f, 0.5f, 0.9f), 1234L)))
        assertEquals(Msg.Game(Rect01.FULL, VIDEO_PORT), roundTrip(Msg.Game(Rect01.FULL, VIDEO_PORT)))
        assertEquals(Msg.Touch(2, 3, 0.25f, 0.75f), roundTrip(Msg.Touch(2, 3, 0.25f, 0.75f)))
        assertEquals(Msg.Stop, roundTrip(Msg.Stop))
        val img = roundTrip(Msg.Image(byteArrayOf(1, 2, 3), Rect01.FULL)) as Msg.Image
        assertArrayEquals(byteArrayOf(1, 2, 3), img.bytes)
    }

    @Test fun clockSyncTrustsFastestRoundTrip() {
        val c = ClockSync()
        c.add(t0 = 0, hostTime = 1050, t1 = 100)   // slow: offset 1000
        c.add(t0 = 200, hostTime = 1205, t1 = 210) // fast: offset 1000
        c.add(t0 = 300, hostTime = 2000, t1 = 900) // very slow, skewed
        assertEquals(1000L, c.offset)
        assertEquals(10L, c.bestRtt)
    }

    @Test fun pointerTrackerBuildsMultiTouch() {
        val t = PointerTracker()
        val d = t.update(PointerTracker.DOWN, 101, 1f, 1f)!!
        assertEquals(PointerTracker.ACTION_DOWN, d.action)
        assertTrue(d.newGesture)
        val d2 = t.update(PointerTracker.DOWN, 201, 5f, 5f)!!
        assertEquals(PointerTracker.ACTION_POINTER_DOWN or (1 shl 8), d2.action)
        assertEquals(2, d2.pointers.size)
        val mv = t.update(PointerTracker.MOVE, 201, 6f, 6f)!!
        assertEquals(6f, mv.pointers[1].x)
        val u1 = t.update(PointerTracker.UP, 101, 1f, 1f)!!
        assertEquals(PointerTracker.ACTION_POINTER_UP, u1.action)
        val u2 = t.update(PointerTracker.UP, 201, 6f, 6f)!!
        assertEquals(PointerTracker.ACTION_UP, u2.action)
        assertNull(t.update(PointerTracker.MOVE, 201, 0f, 0f))
    }
}
