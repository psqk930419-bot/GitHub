package com.kimwonyup.ergoangle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetPoseSelectorTest {
    private fun f(index: Int, x: Float, y: Float = 0.50f, torso: Float = 0.28f, width: Float = 0.20f, valid: Boolean = true) =
        PoseTargetFeature(index, x, y, torso, width, valid)

    @Test
    fun calibrationLocksCentralContinuousPersonWhenAnotherPersonAppears() {
        val s = TargetPoseSelector()
        s.beginCalibration()
        repeat(8) { i ->
            val user = f(index = if (i % 2 == 0) 0 else 1, x = 0.49f + i * 0.002f, torso = 0.30f)
            val passer = f(index = if (i % 2 == 0) 1 else 0, x = 0.80f - i * 0.01f, torso = 0.25f)
            assertEquals(user.index, s.select(listOf(user, passer), i * 200L))
        }
        assertTrue(s.lockCalibration(1_600L))
    }

    @Test
    fun lockedTargetSurvivesPoseOrderingSwap() {
        val s = TargetPoseSelector()
        s.beginCalibration()
        repeat(6) { i -> s.select(listOf(f(0, 0.50f + i * 0.002f, torso = 0.30f)), i * 200L) }
        assertTrue(s.lockCalibration(1_200L))

        val other = f(0, 0.78f, torso = 0.24f)
        val user = f(1, 0.53f, torso = 0.29f)
        assertEquals(1, s.select(listOf(other, user), 1_400L))

        val userNowFirst = f(0, 0.55f, torso = 0.29f)
        val otherNowSecond = f(1, 0.75f, torso = 0.24f)
        assertEquals(0, s.select(listOf(userNowFirst, otherNowSecond), 1_600L))
    }

    @Test
    fun doesNotSwitchToFarBystanderWhenTargetDisappears() {
        val s = TargetPoseSelector()
        s.beginCalibration()
        repeat(6) { i -> s.select(listOf(f(0, 0.50f, torso = 0.30f)), i * 200L) }
        assertTrue(s.lockCalibration(1_200L))
        assertEquals(0, s.select(listOf(f(0, 0.54f, torso = 0.29f)), 1_400L))

        val farBystander = f(0, 0.91f, y = 0.35f, torso = 0.18f, width = 0.35f)
        assertNull(s.select(listOf(farBystander), 1_600L))
        assertNull(s.select(listOf(farBystander), 5_000L))
    }

    @Test
    fun reacquiresUserNearCalibratedSeatAfterLongAbsence() {
        val s = TargetPoseSelector()
        s.beginCalibration()
        repeat(6) { i -> s.select(listOf(f(0, 0.48f, torso = 0.31f)), i * 200L) }
        assertTrue(s.lockCalibration(1_200L))

        assertNull(s.select(emptyList(), 5_000L))
        val returningUser = f(2, 0.51f, torso = 0.30f)
        assertEquals(2, s.select(listOf(returningUser), 5_200L))
    }
}
