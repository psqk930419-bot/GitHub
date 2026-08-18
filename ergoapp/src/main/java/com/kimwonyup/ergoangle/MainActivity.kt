package com.kimwonyup.ergoangle

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
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
    private lateinit var burdenText: TextView
    private lateinit var neckText: TextView
    private lateinit var trunkText: TextView
    private lateinit var neckExposureText: TextView
    private lateinit var trunkExposureText: TextView
    private lateinit var staticText: TextView
    private lateinit var repetitionText: TextView
    private lateinit var sessionText: TextView
    private lateinit var fpsText: TextView

    private lateinit var backgroundExecutor: ExecutorService
    private var poseHelper: PoseLandmarkerHelper? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val smoother = AngleSmoother()
    private val exposureTracker = ExposureTracker()
    private val bendTracker = BendCycleTracker()
    private var latestAngles: ErgoAngles? = null
    private var lastToastMs = 0L
    private var pendingCsv = ""

    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startPipeline() else Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_LONG).show()
    }

    private val saveCsvLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingCsv) }
                Toast.makeText(this, "CSV를 저장했습니다.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "CSV 저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        viewFinder = findViewById(R.id.viewFinder)
        overlay = findViewById(R.id.overlay)
        guideText = findViewById(R.id.guideText)
        burdenText = findViewById(R.id.burdenText)
        neckText = findViewById(R.id.neckText)
        trunkText = findViewById(R.id.trunkText)
        neckExposureText = findViewById(R.id.neckExposureText)
        trunkExposureText = findViewById(R.id.trunkExposureText)
        staticText = findViewById(R.id.staticText)
        repetitionText = findViewById(R.id.repetitionText)
        sessionText = findViewById(R.id.sessionText)
        fpsText = findViewById(R.id.fpsText)
        findViewById<Button>(R.id.resetButton).setOnClickListener { resetMeasurement() }
        findViewById<Button>(R.id.reportButton).setOnClickListener { showReport() }
        findViewById<Button>(R.id.csvButton).setOnClickListener { exportCsv() }

        backgroundExecutor = Executors.newSingleThreadExecutor()
        resetMeasurement()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startPipeline()
        else requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startPipeline() {
        backgroundExecutor.execute {
            poseHelper = PoseLandmarkerHelper(applicationContext, this)
            runOnUiThread { setUpCamera() }
        }
    }

    private fun resetMeasurement() {
        val now = SystemClock.elapsedRealtime()
        smoother.reset()
        exposureTracker.reset(now)
        bendTracker.reset()
        latestAngles = null
        burdenText.text = "자세 인식 대기 중"
        neckText.text = "목 --°"
        trunkText.text = "몸통 --°"
        neckExposureText.text = "목 >20°  00:00 · 0%"
        trunkExposureText.text = "몸통 >20° 0% · >45° 0% · >60° 0%"
        staticText.text = "정적 자세 ≥1분: 없음"
        repetitionText.text = "몸통 굴곡 반복: 0회/분"
        sessionText.text = "측정 00:00"
        guideText.text = "몸 전체 옆면이 보이도록 2–3 m 거리에서 촬영"
        overlay.clear()
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
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analyzer)
        preview.setSurfaceProvider(viewFinder.surfaceProvider)
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
        exposureTracker.update(now, angles)
        bendTracker.update(now, angles.trunkFlexionDeg)
        val snap = exposureTracker.snapshot(now)
        val cycles = bendTracker.cyclesLastMinute(now)
        val profile = sideProfileHeuristic(pose)

        runOnUiThread {
            overlay.setResults(pose, resultBundle.inputImageWidth, resultBundle.inputImageHeight, angles)
            neckText.text = String.format(Locale.KOREA, "목 %.0f° · %s", angles.neckFlexionDeg, shortLabel(angles.neckLevel))
            trunkText.text = String.format(Locale.KOREA, "몸통 %.0f° · %s", angles.trunkFlexionDeg, shortLabel(angles.trunkLevel))
            neckText.setTextColor(ContextCompat.getColor(this, levelColor(angles.neckLevel)))
            trunkText.setTextColor(ContextCompat.getColor(this, levelColor(angles.trunkLevel)))
            burdenText.text = "현재 자세: ${angles.overallLevel.label}"
            burdenText.setTextColor(ContextCompat.getColor(this, levelColor(angles.overallLevel)))
            neckExposureText.text = String.format(Locale.KOREA, "목 >20°  %s · %d%%  (최장 %s)", formatDuration(snap.neck20Ms), snap.neck20Pct, formatDuration(snap.maxContinuousNeck20Ms))
            trunkExposureText.text = String.format(Locale.KOREA, "몸통 >20° %d%% · >45° %d%% · >60° %d%%", snap.trunk20Pct, snap.trunk45Pct, snap.trunk60Pct)
            staticText.text = if (snap.currentStaticMs >= 60_000L) "정적 자세 ≥1분: 감지됨 · 현재 ${formatDuration(snap.currentStaticMs)}" else "정적 자세: 현재 ${formatDuration(snap.currentStaticMs)} · 1분부터 표시"
            repetitionText.text = "몸통 굴곡 반복: ${cycles}회/분${if (cycles > 4) " · 반복 높음" else ""}"
            sessionText.text = "측정 ${formatDuration(snap.totalMs)}"
            fpsText.text = "분석 ${resultBundle.inferenceTimeMs} ms"
            guideText.text = if (profile) "측면 인식 양호 · 목/몸통 자세노출 분석 중" else "몸을 더 옆으로 돌려 어깨·골반이 겹치게 해주세요"
        }
    }

    override fun onError(error: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastToastMs < 3000L) return
        lastToastMs = now
        runOnUiThread { Toast.makeText(this, error, Toast.LENGTH_SHORT).show() }
    }

    private fun showReport() {
        val now = SystemClock.elapsedRealtime()
        val snap = exposureTracker.snapshot(now)
        val text = buildReport(snap, latestAngles, bendTracker.cyclesLastMinute(now))
        AlertDialog.Builder(this).setTitle("자세노출 요약").setMessage(text)
            .setPositiveButton("확인", null)
            .setNeutralButton("복사") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("ErgoAngle report", text))
                Toast.makeText(this, "요약을 복사했습니다.", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun exportCsv() {
        val now = SystemClock.elapsedRealtime()
        val s = exposureTracker.snapshot(now)
        val a = latestAngles
        pendingCsv = buildString {
            appendLine("metric,value,unit")
            appendLine("session_duration,${s.totalMs / 1000.0},s")
            appendLine("current_neck_angle,${a?.neckFlexionDeg ?: ""},deg")
            appendLine("current_trunk_angle,${a?.trunkFlexionDeg ?: ""},deg")
            appendLine("neck_over_20_duration,${s.neck20Ms / 1000.0},s")
            appendLine("neck_over_20_percent,${s.neck20Pct},percent")
            appendLine("trunk_over_20_percent,${s.trunk20Pct},percent")
            appendLine("trunk_over_45_percent,${s.trunk45Pct},percent")
            appendLine("trunk_over_60_percent,${s.trunk60Pct},percent")
            appendLine("max_continuous_neck_over_20,${s.maxContinuousNeck20Ms / 1000.0},s")
            appendLine("max_continuous_trunk_over_45,${s.maxContinuousTrunk45Ms / 1000.0},s")
            appendLine("static_over_60_seconds_exposure,${s.staticOver60Ms / 1000.0},s")
            appendLine("trunk_bend_cycles_last_minute,${bendTracker.cyclesLastMinute(now)},count_per_min")
        }
        saveCsvLauncher.launch("ErgoAngle_${System.currentTimeMillis()}.csv")
    }

    private fun buildReport(s: ExposureSnapshot, a: ErgoAngles?, cycles: Int): String = buildString {
        appendLine("측정시간: ${formatDuration(s.totalMs)}")
        if (a != null) {
            appendLine("현재 목 자세각: %.0f°".format(a.neckFlexionDeg))
            appendLine("현재 몸통 굴곡각: %.0f°".format(a.trunkFlexionDeg))
        }
        appendLine("목 >20° 노출: ${s.neck20Pct}% (${formatDuration(s.neck20Ms)})")
        appendLine("몸통 >20° 노출: ${s.trunk20Pct}%")
        appendLine("몸통 >45° 노출: ${s.trunk45Pct}%")
        appendLine("몸통 >60° 노출: ${s.trunk60Pct}%")
        appendLine("최장 연속 목 >20°: ${formatDuration(s.maxContinuousNeck20Ms)}")
        appendLine("최장 연속 몸통 >45°: ${formatDuration(s.maxContinuousTrunk45Ms)}")
        appendLine("현재 정적자세 지속: ${formatDuration(s.currentStaticMs)}")
        appendLine("몸통 굴곡 반복: ${cycles}회/분")
        appendLine()
        append("※ 단일 RGB 카메라 기반 자세노출 선별값이며 질병 위험도나 임상 진단값이 아닙니다. 하중·파지·근사용을 직접 측정하지 않으므로 완전한 RULA/REBA 점수가 아닙니다.")
    }

    private fun midpoint(pose: List<NormalizedLandmark>, a: Int, b: Int): Point2 = Point2((pose[a].x() + pose[b].x()) / 2f, (pose[a].y() + pose[b].y()) / 2f)

    private fun sideProfileHeuristic(pose: List<NormalizedLandmark>): Boolean {
        val shoulderGap = abs(pose[11].x() - pose[12].x())
        val hipGap = abs(pose[23].x() - pose[24].x())
        return (shoulderGap + hipGap) / 2f < 0.13f
    }

    private fun shortLabel(level: PostureLevel): String = when (level) {
        PostureLevel.LOW -> "낮음"
        PostureLevel.MILD -> "경미"
        PostureLevel.HIGH -> "높음"
        PostureLevel.VERY_HIGH -> "매우 높음"
    }

    private fun levelColor(level: PostureLevel): Int = when (level) {
        PostureLevel.LOW -> R.color.good
        PostureLevel.MILD -> R.color.warn
        PostureLevel.HIGH -> R.color.orange
        PostureLevel.VERY_HIGH -> R.color.bad
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) String.format(Locale.KOREA, "%02d:%02d:%02d", hours, minutes, seconds) else String.format(Locale.KOREA, "%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        backgroundExecutor.execute { poseHelper?.clear() }
        backgroundExecutor.shutdown()
        try { backgroundExecutor.awaitTermination(500, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { }
        super.onDestroy()
    }
}
