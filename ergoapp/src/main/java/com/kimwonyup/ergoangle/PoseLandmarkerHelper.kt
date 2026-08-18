package com.kimwonyup.ergoangle

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class PoseLandmarkerHelper(
    private val context: Context,
    private val listener: LandmarkerListener,
    private val minPoseDetectionConfidence: Float = 0.5f,
    private val minPoseTrackingConfidence: Float = 0.5f,
    private val minPosePresenceConfidence: Float = 0.5f
) {
    private var poseLandmarker: PoseLandmarker? = null

    init { setup() }

    fun setup() {
        clear()
        try {
            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.CPU)
                .setModelAssetPath("pose_landmarker_lite.task")
                .build()
            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinPoseDetectionConfidence(minPoseDetectionConfidence)
                .setMinTrackingConfidence(minPoseTrackingConfidence)
                .setMinPosePresenceConfidence(minPosePresenceConfidence)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::returnLiveStreamResult)
                .setErrorListener(this::returnLiveStreamError)
                .build()
            poseLandmarker = PoseLandmarker.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.e(TAG, "PoseLandmarker init failed", e)
            listener.onError("자세 인식 모델 초기화 실패: ${e.message ?: "unknown"}")
        }
    }

    fun clear() {
        poseLandmarker?.close()
        poseLandmarker = null
    }

    fun detectLiveStream(imageProxy: ImageProxy) {
        val landmarker = poseLandmarker
        if (landmarker == null) {
            imageProxy.close()
            return
        }
        val frameTime = SystemClock.uptimeMillis()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val width = imageProxy.width
        val height = imageProxy.height
        try {
            val bitmapBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            imageProxy.planes[0].buffer.rewind()
            bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
            imageProxy.close()
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotatedBitmap = Bitmap.createBitmap(bitmapBuffer, 0, 0, bitmapBuffer.width, bitmapBuffer.height, matrix, true)
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            landmarker.detectAsync(mpImage, frameTime)
        } catch (e: Exception) {
            try { imageProxy.close() } catch (_: Exception) { }
            listener.onError("카메라 프레임 분석 오류: ${e.message ?: "unknown"}")
        }
    }

    private fun returnLiveStreamResult(result: PoseLandmarkerResult, input: MPImage) {
        listener.onResults(
            ResultBundle(
                result = result,
                inferenceTimeMs = SystemClock.uptimeMillis() - result.timestampMs(),
                inputImageHeight = input.height,
                inputImageWidth = input.width
            )
        )
    }

    private fun returnLiveStreamError(error: RuntimeException) {
        listener.onError(error.message ?: "Pose Landmarker 오류")
    }

    data class ResultBundle(
        val result: PoseLandmarkerResult,
        val inferenceTimeMs: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int
    )

    interface LandmarkerListener {
        fun onResults(resultBundle: ResultBundle)
        fun onError(error: String)
    }

    companion object { private const val TAG = "ErgoAnglePose" }
}
