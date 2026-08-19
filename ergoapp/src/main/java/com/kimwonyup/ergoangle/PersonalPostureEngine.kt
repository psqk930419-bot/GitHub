package com.kimwonyup.ergoangle

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

data class RawDeskMetrics(
    val headForwardRatio: Float,
    val trunkAbsoluteDeg: Float
)

data class PersonalBaseline(
    val headForwardRatio: Float,
    val trunkAbsoluteDeg: Float
)

data class PersonalPostureReading(
    val headForwardDeltaRatio: Float,
    val trunkDeltaDeg: Float,
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

        // Nose direction makes the forward-head metric invariant to whether the user faces left/right.
        // Mirroring flips both terms, so the resulting signed forward displacement is also mirror-safe.
        val facingSign = if (nose.x >= ear.x) 1f else -1f
        val headForwardRatio = (((ear.x - shoulder.x) * facingSign) / torsoLength).coerceIn(-1f, 1f)
        val trunkAbsoluteDeg = angleBetween(trunk, Point2(0f, -1f)).coerceIn(0f, 90f)
        return RawDeskMetrics(headForwardRatio, trunkAbsoluteDeg)
    }

    fun evaluate(raw: RawDeskMetrics, baseline: PersonalBaseline): PersonalPostureReading {
        val headDelta = max(0f, raw.headForwardRatio - baseline.headForwardRatio)
        val trunkDelta = max(0f, raw.trunkAbsoluteDeg - baseline.trunkAbsoluteDeg)

        val headLevel = when {
            headDelta < 0.03f -> PostureLevel.LOW
            headDelta < 0.07f -> PostureLevel.MILD
            headDelta < 0.12f -> PostureLevel.HIGH
            else -> PostureLevel.VERY_HIGH
        }

        val relativeTrunkLevel = when {
            trunkDelta < 5f -> PostureLevel.LOW
            trunkDelta < 12f -> PostureLevel.MILD
            trunkDelta < 22f -> PostureLevel.HIGH
            else -> PostureLevel.VERY_HIGH
        }
        val absoluteTrunkLevel = when {
            raw.trunkAbsoluteDeg < 20f -> PostureLevel.LOW
            raw.trunkAbsoluteDeg < 45f -> PostureLevel.MILD
            raw.trunkAbsoluteDeg < 60f -> PostureLevel.HIGH
            else -> PostureLevel.VERY_HIGH
        }
        val trunkLevel = if (relativeTrunkLevel.rank >= absoluteTrunkLevel.rank) relativeTrunkLevel else absoluteTrunkLevel
        val overall = if (headLevel.rank >= trunkLevel.rank) headLevel else trunkLevel

        // Consumer alert logic: meaningful personal deviation OR a clearly flexed trunk.
        // These are coaching heuristics, not disease-risk cutoffs.
        val bad = headDelta >= 0.07f || trunkDelta >= 12f || raw.trunkAbsoluteDeg >= 20f
        val severe = headDelta >= 0.12f || trunkDelta >= 22f || raw.trunkAbsoluteDeg >= 45f

        return PersonalPostureReading(
            headForwardDeltaRatio = headDelta,
            trunkDeltaDeg = trunkDelta,
            trunkAbsoluteDeg = raw.trunkAbsoluteDeg,
            headLevel = headLevel,
            trunkLevel = trunkLevel,
            overallLevel = overall,
            bad = bad,
            severe = severe
        )
    }

    private fun angleBetween(a: Point2, b: Point2): Float {
        val denom = norm(a) * norm(b)
        if (denom <= 1e-8f) return 0f
        val cosine = ((a.x * b.x + a.y * b.y) / denom).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine.toDouble())).toFloat()
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
            trunkSamples += metrics.trunkAbsoluteDeg
        }

        val progress = ((validMs * 100L) / requiredValidMs).toInt().coerceIn(0, 100)
        if (validMs < requiredValidMs) return CalibrationUpdate(true, progress)

        active = false
        if (headSamples.size < minSamples || trunkSamples.size < minSamples) {
            return CalibrationUpdate(false, 100, failed = true)
        }

        val baseline = PersonalBaseline(
            headForwardRatio = median(headSamples),
            trunkAbsoluteDeg = median(trunkSamples)
        )
        return CalibrationUpdate(false, 100, baseline = baseline)
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }
}
