from pathlib import Path

path = Path('ergoapp/src/main/java/com/kimwonyup/ergoangle/MainActivity.kt')
text = path.read_text(encoding='utf-8')

# Imports.
old = 'import android.view.WindowManager\n'
new = 'import android.view.WindowManager\nimport android.view.View\n'
if old not in text:
    raise SystemExit('WindowManager import anchor not found')
text = text.replace(old, new, 1)

# View fields + onboarding state.
old = '''    private lateinit var calibrationButton: Button\n    private lateinit var startPauseButton: Button\n'''
new = '''    private lateinit var calibrationButton: Button\n    private lateinit var startPauseButton: Button\n    private lateinit var detailsButton: Button\n    private lateinit var helpButton: Button\n    private lateinit var onboardingOverlay: View\n    private lateinit var onboardingWorkButton: Button\n    private lateinit var onboardingStudyButton: Button\n    private lateinit var onboardingPrimaryButton: Button\n\n    private var onboardingAutoArm = false\n    private var autoStartAfterCalibration = false\n    private var autoReadySinceMs = 0L\n    private var detailsVisible = false\n'''
if old not in text:
    raise SystemExit('button field anchor not found')
text = text.replace(old, new, 1)

# Bind new views.
old = '''        calibrationButton = findViewById(R.id.calibrationButton)\n        startPauseButton = findViewById(R.id.startPauseButton)\n'''
new = '''        calibrationButton = findViewById(R.id.calibrationButton)\n        startPauseButton = findViewById(R.id.startPauseButton)\n        detailsButton = findViewById(R.id.detailsButton)\n        helpButton = findViewById(R.id.helpButton)\n        onboardingOverlay = findViewById(R.id.onboardingOverlay)\n        onboardingWorkButton = findViewById(R.id.onboardingWorkButton)\n        onboardingStudyButton = findViewById(R.id.onboardingStudyButton)\n        onboardingPrimaryButton = findViewById(R.id.onboardingPrimaryButton)\n'''
if old not in text:
    raise SystemExit('view binding anchor not found')
text = text.replace(old, new, 1)

# Listeners.
old = '''        findViewById<Button>(R.id.reportButton).setOnClickListener { showReport() }\n        findViewById<Button>(R.id.resetButton).setOnClickListener { resetSession() }\n'''
new = '''        findViewById<Button>(R.id.reportButton).setOnClickListener { showReport() }\n        findViewById<Button>(R.id.resetButton).setOnClickListener { resetSession() }\n        detailsButton.setOnClickListener { toggleDetails() }\n        helpButton.setOnClickListener { showOnboarding() }\n        onboardingWorkButton.setOnClickListener { setOnboardingMode(DeskMode.WORK) }\n        onboardingStudyButton.setOnClickListener { setOnboardingMode(DeskMode.STUDY) }\n        onboardingPrimaryButton.setOnClickListener { finishOnboardingAndPrepare() }\n'''
if old not in text:
    raise SystemExit('listener anchor not found')
text = text.replace(old, new, 1)

# First run onboarding after reset.
old = '''        backgroundExecutor = Executors.newSingleThreadExecutor()\n        resetSession()\n        uiHandler.post(ticker)\n'''
new = '''        backgroundExecutor = Executors.newSingleThreadExecutor()\n        resetSession()\n        setupFirstRunOnboarding()\n        uiHandler.post(ticker)\n'''
if old not in text:
    raise SystemExit('onCreate reset anchor not found')
text = text.replace(old, new, 1)

# Consumer-friendly detail visibility on reset.
old = '''        angleDetailText.text = "상세: 머리 전방 --% · 상체 변화 --°"\n        startPauseButton.text = "세션 시작"\n'''
new = '''        angleDetailText.text = "상세: 머리 전방 --% · 상체 변화 --°"\n        angleDetailText.visibility = if (detailsVisible) View.VISIBLE else View.GONE\n        detailsButton.text = if (detailsVisible) "상세 닫기" else "상세"\n        startPauseButton.text = "세션 시작"\n'''
if old not in text:
    raise SystemExit('reset detail anchor not found')
text = text.replace(old, new, 1)

