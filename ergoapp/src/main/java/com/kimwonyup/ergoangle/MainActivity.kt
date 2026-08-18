package com.kimwonyup.ergoangle

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class MainActivity : AppCompatActivity(), PoseLandmarkerHelper.LandmarkerListener {
    private lateinit var viewFinder: PreviewView
    private lateinit var overlay: PoseOverlayView
    private lateinit var guideText: TextView
    private lateinit var scoreText: TextView
    private lateinit var scoreSubText: TextView
    private lateinit var sessionText: TextView
    private lateinit var statusText: TextView
    private lateinit var goodText: TextView
    private lateinit var badRunText: TextView
    private lateinit var angleDetailText: TextView
    private lateinit var modeButton: Button
    private lateinit var focusButton: Button
    private lateinit var cameraButton: Button
    private lateinit var startPauseButton: Button

    private lateinit var backgroundExecutor: ExecutorService
    private var poseHelper: PoseLandmarkerHelper? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val smoother = AngleSmoother()
    private val deskTracker = DeskPostureTracker()
    private var latestAngles: ErgoAngles? = null

    private var mode = DeskMode.WORK
    private var focusMinutes = 50
    private var isSessionRunning = false
    private var isFrontCamera = true
    private var sessionAccumulatedMs = 0L
    private var sessionStartedAtMs = 0L
    private var focusReminderSent = false
    private var lastBadAlertMs = 0L
    private var lastToastMs = 0L

    private val uiHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateClockUi()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startPipeline() else Toast.makeText(this, "자세 분석을 위해 카메라 권한이 필요합니다.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        overlay = findViewById(R.id.overlay)
        guideText = findViewById(R.id.guideText)
        scoreText = findViewById(R.id.scoreText)
        scoreSubText = findViewById(R.id.scoreSubText)
        sessionText = findViewById(R.id.sessionText)
        statusText = findViewById(R.id.statusText)
        goodText = findViewById(R.id.goodText)
        badRunText = findViewById(R.id.badRunText)
        angleDetailText = findViewById(R.id.angleDetailText)
        modeButton = findViewById(R.id.modeButton)
        focusButton = findViewById(R.id.focusButton)
        cameraButton = findViewById(R.id.cameraButton)
        startPauseButton = findViewById(R.id.startPauseButton)

        modeButton.setOnClickListener { toggleMode() }
        focusButton.setOnClickListener { chooseFocusTime() }
        cameraButton.setOnClickListener { switchCamera() }
        startPauseButton.setOnClickListener { toggleSession() }
        findViewById<Button>(R.id.reportButton).setOnClickListener { showReport() }
        findViewById<Button>(R.id.resetButton).setOnClickListener { resetSession() }

        backgroundExecutor = Executors.newSingleThreadExecutor()
        resetSession()
        uiHandler.post(ticker)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startPipeline()
        else requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startPipeline() {
        backgroundExecutor.execute {
            poseHelper = PoseLandmarkerHelper(applicationContext, this)
            runOnUiThread { setUpCamera() }
        }
    }

    private fun resetSession() {
        val now = SystemClock.elapsedRealtime()
        isSessionRunning = false
        sessionAccumulatedMs = 0L
        sessionStartedAtMs = 0L
        focusReminderSent = false
        lastBadAlertMs = 0L
        smoother.reset()
        deskTracker.reset(now)
        latestAngles = null

        scoreText.text = "자세점수 --"
        scoreSubText.text = "세션을 시작하면 자동으로 계산합니다"
        statusText.text = "현재 자세: 카메라 위치를 맞춰주세요"
        goodText.text = "바른 자세 --% · 나쁜 자세 --%"
        badRunText.text = "연속 나쁜 자세 00:00 · 45초부터 조용히 진동"
        angleDetailText.text = "상세: 목 --° · 몸통 --°"
        startPauseButton.text = "세션 시작"
        modeButton.text = mode.label
        focusButton.text = "집중 ${focusMinutes}분"
        cameraButton.text = if (isFrontCamera) "전면 카메라" else "후면 카메라"
        guideText.text = "폰을 옆에 세우고 귀·어깨·골반이 보이게 맞춰주세요"
        overlay.clear()
        updateClockUi()
    }

    private fun toggleMode() {
        if (isSessionRunning) {
            Toast.makeText(this, "세션을 일시정지한 뒤 모드를 바꿔주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        mode = if (mode == DeskMode.WORK) DeskMode.STUDY else DeskMode.WORK
        modeButton.text = mode.label
        Toast.makeText(this, if (mode == DeskMode.WORK) "업무용 자세 코칭" else "공부 자세 + 집중 세션", Toast.LENGTH_SHORT).show()
    }

    private fun chooseFocusTime() {
        if (isSessionRunning) {
            Toast.makeText(this, "세션을 일시정지한 뒤 집중시간을 바꿔주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val values = intArrayOf(25, 50, 90)
        val labels = arrayOf("25분 · 짧은 집중", "50분 · 기본", "90분 · 긴 집중")
        AlertDialog.Builder(this)
            .setTitle("집중 세션 시간")
            .setItems(labels) { _, which ->
                focusMinutes = values[which]
                focusReminderSent = false
                focusButton.text = "집중 ${focusMinutes}분"
                updateClockUi()
            }.show()
    }

    private fun toggleSession() {
        val now = SystemClock.elapsedRealtime()
        if (!isSessionRunning) {
            isSessionRunning = true
            sessionStartedAtMs = now
            deskTracker.resume(now)
            startPauseButton.text = "일시정지"
            scoreSubText.text = "자세를 분석하고 있습니다"
            Toast.makeText(this, "${mode.label} 시작", Toast.LENGTH_SHORT).show()
        } else {
            sessionAccumulatedMs += now - sessionStartedAtMs
            isSessionRunning = false
            sessionStartedAtMs = 0L
            startPauseButton.text = "계속하기"
            scoreSubText.text = "일시정지됨"
        }
        updateClockUi()
    }

    private fun switchCamera() {
        isFrontCamera = !isFrontCamera
        cameraButton.text = if (isFrontCamera) "전면 카메라" else "후면 카메라"
        bindCameraUseCases()
    }

    private fun setUpCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ cameraProvider = future.get(); bindCameraUseCases() }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("MissingPermission")
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val rotation = viewFinder.display.rotation
        val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).setTargetRotation(rotation).build()
        val analyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analyzer.setAnalyzer(backgroundExecutor) { image ->
            val helper = poseHelper
            if (helper == null) image.close() else helper.detectLiveStream(image)
        }
        provider.unbindAll()
        val selector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.bindToLifecycle(this, selector, preview, analyzer)
            preview.setSurfaceProvider(viewFinder.surfaceProvider)
        } catch (e: Exception) {
            isFrontCamera = !isFrontCamera
            cameraButton.text = if (isFrontCamera) "전면 카메라" else "후면 카메라"
            Toast.makeText(this, "선택한 카메라를 사용할 수 없어 다른 카메라로 전환했습니다.", Toast.LENGTH_SHORT).show()
            val fallback = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            provider.bindToLifecycle(this, fallback, preview, analyzer)
            preview.setSurfaceProvider(viewFinder.surfaceProvider)
        }
    }

    override fun onResults(resultBundle: PoseLandmarkerHelper.ResultBundle) {
        val pose = resultBundle.result.landmarks().firstOrNull() ?: return
        if (pose.size < 33) return

        val ear = midpoint(pose, 7, 8)
        val shoulder = midpoint(pose, 11, 12)
        val hip = midpoint(pose, 23, 24)
        val raw = ErgoAngleCalculator.calculate(ear, shoulder, hip) ?: return
        val angles = smoother.update(raw)
        latestAngles = angles
        val now = SystemClock.elapsedRealtime()
        if (isSessionRunning) deskTracker.update(now, angles)
        val snap = deskTracker.snapshot()
        val sideProfile = sideProfileHeuristic(pose)

        if (isSessionRunning && snap.currentBadRunMs >= BAD_POSTURE_ALERT_MS && now - lastBadAlertMs >= BAD_POSTURE_REPEAT_MS) {
            lastBadAlertMs = now
            runOnUiThread {
                vibrateBadPosture()
                Toast.makeText(this, "자세가 오래 무너졌어요. 목과 몸통을 한 번 펴주세요.", Toast.LENGTH_SHORT).show()
            }
        }

        runOnUiThread {
            overlay.setResults(pose, resultBundle.inputImageWidth, resultBundle.inputImageHeight, angles, mirrored = isFrontCamera)
            angleDetailText.text = String.format(Locale.KOREA, "상세: 목 %.0f° · 몸통 %.0f°", angles.neckFlexionDeg, angles.trunkFlexionDeg)
            val currentLabel = friendlyCurrentLabel(angles)
            statusText.text = "현재 자세: $currentLabel"
            statusText.setTextColor(ContextCompat.getColor(this, levelColor(angles.overallLevel)))

            if (snap.observedMs >= 2000L) {
                scoreText.text = "자세점수 ${snap.postureScore}"
                scoreText.setTextColor(ContextCompat.getColor(this, scoreColor(snap.postureScore)))
                scoreSubText.text = scoreMessage(snap.postureScore)
                goodText.text = "바른 자세 ${snap.goodPct}% · 나쁜 자세 ${snap.badPct}%"
                badRunText.text = "연속 나쁜 자세 ${formatDuration(snap.currentBadRunMs)} · 최장 ${formatDuration(snap.maxBadRunMs)}"
            }

            guideText.text = when {
                !sideProfile -> "몸을 더 옆으로 돌려 어깨와 골반이 겹치게 해주세요"
                !isSessionRunning -> "측면 인식 양호 · 세션 시작을 누르면 자세 코칭을 시작합니다"
                else -> "측면 인식 양호 · 영상 저장 없이 기기에서 실시간 분석 중"
            }
        }
    }

    override fun onError(error: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastToastMs < 3000L) return
        lastToastMs = now
        runOnUiThread { Toast.makeText(this, error, Toast.LENGTH_SHORT).show() }
    }

    private fun updateClockUi() {
        val elapsed = sessionElapsedMs()
        sessionText.text = "${formatDuration(elapsed)} / ${focusMinutes}:00"
        if (isSessionRunning && !focusReminderSent && elapsed >= focusMinutes * 60_000L) {
            focusReminderSent = true
            vibrateFocusComplete()
            Toast.makeText(this, "집중 세션 완료! 2–5분 일어나 움직여보세요.", Toast.LENGTH_LONG).show()
        }
    }

    private fun sessionElapsedMs(): Long {
        if (!isSessionRunning || sessionStartedAtMs == 0L) return sessionAccumulatedMs
        return sessionAccumulatedMs + (SystemClock.elapsedRealtime() - sessionStartedAtMs)
    }

    private fun showReport() {
        val s = deskTracker.snapshot()
        val a = latestAngles
        val report = buildString {
            appendLine("${mode.label} · 집중 목표 ${focusMinutes}분")
            appendLine("세션 시간: ${formatDuration(sessionElapsedMs())}")
            if (s.observedMs > 0L) {
                appendLine("자세점수: ${s.postureScore}/100")
                appendLine("바른 자세: ${s.goodPct}%")
                appendLine("나쁜 자세: ${s.badPct}%")
                appendLine("강한 자세부담 구간: ${s.severePct}%")
                appendLine("최장 연속 나쁜 자세: ${formatDuration(s.maxBadRunMs)}")
                appendLine()
                appendLine("[상세 노출]")
                appendLine("목 ≥20°: ${s.neck20Pct}%")
                appendLine("몸통 ≥20°: ${s.trunk20Pct}%")
                appendLine("몸통 ≥45°: ${s.trunk45Pct}%")
                appendLine("몸통 ≥60°: ${s.trunk60Pct}%")
            } else appendLine("아직 충분한 자세 데이터가 없습니다.")
            if (a != null) appendLine("현재 측정: 목 %.0f° · 몸통 %.0f°".format(a.neckFlexionDeg, a.trunkFlexionDeg))
            appendLine()
            append("※ 자세점수는 카메라 기반 생활습관 피드백용 휴리스틱이며 질병 발생확률이나 임상 진단값이 아닙니다. 영상은 앱에서 저장하지 않습니다.")
        }

        AlertDialog.Builder(this)
            .setTitle("이번 세션 요약")
            .setMessage(report)
            .setPositiveButton("확인", null)
            .setNeutralButton("복사") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ErgoAngle Desk report", report))
                Toast.makeText(this, "세션 요약을 복사했습니다.", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun friendlyCurrentLabel(a: ErgoAngles): String = when {
        a.neckFlexionDeg < 20f && a.trunkFlexionDeg < 20f -> "좋아요"
        a.neckFlexionDeg >= 30f || a.trunkFlexionDeg >= 45f -> "자세 리셋이 필요해요"
        else -> "조금만 펴주세요"
    }

    private fun scoreMessage(score: Int): String = when {
        score >= 90 -> "아주 안정적으로 앉아 있어요"
        score >= 80 -> "좋은 자세 흐름을 유지하고 있어요"
        score >= 65 -> "가끔 자세가 무너져요"
        score >= 45 -> "앞으로 숙이는 시간이 꽤 길어요"
        else -> "자세를 자주 리셋해주는 게 좋아요"
    }

    private fun scoreColor(score: Int): Int = when {
        score >= 80 -> R.color.good
        score >= 65 -> R.color.warn
        score >= 45 -> R.color.orange
        else -> R.color.bad
    }

    private fun levelColor(level: PostureLevel): Int = when (level) {
        PostureLevel.LOW -> R.color.good
        PostureLevel.MILD -> R.color.warn
        PostureLevel.HIGH -> R.color.orange
        PostureLevel.VERY_HIGH -> R.color.bad
    }

    private fun midpoint(pose: List<NormalizedLandmark>, a: Int, b: Int): Point2 =
        Point2((pose[a].x() + pose[b].x()) / 2f, (pose[a].y() + pose[b].y()) / 2f)

    private fun sideProfileHeuristic(pose: List<NormalizedLandmark>): Boolean {
        val shoulderGap = abs(pose[11].x() - pose[12].x())
        val hipGap = abs(pose[23].x() - pose[24].x())
        return (shoulderGap + hipGap) / 2f < 0.13f
    }

    private fun vibrateBadPosture() {
        val vibrator = getVibrator()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(180L, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vibrator.vibrate(180L)
    }

    private fun vibrateFocusComplete() {
        val vibrator = getVibrator()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 120L, 100L, 180L), -1))
        else @Suppress("DEPRECATION") vibrator.vibrate(longArrayOf(0L, 120L, 100L, 180L), -1)
    }

    private fun getVibrator(): Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) String.format(Locale.KOREA, "%02d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.KOREA, "%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(ticker)
        cameraProvider?.unbindAll()
        backgroundExecutor.execute { poseHelper?.clear() }
        backgroundExecutor.shutdown()
        try { backgroundExecutor.awaitTermination(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
        super.onDestroy()
    }

    companion object {
        private const val BAD_POSTURE_ALERT_MS = 45_000L
        private const val BAD_POSTURE_REPEAT_MS = 90_000L
    }
}
