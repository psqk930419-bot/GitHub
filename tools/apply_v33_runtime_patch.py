from pathlib import Path

path = Path('ergoapp/src/main/java/com/kimwonyup/ergoangle/MainActivity.kt')
text = path.read_text(encoding='utf-8')

# Add temporal filter next to the session tracker.
old = '''    private val deskTracker = DeskPostureTracker()\n    private val calibrator = BaselineCalibrator()\n'''
new = '''    private val deskTracker = DeskPostureTracker()\n    private val calibrator = BaselineCalibrator()\n    private val temporalFilter = PostureTemporalFilter()\n'''
if old in text:
    text = text.replace(old, new)

# Reset temporal state with the session.
old = '''        deskTracker.reset(now)\n        latestReading = null\n'''
new = '''        deskTracker.reset(now)\n        temporalFilter.reset(now)\n        latestReading = null\n'''
if old in text:
    text = text.replace(old, new, 1)

# Reset filter whenever a focus session starts/resumes so pre-session frames cannot preload BAD state.
old = '''            isSessionRunning = true\n            sessionStartedAtMs = now\n            deskTracker.resume(now)\n'''
new = '''            isSessionRunning = true\n            sessionStartedAtMs = now\n            deskTracker.resume(now)\n            temporalFilter.reset(now)\n'''
if old in text:
    text = text.replace(old, new)

# Use a quality-aware side gate that knows whether this is the 5-second neutral calibration.
old = '''        val sideProfile = sideProfileHeuristic(pose)\n'''
new = '''        val sideProfile = frameQualityHeuristic(pose, raw, calibrator.isActive())\n'''
if old not in text:
    raise SystemExit('Expected side-profile call not found')
text = text.replace(old, new, 1)

# Reset temporal state after establishing a fresh personal baseline.
old = '''                baseline = update.baseline\n                deskTracker.reset(now)\n                latestReading = PersonalPostureEngine.evaluate(raw, update.baseline)\n'''
new = '''                baseline = update.baseline\n                deskTracker.reset(now)\n                temporalFilter.reset(now)\n                latestReading = PersonalPostureEngine.evaluate(raw, update.baseline)\n'''
if old in text:
    text = text.replace(old, new, 1)

# Invalid geometry should not be shown as a posture score/angle at all.
old = '''        val reading = PersonalPostureEngine.evaluate(raw, b)\n        latestReading = reading\n        if (isSessionRunning && sideProfile) deskTracker.update(now, reading)\n        val snap = deskTracker.snapshot()\n'''
new = '''        if (!sideProfile) {\n            runOnUiThread {\n                overlay.setResults(pose, resultBundle.inputImageWidth, resultBundle.inputImageHeight, null, mirrored = isFrontCamera)\n                guideText.text = "몸통 전체가 보이는 측면 위치로 맞춰주세요 · 이 시간은 점수에서 제외됩니다"\n                statusText.text = "현재 자세: 측정 대기"\n                angleDetailText.text = "상세: 몸통 전체가 보여야 분석합니다"\n            }\n            return\n        }\n\n        val reading = if (isSessionRunning) {\n            temporalFilter.update(now, raw, b)\n        } else {\n            PersonalPostureEngine.evaluate(raw, b)\n        }\n        latestReading = reading\n        if (isSessionRunning) deskTracker.update(now, reading)\n        val snap = deskTracker.snapshot()\n'''
if old not in text:
    raise SystemExit('Expected reading block not found')
text = text.replace(old, new, 1)

# Replace the build-time scale-normalized gate with the V3.3 full-torso geometry gate.
old = '''    private fun sideProfileHeuristic(pose: List<NormalizedLandmark>): Boolean {\n        val shoulderGap = abs(pose[11].x() - pose[12].x())\n        val hipGap = abs(pose[23].x() - pose[24].x())\n        val shoulderMidX = (pose[11].x() + pose[12].x()) / 2f\n        val shoulderMidY = (pose[11].y() + pose[12].y()) / 2f\n        val hipMidX = (pose[23].x() + pose[24].x()) / 2f\n        val hipMidY = (pose[23].y() + pose[24].y()) / 2f\n        val torsoDx = shoulderMidX - hipMidX\n        val torsoDy = shoulderMidY - hipMidY\n        val torsoLength = kotlin.math.sqrt(torsoDx * torsoDx + torsoDy * torsoDy)\n        if (torsoLength < 1e-5f) return false\n\n        // Scale-normalized gate: camera distance/zoom should not decide whether a side view is valid.\n        val projectedBodyWidth = (shoulderGap + hipGap) / 2f\n        return projectedBodyWidth / torsoLength < 0.50f\n    }\n'''
new = '''    private fun frameQualityHeuristic(\n        pose: List<NormalizedLandmark>,\n        raw: RawDeskMetrics,\n        forCalibration: Boolean\n    ): Boolean {\n        val shoulderGap = abs(pose[11].x() - pose[12].x())\n        val hipGap = abs(pose[23].x() - pose[24].x())\n        val shoulderMidX = (pose[11].x() + pose[12].x()) / 2f\n        val shoulderMidY = (pose[11].y() + pose[12].y()) / 2f\n        val hipMidX = (pose[23].x() + pose[24].x()) / 2f\n        val hipMidY = (pose[23].y() + pose[24].y()) / 2f\n        val torsoDx = shoulderMidX - hipMidX\n        val torsoDy = shoulderMidY - hipMidY\n        val torsoLength = kotlin.math.sqrt(torsoDx * torsoDx + torsoDy * torsoDy)\n        if (torsoLength < 0.10f) return false\n\n        val projectedBodyWidth = (shoulderGap + hipGap) / 2f\n        if (projectedBodyWidth / torsoLength >= 0.50f) return false\n\n        // Near-horizontal shoulder-to-hip geometry is usually a cropped/partial-body\n        // pose hallucination in desk mode. Deep bends remain measurable up to 75°.\n        if (abs(raw.trunkForwardDeg) > 75f) return false\n\n        // A personal neutral baseline should itself be reasonably seated/upright.\n        // This specifically blocks a hand/forearm crop from becoming the user's baseline.\n        if (forCalibration && abs(raw.trunkForwardDeg) > 45f) return false\n        return true\n    }\n'''
if old not in text:
    raise SystemExit('Expected normalized side-profile function not found. Run apply_side_profile_patch.py first.')
text = text.replace(old, new, 1)

path.write_text(text, encoding='utf-8')
print('Applied V3.3 temporal stabilization + full-torso quality gate')