# Insert onboarding helpers before manual calibration.
anchor = '''    private fun startCalibration() {\n'''
helpers = '''    private fun setupFirstRunOnboarding() {\n        val done = getSharedPreferences("ergoangle_prefs", Context.MODE_PRIVATE)\n            .getBoolean("onboarding_done", false)\n        if (done) {\n            onboardingOverlay.visibility = View.GONE\n        } else {\n            showOnboarding()\n        }\n    }\n\n    private fun showOnboarding() {\n        if (isSessionRunning) {\n            Toast.makeText(this, "세션을 일시정지한 뒤 처음 사용법을 볼 수 있습니다.", Toast.LENGTH_SHORT).show()\n            return\n        }\n        onboardingOverlay.visibility = View.VISIBLE\n        setOnboardingMode(mode)\n    }\n\n    private fun setOnboardingMode(newMode: DeskMode) {\n        mode = newMode\n        modeButton.text = mode.label\n        onboardingWorkButton.text = if (mode == DeskMode.WORK) "✓ 업무" else "업무"\n        onboardingStudyButton.text = if (mode == DeskMode.STUDY) "✓ 공부" else "공부"\n    }\n\n    private fun finishOnboardingAndPrepare() {\n        onboardingOverlay.visibility = View.GONE\n        getSharedPreferences("ergoangle_prefs", Context.MODE_PRIVATE)\n            .edit().putBoolean("onboarding_done", true).apply()\n        onboardingAutoArm = true\n        autoStartAfterCalibration = true\n        autoReadySinceMs = 0L\n        baseline = null\n        targetSelector.reset()\n        calibrator.cancel()\n        guideText.text = "화면 중앙에 편하게 앉으세요 · 측면 구도가 맞으면 자동으로 5초 보정을 시작합니다"\n        calibrationText.text = "자동 설정 준비 중"\n        scoreSubText.text = "머리·어깨·골반이 한 화면에 보이면 됩니다"\n    }\n\n    private fun toggleDetails() {\n        detailsVisible = !detailsVisible\n        angleDetailText.visibility = if (detailsVisible) View.VISIBLE else View.GONE\n        detailsButton.text = if (detailsVisible) "상세 닫기" else "상세"\n    }\n\n'''
if anchor not in text:
    raise SystemExit('startCalibration anchor not found')
text = text.replace(anchor, helpers + anchor, 1)

# When onboarding is armed but target lock has not started, choose the best valid central pose
# only for camera-position readiness. Once calibration begins, TargetPoseSelector takes over.
old = '''        val selectedIndex = targetSelector.select(features, now)\n'''
new = '''        val selectedIndex = if (onboardingAutoArm && baseline == null && !calibrator.isActive() && !targetSelector.isLocked()) {\n            features.filter { it.valid }.minByOrNull {\n                kotlin.math.abs(it.centerX - 0.5f) * 1.8f + kotlin.math.abs(it.centerY - 0.52f) - it.torsoLength * 0.25f\n            }?.index\n        } else {\n            targetSelector.select(features, now)\n        }\n'''
if old not in text:
    raise SystemExit('V3.4 selectedIndex anchor not found; apply V3.4 patch first')
text = text.replace(old, new, 1)

# Auto-arm 5 s calibration after 1.5 s of stable valid side-view geometry.
old = '''        latestSideProfile = sideProfile\n\n        if (calibrator.isActive()) {\n'''
new = '''        latestSideProfile = sideProfile\n\n        if (onboardingAutoArm && baseline == null && !calibrator.isActive()) {\n            if (sideProfile) {\n                if (autoReadySinceMs == 0L) autoReadySinceMs = now\n                val readyMs = now - autoReadySinceMs\n                val readyPct = ((readyMs * 100L) / 1500L).coerceIn(0L, 100L).toInt()\n                runOnUiThread {\n                    guideText.text = "카메라 위치 확인 중 ${readyPct}% · 그대로 앉아주세요"\n                    calibrationText.text = "사용자와 측면 구도를 확인하고 있습니다"\n                }\n                if (readyMs >= 1500L) {\n                    onboardingAutoArm = false\n                    autoReadySinceMs = 0L\n                    runOnUiThread { startCalibration() }\n                    return\n                }\n            } else {\n                autoReadySinceMs = 0L\n                runOnUiThread {\n                    guideText.text = "폰을 몸 옆 60–100cm에 세우고 머리·어깨·골반이 모두 보이게 맞춰주세요"\n                    calibrationText.text = "카메라 위치를 맞추는 중"\n                }\n            }\n        }\n\n        if (calibrator.isActive()) {\n'''
if old not in text:
    raise SystemExit('sideProfile/calibrator anchor not found')
text = text.replace(old, new, 1)

# Auto-start the chosen study/work session after successful calibration from onboarding.
old = '''                    vibrateCalibrationComplete()\n                    Toast.makeText(this, "5초 개인 기준 설정이 완료됐습니다.", Toast.LENGTH_SHORT).show()\n                }\n'''
new = '''                    vibrateCalibrationComplete()\n                    Toast.makeText(this, "5초 개인 기준 설정이 완료됐습니다.", Toast.LENGTH_SHORT).show()\n                    if (autoStartAfterCalibration) {\n                        guideText.text = "준비 완료 · 잠시 후 ${mode.label}을 시작합니다"\n                        uiHandler.postDelayed({\n                            if (baseline != null && !isSessionRunning) toggleSession()\n                            autoStartAfterCalibration = false\n                        }, 700L)\n                    }\n                }\n'''
if old not in text:
    raise SystemExit('calibration completion UI anchor not found')
text = text.replace(old, new, 1)

# Manual calibration should cancel any pending auto-start unless it was initiated by onboarding.
old = '''        val now = SystemClock.elapsedRealtime()\n        baseline = null\n        targetSelector.beginCalibration()\n'''
new = '''        val now = SystemClock.elapsedRealtime()\n        baseline = null\n        onboardingAutoArm = false\n        autoReadySinceMs = 0L\n        targetSelector.beginCalibration()\n'''
if old not in text:
    raise SystemExit('V3.4 startCalibration target anchor not found')
text = text.replace(old, new, 1)

path.write_text(text, encoding='utf-8')
print('Applied V3.5 first-run onboarding + auto calibration/start flow')
