from pathlib import Path

path = Path('ergoapp/src/main/java/com/kimwonyup/ergoangle/MainActivity.kt')
text = path.read_text(encoding='utf-8')

old = '''    private val temporalFilter = PostureTemporalFilter()\n'''
new = '''    private val temporalFilter = PostureTemporalFilter()\n    private val targetSelector = TargetPoseSelector()\n'''
if old not in text:
    raise SystemExit('V3.3 temporal filter field not found; apply V3.3 patch first')
text = text.replace(old, new, 1)

old = '''        val now = SystemClock.elapsedRealtime()\n        calibrator.start(now)\n        calibrationButton.text = "측정 중 0%"\n'''
new = '''        val now = SystemClock.elapsedRealtime()\n        baseline = null\n        targetSelector.beginCalibration()\n        temporalFilter.reset(now)\n        calibrator.start(now)\n        calibrationButton.text = "측정 중 0%"\n'''
if old not in text:
    raise SystemExit('startCalibration block not found')
text = text.replace(old, new, 1)

old = '''        baseline = null\n        calibrator.cancel()\n        resetSession()\n'''
new = '''        baseline = null\n        calibrator.cancel()\n        targetSelector.reset()\n        resetSession()\n'''
if old not in text:
    raise SystemExit('invalidateBaseline block not found')
text = text.replace(old, new, 1)

old = '''    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {\n        val pose = resultBundle.result.landmarks().firstOrNull() ?: return\n        if (pose.size < 33) return\n\n        val nose = Point2(pose[0].x(), pose[0].y())\n        val ear = midpoint(pose, 7, 8)\n        val shoulder = midpoint(pose, 11, 12)\n        val hip = midpoint(pose, 23, 24)\n        val raw = PersonalPostureEngine.measure(nose, ear, shoulder, hip) ?: return\n        val now = SystemClock.elapsedRealtime()\n        val sideProfile = frameQualityHeuristic(pose, raw, calibrator.isActive())\n        latestSideProfile = sideProfile\n'''
new = '''    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {\n        val poses = resultBundle.result.landmarks()\n        val now = SystemClock.elapsedRealtime()\n        if (poses.isEmpty()) {\n            latestSideProfile = false\n            if (calibrator.isActive()) calibrator.onFrame(now, null, false)\n            runOnUiThread {\n                guideText.text = if (targetSelector.isLocked()) "캘리브레이션한 사용자를 다시 찾는 중입니다" else "몸통 전체가 보이도록 화면 중앙에 앉아주세요"\n                statusText.text = "현재 자세: 측정 대기"\n            }\n            return\n        }\n\n        val features = poses.mapIndexedNotNull { index, candidate ->\n            if (candidate.size < 33) return@mapIndexedNotNull null\n            val cNose = Point2(candidate[0].x(), candidate[0].y())\n            val cEar = midpoint(candidate, 7, 8)\n            val cShoulder = midpoint(candidate, 11, 12)\n            val cHip = midpoint(candidate, 23, 24)\n            val cRaw = PersonalPostureEngine.measure(cNose, cEar, cShoulder, cHip) ?: return@mapIndexedNotNull null\n            val valid = frameQualityHeuristic(candidate, cRaw, calibrator.isActive())\n            targetFeature(candidate, index, valid)\n        }\n        val selectedIndex = targetSelector.select(features, now)\n        if (selectedIndex == null || selectedIndex !in poses.indices) {\n            latestSideProfile = false\n            val update = if (calibrator.isActive()) calibrator.onFrame(now, null, false) else null\n            runOnUiThread {\n                if (update != null) {\n                    calibrationButton.text = "측정 중 ${update.progressPct}%"\n                    calibrationText.text = "개인 기준 측정 중 ${update.progressPct}%"\n                }\n                guideText.text = if (targetSelector.isLocked()) "다른 사람은 무시하고 캘리브레이션한 사용자를 찾는 중입니다" else "사용자가 화면 중앙에 오도록 위치를 맞춰주세요"\n                statusText.text = "현재 자세: 사용자 찾는 중"\n                angleDetailText.text = "상세: 대상 사용자가 보일 때 분석합니다"\n            }\n            return\n        }\n\n        val pose = poses[selectedIndex]\n        if (pose.size < 33) return\n        val nose = Point2(pose[0].x(), pose[0].y())\n        val ear = midpoint(pose, 7, 8)\n        val shoulder = midpoint(pose, 11, 12)\n        val hip = midpoint(pose, 23, 24)\n        val raw = PersonalPostureEngine.measure(nose, ear, shoulder, hip) ?: return\n        val sideProfile = frameQualityHeuristic(pose, raw, calibrator.isActive())\n        latestSideProfile = sideProfile\n'''
if old not in text:
    raise SystemExit('onResults opening block not found; V3.3 runtime patch may not have been applied')
text = text.replace(old, new, 1)

old = '''            if (update.baseline != null) {\n                baseline = update.baseline\n                deskTracker.reset(now)\n'''
new = '''            if (update.baseline != null) {\n                if (!targetSelector.lockCalibration(now)) {\n                    baseline = null\n                    runOnUiThread {\n                        calibrationButton.text = "5초 기준 자세 잡기"\n                        calibrationText.text = "대상 사용자 고정에 실패했습니다 · 화면 중앙에서 다시 측정해주세요"\n                        guideText.text = "다른 사람이 겹치지 않게 하고 다시 기준 자세를 잡아주세요"\n                    }\n                    return\n                }\n                baseline = update.baseline\n                deskTracker.reset(now)\n'''
if old not in text:
    raise SystemExit('baseline completion block not found')
text = text.replace(old, new, 1)

old = '''            } else if (update.failed) {\n                runOnUiThread {\n'''
new = '''            } else if (update.failed) {\n                targetSelector.reset()\n                runOnUiThread {\n'''
if old not in text:
    raise SystemExit('calibration failure block not found')
text = text.replace(old, new, 1)

anchor = '''    private fun formatDuration(ms: Long): String {\n'''
helper = '''    private fun targetFeature(\n        pose: List<NormalizedLandmark>,\n        index: Int,\n        valid: Boolean\n    ): PoseTargetFeature {\n        val shoulderGap = abs(pose[11].x() - pose[12].x())\n        val hipGap = abs(pose[23].x() - pose[24].x())\n        val shoulderMidX = (pose[11].x() + pose[12].x()) / 2f\n        val shoulderMidY = (pose[11].y() + pose[12].y()) / 2f\n        val hipMidX = (pose[23].x() + pose[24].x()) / 2f\n        val hipMidY = (pose[23].y() + pose[24].y()) / 2f\n        val dx = shoulderMidX - hipMidX\n        val dy = shoulderMidY - hipMidY\n        val torso = kotlin.math.sqrt(dx * dx + dy * dy)\n        val width = (shoulderGap + hipGap) / 2f\n        val widthRatio = if (torso > 1e-5f) width / torso else 99f\n        return PoseTargetFeature(\n            index = index,\n            centerX = (shoulderMidX + hipMidX) / 2f,\n            centerY = (shoulderMidY + hipMidY) / 2f,\n            torsoLength = torso,\n            widthRatio = widthRatio,\n            valid = valid\n        )\n    }\n\n'''
if anchor not in text:
    raise SystemExit('formatDuration anchor not found')
text = text.replace(anchor, helper + anchor, 1)

path.write_text(text, encoding='utf-8')
print('Applied V3.4 multi-person calibration target lock')
