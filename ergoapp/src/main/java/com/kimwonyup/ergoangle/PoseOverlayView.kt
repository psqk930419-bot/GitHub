package com.kimwonyup.ergoangle

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.max

class PoseOverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val skeletonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt(); strokeWidth = 4f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); strokeWidth = 8f; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
    }
    private val referencePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88FFFFFF.toInt(); strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val heatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 18f; strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 34f; style = Paint.Style.FILL
        setShadowLayer(5f, 0f, 2f, 0xCC000000.toInt())
    }

    private var landmarks: List<NormalizedLandmark>? = null
    private var imageWidth = 1
    private var imageHeight = 1
    private var angles: ErgoAngles? = null

    fun setResults(result: List<NormalizedLandmark>, inputWidth: Int, inputHeight: Int, angles: ErgoAngles) {
        landmarks = result
        imageWidth = inputWidth.coerceAtLeast(1)
        imageHeight = inputHeight.coerceAtLeast(1)
        this.angles = angles
        invalidate()
    }

    fun clear() {
        landmarks = null
        angles = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lm = landmarks ?: return
        val a = angles ?: return
        if (lm.size < 33) return

        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val scaledW = imageWidth * scale
        val scaledH = imageHeight * scale
        val dx = (width - scaledW) / 2f
        val dy = (height - scaledH) / 2f

        fun p(index: Int): Pt {
            val m = lm[index]
            return Pt(m.x() * imageWidth * scale + dx, m.y() * imageHeight * scale + dy)
        }
        fun mid(i: Int, j: Int): Pt {
            val p1 = p(i); val p2 = p(j)
            return Pt((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
        }
        fun line(i: Int, j: Int) {
            val p1 = p(i); val p2 = p(j)
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, skeletonPaint)
        }

        val connections = arrayOf(
            7 to 8, 11 to 12, 23 to 24,
            11 to 13, 13 to 15, 12 to 14, 14 to 16,
            11 to 23, 12 to 24, 23 to 25, 25 to 27, 24 to 26, 26 to 28,
            27 to 29, 29 to 31, 28 to 30, 30 to 32
        )
        connections.forEach { line(it.first, it.second) }

        val ear = mid(7, 8)
        val shoulder = mid(11, 12)
        val hip = mid(23, 24)

        fillPaint.color = withAlpha(levelColor(a.trunkLevel), 48)
        val path = Path().apply {
            moveTo(shoulder.x - 28f, shoulder.y)
            lineTo(shoulder.x + 28f, shoulder.y)
            lineTo(hip.x + 38f, hip.y)
            lineTo(hip.x - 38f, hip.y)
            close()
        }
        canvas.drawPath(path, fillPaint)

        heatPaint.color = levelColor(a.neckLevel)
        canvas.drawLine(ear.x, ear.y, shoulder.x, shoulder.y, heatPaint)
        heatPaint.color = levelColor(a.trunkLevel)
        canvas.drawLine(shoulder.x, shoulder.y, hip.x, hip.y, heatPaint)

        canvas.drawLine(hip.x, hip.y, hip.x, hip.y - 180f, referencePaint)
        listOf(ear, shoulder, hip).forEach { canvas.drawPoint(it.x, it.y, pointPaint) }

        textPaint.color = levelColor(a.neckLevel)
        canvas.drawText("목 %.0f°".format(a.neckFlexionDeg), ear.x + 20f, (ear.y + shoulder.y) / 2f, textPaint)
        textPaint.color = levelColor(a.trunkLevel)
        canvas.drawText("몸통 %.0f°".format(a.trunkFlexionDeg), shoulder.x + 24f, (shoulder.y + hip.y) / 2f, textPaint)
    }

    private fun levelColor(level: PostureLevel): Int = when (level) {
        PostureLevel.LOW -> 0xFF4CAF50.toInt()
        PostureLevel.MILD -> 0xFFFFC107.toInt()
        PostureLevel.HIGH -> 0xFFFF7A00.toInt()
        PostureLevel.VERY_HIGH -> 0xFFF44336.toInt()
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
    private data class Pt(val x: Float, val y: Float)
}
