package com.kimwonyup.ergoangle

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

data class RawDeskMetrics(
    val headForwardRatio: Float,
    val trunkForwardDeg: Float,
    val trunkAbsoluteDeg: Float
)

data class PersonalBaseline(
    val headForwardRatio: Float,
    val trunkForwardDeg: Float,
    val trunkAbsoluteDeg: Float = abs(trunkForwardDeg)
)

data class PersonalPostureReading(
    val headForwardDeltaRatio: Float,
    val trunkDeltaDeg: Float,
    val trunkForwardDeg: Float,
    val trunkAbsoluteDeg: Float,
    val headLevel: PostureLevel,
    val trunkLevel: PostureLevel,
    val overallLevel: PostureLevel,
    val bad: Boolean,
    val severe: Boolean
) {
    val headForwardDeltaPct: Int get() = (headForwardDeltaRatio * 100f).toInt().coerceAtLeast(0)
}

object PersonalPostureEngine {
    fun measure(nose: Point2, ear: Point2, shoulder: Point2, hip: Point2): RawDeskMetrics? {
        val trunk = Point2(shoulder.x - hip.x, shoulder.y - hip.y)
        val torsoLength = norm(trunk)
        if (torsoLength < 1e-5f) return null

        // The nose/ear direction identifies the user's forward direction on screen.
        // Mirroring flips both the facing direction and horizontal landmark differences,
        // so the normalized metrics remain stable for front-camera previews.
        val facingSign = if (nose.x >= ear.x) 1f else -1f
        val headForwardRatio = (((ear.x - shoulder.x) * facingSign) / torsoLength).coerceIn(-1f, 1f)

        // Signed trunk angle: positive = trunk moves forward in the direction the user faces,
        // negative = reclined/backward. This avoids treating a neutral reclined posture as
        // equivalent to forward flexion just because both have the same absolute angle.
        val forwardComponent = (shoulder.x - hip.x) * facingSign
        val verticalUpComponent = -(shoulder.y - hip.y)
        val trunkForwardDeg = Math.toDegrees(
            atan2(forwardComponent.toDouble(), verticalUpComponent.toDouble())
        ).toFloat().coerceIn(-90f, 90f)

        return RawDeskMetrics(
            headForwardRatio = headForwardRatio,
            trunkForwardDeg = trunkForwardDeg,
            trunkAbsoluteDeg = abs(trunkForwardDeg)
        )
    }

    fun evaluate(raw: RawDeskMetrics, baseline: PersonalBaseline): PersonalPostureReading {
        val headDelta = max(0f, raw.headForwardRatio - baseline.headForwardRatio)
        val trunkDelta = max(0f, raw.trunkForwardDeg - baseline.trunkForwardDeg)

        val headLevel = when {
            headDelta < 0.03f -> PostureLevel.LOW
            headDelta < 0.07f -> PostureLevel.MILD
            headDelta < 0.12f -> PostureLevel.HIGH
            else -> PostureLevel.VERY_HIGH
        }

        // In personalized desk mode, trunk exposure is based on forward change from the user's
        // own neutral baseline. Absolute angle is retained for reporting only, not as a direct
        // bad-posture trigger, because comfortable neutral sitting can be mildly reclined.
        val trunkLevel = when {
            trunkDelta < 5f -> PostureLevel.LOW
            trunkDelta < 12f -> PostureLevel.MILD
            trunkDelta < 22f -> PostureLevel.HIGH
            else -> PostureLevel.VERY_HIGH
        }
        val overall = if (headLevel.rank >= trunkLevel.rank) headLevel else trunkLevel

        // Coaching heuristics, not clinical disease-risk cutoffs.
        val bad = headDelta >= 0.07f || trunkDelta >= 12f
        val severe = headDelta >= 0.12f || trunkDelta >= 22f

        return PersonalPostureReading(
            headForwardDeltaRatio = headDelta,
            trunkDeltaDeg = trunkDelta,
            trunkForwardDeg = raw.trunkForwardDeg,
            trunkAbsoluteDeg = raw.trunkAbsoluteDeg,
            headLevel = headLevel,
            trunkLevel = trunkLevel,
            overallLevel = overall,
            bad = bad,
            severe = severe
        )
    }

    private fun norm(v: Point2): Float = sqrt(v.x * v.x + v.y * v.y)
}

data class CalibrationUpdate(
    val active: Boolean,
    val progressPct: Int,
    val baseline: PersonalBaseline? = null,
    val failed: Boolean = false
)

class BaselineCalibrator(
    private val requiredValidMs: Long = 5_000L,
    private val minSamples: Int = 20
) {
    private val headSamples = mutableListOf<Float>()
    private val trunkSamples = mutableListOf<Float>()
    private var active = false
    private var validMs = 0L
    private var lastFrameMs = 0L

    fun start(nowMs: Long) {
        headSamples.clear()
        trunkSamples.clear()
        active = true
        validMs = 0L
        lastFrameMs = nowMs
    }

    fun cancel() {
        active = false
        validMs = 0L
        headSamples.clear()
        trunkSamples.clear()
    }

    fun isActive(): Boolean = active

    fun onFrame(nowMs: Long, metrics: RawDeskMetrics?, validSideProfile: Boolean): CalibrationUpdate {
        if (!active) return CalibrationUpdate(false, 0)
        val delta = (nowMs - lastFrameMs).coerceIn(0L, 250L)
        lastFrameMs = nowMs

        if (validSideProfile && metrics != null) {
            validMs += delta
            headSamples += metrics.headForwardRatio
            trunkSamples += metrics.trunkForwardDeg
        }

        val progress = ((validMs * 100L) / requiredValidMs).toInt().coerceIn(0, 100)
        if (validMs < requiredValidMs) return CalibrationUpdate(true, progress)

        active = false
        if (headSamples.size < minSamples || trunkSamples.size < minSamples) {
            return CalibrationUpdate(false, 100, failed = true)
        }

        val trunkForward = median(trunkSamples)
        val baseline = PersonalBaseline(
            headForwardRatio = median(headSamples),
            trunkForwardDeg = trunkForward,
            trunkAbsoluteDeg = abs(trunkForward)
        )
        return CalibrationUpdate(false, 100, baseline = baseline)
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }
}
