package com.kimwonyup.ergoangle

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

data class PoseTargetFeature(
    val index: Int,
    val centerX: Float,
    val centerY: Float,
    val torsoLength: Float,
    val widthRatio: Float,
    val valid: Boolean = true
)

/**
 * Locks desk coaching to the person used during the 5-second calibration.
 * This is screen-space continuity matching, not biometric identification.
 */
class TargetPoseSelector(
    private val maxLockedScore: Float = 1.55f,
    private val lostResetMs: Long = 3_000L
) {
    private var calibrationActive = false
    private var provisional: PoseTargetFeature? = null
    private val calibrationSamples = mutableListOf<PoseTargetFeature>()
    private var lockedCenterX: Float? = null
    private var lockedCenterY: Float? = null
    private var lockedTorso: Float? = null
    private var lockedWidthRatio: Float? = null
    private var lastCenterX: Float? = null
    private var lastCenterY: Float? = null
    private var lastTorso: Float? = null
    private var lastSeenMs: Long = 0L

    fun reset() {
        calibrationActive = false
        provisional = null
        calibrationSamples.clear()
        lockedCenterX = null
        lockedCenterY = null
        lockedTorso = null
        lockedWidthRatio = null
        lastCenterX = null
        lastCenterY = null
        lastTorso = null
        lastSeenMs = 0L
    }

    fun beginCalibration() {
        reset()
        calibrationActive = true
    }

    fun isLocked(): Boolean = lockedTorso != null

    /** Returns the pose list index selected for this frame, or null if the locked target is absent. */
    fun select(features: List<PoseTargetFeature>, nowMs: Long): Int? {
        val candidates = features.filter { it.valid && it.torsoLength >= 0.08f }
        if (candidates.isEmpty()) return null

        if (!isLocked()) {
            if (!calibrationActive) {
                // Before calibration, show a stable preview candidate but do not create identity state.
                return candidates.minByOrNull { initialCalibrationScore(it) }?.index
            }
            val previous = provisional
            val chosen = if (previous == null) {
                candidates.minByOrNull { initialCalibrationScore(it) }
            } else {
                candidates.minByOrNull { continuityScore(it, previous) }
            } ?: return null
            provisional = chosen
            calibrationSamples += chosen
            lastCenterX = chosen.centerX
            lastCenterY = chosen.centerY
            lastTorso = chosen.torsoLength
            lastSeenMs = nowMs
            return chosen.index
        }

        val lx = lastCenterX ?: lockedCenterX ?: return null
        val ly = lastCenterY ?: lockedCenterY ?: return null
        val lt = lastTorso ?: lockedTorso ?: return null
        val lockX = lockedCenterX ?: lx
        val lockY = lockedCenterY ?: ly
        val lockTorso = lockedTorso ?: lt
        val lockWidth = lockedWidthRatio ?: 0f
        val best = candidates.minByOrNull {
            lockedScore(it, lx, ly, lt, lockX, lockY, lockTorso, lockWidth, nowMs)
        } ?: return null
        val score = lockedScore(best, lx, ly, lt, lockX, lockY, lockTorso, lockWidth, nowMs)
        if (score > maxLockedScore) return null

        lastCenterX = best.centerX
        lastCenterY = best.centerY
        lastTorso = best.torsoLength
        lastSeenMs = nowMs
        return best.index
    }

    fun lockCalibration(nowMs: Long): Boolean {
        if (calibrationSamples.size < 5) return false
        lockedCenterX = median(calibrationSamples.map { it.centerX })
        lockedCenterY = median(calibrationSamples.map { it.centerY })
        lockedTorso = median(calibrationSamples.map { it.torsoLength })
        lockedWidthRatio = median(calibrationSamples.map { it.widthRatio })
        lastCenterX = provisional?.centerX ?: lockedCenterX
        lastCenterY = provisional?.centerY ?: lockedCenterY
        lastTorso = provisional?.torsoLength ?: lockedTorso
        lastSeenMs = nowMs
        calibrationActive = false
        calibrationSamples.clear()
        return true
    }

    private fun initialCalibrationScore(c: PoseTargetFeature): Float {
        val centerDist = distance(c.centerX, c.centerY, 0.5f, 0.52f)
        return centerDist * 2.2f - c.torsoLength.coerceAtMost(0.6f) * 0.55f
    }

    private fun continuityScore(c: PoseTargetFeature, p: PoseTargetFeature): Float {
        val move = distance(c.centerX, c.centerY, p.centerX, p.centerY)
        val size = logRatio(c.torsoLength, p.torsoLength)
        val width = abs(c.widthRatio - p.widthRatio)
        return move / 0.20f + size * 0.65f + width * 0.25f
    }

    private fun lockedScore(c: PoseTargetFeature, lastX: Float, lastY: Float, lastTorsoValue: Float, lockX: Float, lockY: Float, lockTorsoValue: Float, lockWidth: Float, nowMs: Long): Float {
        val recentlySeen = lastSeenMs > 0L && nowMs - lastSeenMs <= lostResetMs
        val refX = if (recentlySeen) lastX else lockX
        val refY = if (recentlySeen) lastY else lockY
        val refTorso = if (recentlySeen) lastTorsoValue else lockTorsoValue
        val move = distance(c.centerX, c.centerY, refX, refY)
        val size = logRatio(c.torsoLength, refTorso)
        val home = distance(c.centerX, c.centerY, lockX, lockY)
        val width = abs(c.widthRatio - lockWidth)
        return move / 0.24f + size * 0.75f + home / 0.70f * 0.25f + width * 0.20f
    }

    private fun logRatio(a: Float, b: Float): Float {
        if (a <= 1e-5f || b <= 1e-5f) return 10f
        return abs(ln((a / b).toDouble())).toFloat()
    }

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }
}
