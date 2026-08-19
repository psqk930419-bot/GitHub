package com.kimwonyup.ergoangle

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class Point2(val x: Float, val y: Float)

enum class PostureLevel(val label: String, val rank: Int) {
    LOW("낮은 자세노출", 0),
    MILD("경미한 굴곡", 1),
    HIGH("높은 굴곡 노출", 2),
    VERY_HIGH("매우 높은 굴곡 노출", 3)
}

data class ErgoAngles(
    val neckFlexionDeg: Float,
    val trunkFlexionDeg: Float,
    val neckLevel: PostureLevel,
    val trunkLevel: PostureLevel,
    val overallLevel: PostureLevel
)

object ErgoAngleCalculator {
    fun calculate(ear: Point2, shoulder: Point2, hip: Point2): ErgoAngles? {
        val neckUp = Point2(ear.x - shoulder.x, ear.y - shoulder.y)
        val trunkUp = Point2(shoulder.x - hip.x, shoulder.y - hip.y)
        if (norm(neckUp) < 1e-5f || norm(trunkUp) < 1e-5f) return null
        val neck = angleBetween(neckUp, trunkUp).coerceIn(0f, 90f)
        val trunk = angleBetween(trunkUp, Point2(0f, -1f)).coerceIn(0f, 90f)
        return classify(neck, trunk)
    }

    fun classify(neck: Float, trunk: Float): ErgoAngles {
        val neckLevel = when {
            neck < 10f -> PostureLevel.LOW
            neck < 20f -> PostureLevel.MILD
            else -> PostureLevel.HIGH
        }
        val trunkLevel = when {
            trunk < 20f -> PostureLevel.LOW
            trunk < 45f -> PostureLevel.MILD
            trunk < 60f -> PostureLevel.HIGH
            else -> PostureLevel.VERY_HIGH
        }
        val overall = if (neckLevel.rank >= trunkLevel.rank) neckLevel else trunkLevel
        return ErgoAngles(neck, trunk, neckLevel, trunkLevel, overall)
    }

    private fun angleBetween(a: Point2, b: Point2): Float {
        val denom = norm(a) * norm(b)
        if (denom <= 1e-8f) return 0f
        val cosine = ((a.x * b.x + a.y * b.y) / denom).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine.toDouble())).toFloat()
    }

    private fun norm(v: Point2): Float = sqrt(v.x * v.x + v.y * v.y)
}

class AngleSmoother(private val alpha: Float = 0.20f) {
    private var neck: Float? = null
    private var trunk: Float? = null

    fun update(raw: ErgoAngles): ErgoAngles {
        neck = neck?.let { it + alpha * (raw.neckFlexionDeg - it) } ?: raw.neckFlexionDeg
        trunk = trunk?.let { it + alpha * (raw.trunkFlexionDeg - it) } ?: raw.trunkFlexionDeg
        return ErgoAngleCalculator.classify(neck ?: raw.neckFlexionDeg, trunk ?: raw.trunkFlexionDeg)
    }

    fun reset() {
        neck = null
        trunk = null
    }
}

data class ExposureSnapshot(
    val totalMs: Long,
    val neck20Ms: Long,
    val trunk20Ms: Long,
    val trunk45Ms: Long,
    val trunk60Ms: Long,
    val maxContinuousNeck20Ms: Long,
    val maxContinuousTrunk45Ms: Long,
    val currentStaticMs: Long,
    val staticOver60Ms: Long
) {
    private fun pct(value: Long): Int = if (totalMs <= 0L) 0 else min(100, ((value * 100.0) / totalMs).toInt())
    val neck20Pct: Int get() = pct(neck20Ms)
    val trunk20Pct: Int get() = pct(trunk20Ms)
    val trunk45Pct: Int get() = pct(trunk45Ms)
    val trunk60Pct: Int get() = pct(trunk60Ms)
}

class ExposureTracker {
    private var sessionStartMs = 0L
    private var previousMs = 0L
    private var neck20Ms = 0L
    private var trunk20Ms = 0L
    private var trunk45Ms = 0L
    private var trunk60Ms = 0L
    private var neck20RunMs = 0L
    private var trunk45RunMs = 0L
    private var maxNeck20RunMs = 0L
    private var maxTrunk45RunMs = 0L
    private var lastNeck: Float? = null
    private var lastTrunk: Float? = null
    private var staticRunMs = 0L
    private var staticOver60Ms = 0L

    fun reset(nowMs: Long) {
        sessionStartMs = nowMs
        previousMs = nowMs
        neck20Ms = 0L
        trunk20Ms = 0L
        trunk45Ms = 0L
        trunk60Ms = 0L
        neck20RunMs = 0L
        trunk45RunMs = 0L
        maxNeck20RunMs = 0L
        maxTrunk45RunMs = 0L
        lastNeck = null
        lastTrunk = null
        staticRunMs = 0L
        staticOver60Ms = 0L
    }

    fun update(nowMs: Long, angles: ErgoAngles) {
        if (sessionStartMs == 0L) reset(nowMs)
        val delta = (nowMs - previousMs).coerceIn(0L, 1000L)

        if (angles.neckFlexionDeg >= 20f) {
            neck20Ms += delta
            neck20RunMs += delta
            maxNeck20RunMs = max(maxNeck20RunMs, neck20RunMs)
        } else neck20RunMs = 0L

        if (angles.trunkFlexionDeg >= 20f) trunk20Ms += delta
        if (angles.trunkFlexionDeg >= 45f) {
            trunk45Ms += delta
            trunk45RunMs += delta
            maxTrunk45RunMs = max(maxTrunk45RunMs, trunk45RunMs)
        } else trunk45RunMs = 0L
        if (angles.trunkFlexionDeg >= 60f) trunk60Ms += delta

        val ln = lastNeck
        val lt = lastTrunk
        val stable = ln != null && lt != null && abs(angles.neckFlexionDeg - ln) <= 3f && abs(angles.trunkFlexionDeg - lt) <= 3f
        if (stable) {
            staticRunMs += delta
            if (staticRunMs >= 60_000L) staticOver60Ms += delta
        } else staticRunMs = 0L

        lastNeck = angles.neckFlexionDeg
        lastTrunk = angles.trunkFlexionDeg
        previousMs = nowMs
    }

    fun snapshot(nowMs: Long): ExposureSnapshot = ExposureSnapshot(
        totalMs = if (sessionStartMs == 0L) 0L else max(0L, nowMs - sessionStartMs),
        neck20Ms = neck20Ms,
        trunk20Ms = trunk20Ms,
        trunk45Ms = trunk45Ms,
        trunk60Ms = trunk60Ms,
        maxContinuousNeck20Ms = maxNeck20RunMs,
        maxContinuousTrunk45Ms = maxTrunk45RunMs,
        currentStaticMs = staticRunMs,
        staticOver60Ms = staticOver60Ms
    )
}

class BendCycleTracker {
    private var armed = false
    private val cycleTimes = ArrayDeque<Long>()

    fun reset() {
        armed = false
        cycleTimes.clear()
    }

    fun update(nowMs: Long, trunkAngle: Float) {
        if (!armed && trunkAngle >= 20f) armed = true
        if (armed && trunkAngle < 10f) {
            cycleTimes.addLast(nowMs)
            armed = false
        }
        prune(nowMs)
    }

    fun cyclesLastMinute(nowMs: Long): Int {
        prune(nowMs)
        return cycleTimes.size
    }

    private fun prune(nowMs: Long) {
        while (cycleTimes.isNotEmpty() && nowMs - cycleTimes.first() > 60_000L) cycleTimes.removeFirst()
    }
}
