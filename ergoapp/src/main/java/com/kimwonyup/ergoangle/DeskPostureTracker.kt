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
    val headHighMs: Long,
    val trunkHighMs: Long,
    val currentBadRunMs: Long,
    val maxBadRunMs: Long
) {
    private fun pct(value: Long): Int = if (observedMs <= 0L) 0 else min(100, ((value * 100.0) / observedMs).roundToInt())
    val goodPct: Int get() = pct(goodMs)
    val badPct: Int get() = pct(badMs)
    val severePct: Int get() = pct(severeMs)
    val headHighPct: Int get() = pct(headHighMs)
    val trunkHighPct: Int get() = pct(trunkHighMs)
    val postureScore: Int get() = if (observedMs <= 0L) 0 else (100.0 - badPct * 0.60 - severePct * 0.40).roundToInt().coerceIn(0, 100)
}

class DeskPostureTracker {
    private var previousMs = 0L
    private var observedMs = 0L
    private var goodMs = 0L
    private var badMs = 0L
    private var severeMs = 0L
    private var headHighMs = 0L
    private var trunkHighMs = 0L
    private var currentBadRunMs = 0L
    private var maxBadRunMs = 0L

    fun reset(nowMs: Long) {
        previousMs = nowMs
        observedMs = 0L
        goodMs = 0L
        badMs = 0L
        severeMs = 0L
        headHighMs = 0L
        trunkHighMs = 0L
        currentBadRunMs = 0L
        maxBadRunMs = 0L
    }

    fun resume(nowMs: Long) {
        previousMs = nowMs
    }

    fun update(nowMs: Long, reading: PersonalPostureReading) {
        if (previousMs == 0L) previousMs = nowMs
        val delta = (nowMs - previousMs).coerceIn(0L, 1000L)
        previousMs = nowMs
        observedMs += delta

        if (!reading.bad) goodMs += delta
        if (reading.bad) {
            badMs += delta
            currentBadRunMs += delta
            maxBadRunMs = max(maxBadRunMs, currentBadRunMs)
        } else {
            currentBadRunMs = 0L
        }
        if (reading.severe) severeMs += delta
        if (reading.headLevel.rank >= PostureLevel.HIGH.rank) headHighMs += delta
        if (reading.trunkLevel.rank >= PostureLevel.HIGH.rank) trunkHighMs += delta
    }

    fun snapshot(): DeskPostureSnapshot = DeskPostureSnapshot(
        observedMs = observedMs,
        goodMs = goodMs,
        badMs = badMs,
        severeMs = severeMs,
        headHighMs = headHighMs,
        trunkHighMs = trunkHighMs,
        currentBadRunMs = currentBadRunMs,
        maxBadRunMs = maxBadRunMs
    )
}
