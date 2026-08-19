package com.kimwonyup.ergoangle

import kotlin.math.abs

/**
 * Temporal stabilization for consumer desk coaching.
 *
 * - EMA reduces frame-to-frame landmark jitter.
 * - BAD state must persist before entering/leaving the coaching state.
 * - Clinical/disease-risk meaning is not implied; these are UI/coaching heuristics.
 */
class PostureTemporalFilter(
    private val alpha: Float = 0.22f,
    private val enterDelayMs: Long = 1_500L,
    private val exitDelayMs: Long = 1_500L
) {
    private var emaHeadRatio: Float? = null
    private var emaTrunkForwardDeg: Float? = null
    private var lastMs: Long = 0L
    private var badState = false
    private var enterAccumMs = 0L
    private var exitAccumMs = 0L

    fun reset(nowMs: Long = 0L) {
        emaHeadRatio = null
        emaTrunkForwardDeg = null
        lastMs = nowMs
        badState = false
        enterAccumMs = 0L
        exitAccumMs = 0L
    }

    fun update(
        nowMs: Long,
        raw: RawDeskMetrics,
        baseline: PersonalBaseline
    ): PersonalPostureReading {
        val dt = if (lastMs == 0L) 0L else (nowMs - lastMs).coerceIn(0L, 1_000L)
        lastMs = nowMs

        val h = emaHeadRatio?.let { it + alpha * (raw.headForwardRatio - it) } ?: raw.headForwardRatio
        val t = emaTrunkForwardDeg?.let { it + alpha * (raw.trunkForwardDeg - it) } ?: raw.trunkForwardDeg
        emaHeadRatio = h
        emaTrunkForwardDeg = t

        val smoothed = raw.copy(
            headForwardRatio = h,
            trunkForwardDeg = t,
            trunkAbsoluteDeg = abs(t)
        )
        val candidate = PersonalPostureEngine.evaluate(smoothed, baseline)

        if (candidate.bad) {
            enterAccumMs += dt
            exitAccumMs = 0L
            if (!badState && enterAccumMs >= enterDelayMs) {
                badState = true
                enterAccumMs = 0L
            }
        } else {
            exitAccumMs += dt
            enterAccumMs = 0L
            if (badState && exitAccumMs >= exitDelayMs) {
                badState = false
                exitAccumMs = 0L
            }
        }

        // Keep the continuous severity estimate for display, but do not let a one-frame
        // excursion count as an active bad-posture episode in the session tracker.
        return candidate.copy(
            bad = badState,
            severe = badState && candidate.severe
        )
    }

    fun currentBadState(): Boolean = badState
}
