package com.kimwonyup.ergoangle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PersonalPostureEngineTest {

    @Test
    fun reclinedNeutralIsNotBadAgainstOwnBaseline() {
        val baseline = PersonalBaseline(headForwardRatio = 0.04f, trunkForwardDeg = -24f)
        val raw = RawDeskMetrics(headForwardRatio = 0.04f, trunkForwardDeg = -24f, trunkAbsoluteDeg = 24f)
        val reading = PersonalPostureEngine.evaluate(raw, baseline)

        assertFalse(reading.bad)
        assertEquals(0f, reading.trunkDeltaDeg, 0.001f)
    }

    @Test
    fun movingFromReclinedToForwardIsDetected() {
        val baseline = PersonalBaseline(headForwardRatio = 0.04f, trunkForwardDeg = -24f)
        val raw = RawDeskMetrics(headForwardRatio = 0.10f, trunkForwardDeg = 10f, trunkAbsoluteDeg = 10f)
        val reading = PersonalPostureEngine.evaluate(raw, baseline)

        assertTrue(reading.bad)
        assertTrue(reading.severe)
        assertEquals(34f, reading.trunkDeltaDeg, 0.001f)
    }

    @Test
    fun headForwardOnlyCanTriggerCoaching() {
        val baseline = PersonalBaseline(headForwardRatio = 0.10f, trunkForwardDeg = 0f)
        val raw = RawDeskMetrics(headForwardRatio = 0.19f, trunkForwardDeg = 1f, trunkAbsoluteDeg = 1f)
        val reading = PersonalPostureEngine.evaluate(raw, baseline)

        assertTrue(reading.bad)
        assertFalse(reading.severe)
        assertEquals(9f, reading.headForwardDeltaPct.toFloat(), 1f)
    }

    @Test
    fun measurementIsMirrorInvariant() {
        val nose = Point2(0.72f, 0.22f)
        val ear = Point2(0.66f, 0.26f)
        val shoulder = Point2(0.56f, 0.46f)
        val hip = Point2(0.50f, 0.76f)

        val a = PersonalPostureEngine.measure(nose, ear, shoulder, hip)!!
        val b = PersonalPostureEngine.measure(
            Point2(1f - nose.x, nose.y),
            Point2(1f - ear.x, ear.y),
            Point2(1f - shoulder.x, shoulder.y),
            Point2(1f - hip.x, hip.y)
        )!!

        assertTrue(abs(a.headForwardRatio - b.headForwardRatio) < 0.0001f)
        assertTrue(abs(a.trunkForwardDeg - b.trunkForwardDeg) < 0.0001f)
    }
}
