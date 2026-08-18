package com.kimwonyup.ergoangle

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class DeskMode(val label: String) {
    WORK("업무 모드"),
    STUDY("공부 모드")
}

data class DeskPostureSnapshot(
    val observedMs: Long,
    val goodMs: Long,
    val badMs: Long,
    val severeMs: Long,
    val neck20Ms: Long,
    val trunk20Ms: Long,
    val trunk45Ms: Long,
    val trunk60Ms: Long,
    val currentBadRunMs: Long,
    val maxBadRunMs: Long
) {
    private fun pct(value: Long): Int = if (observedMs <= 0L) 0 else min(100, ((value * 100.0) / observedMs).roundToInt())
    val goodPct: Int get() = pct(goodMs)
    val badPct: Int get() = pct(badMs)
    val severePct: Int get() = pct(severeMs)
    val neck20Pct: Int get() = pct(neck20Ms)
    val trunk20Pct: Int get() = pct(trunk20Ms)
    val trunk45Pct: Int get() = pct(trunk45Ms)
    val trunk60Pct: Int get() = pct(trunk60Ms)
    val postureScore: Int get() = if (observedMs <= 0L) 0 else (100.0 - badPct * 0.65 - severePct * 0.35).roundToInt().coerceIn(0, 100)
}

class DeskPostureTracker {
    private var previousMs = 0L
    private var observedMs = 0L
    private var goodMs = 0L
    private var badMs = 0L
    private var severeMs = 0L
    private var neck20Ms = 0L
    private var trunk20Ms = 0L
    private var trunk45Ms = 0L
    private var trunk60Ms = 0L
    private var currentBadRunMs = 0L
    private var maxBadRunMs = 0L

    fun reset(nowMs: Long) {
        previousMs = nowMs
        observedMs = 0L
        goodMs = 0L
        badMs = 0L
        severeMs = 0L
        neck20Ms = 0L
        trunk20Ms = 0L
        trunk45Ms = 0L
        trunk60Ms = 0L
        currentBadRunMs = 0L
        maxBadRunMs = 0L
    }

    fun resume(nowMs: Long) {
        previousMs = nowMs
    }

    fun update(nowMs: Long, angles: ErgoAngles) {
        if (previousMs == 0L) previousMs = nowMs
        val delta = (nowMs - previousMs).coerceIn(0L, 1000L)
        previousMs = nowMs
        observedMs += delta

        val neckBad = angles.neckFlexionDeg >= 20f
        val trunkBad = angles.trunkFlexionDeg >= 20f
        val bad = neckBad || trunkBad
        val severe = angles.neckFlexionDeg >= 30f || angles.trunkFlexionDeg >= 45f

        if (!bad) goodMs += delta
        if (bad) {
            badMs += delta
            currentBadRunMs += delta
            maxBadRunMs = max(maxBadRunMs, currentBadRunMs)
        } else {
            currentBadRunMs = 0L
        }
        if (severe) severeMs += delta
        if (neckBad) neck20Ms += delta
        if (trunkBad) trunk20Ms += delta
        if (angles.trunkFlexionDeg >= 45f) trunk45Ms += delta
        if (angles.trunkFlexionDeg >= 60f) trunk60Ms += delta
    }

    fun snapshot(): DeskPostureSnapshot = DeskPostureSnapshot(
        observedMs = observedMs,
        goodMs = goodMs,
        badMs = badMs,
        severeMs = severeMs,
        neck20Ms = neck20Ms,
        trunk20Ms = trunk20Ms,
        trunk45Ms = trunk45Ms,
        trunk60Ms = trunk60Ms,
        currentBadRunMs = currentBadRunMs,
        maxBadRunMs = maxBadRunMs
    )
}
