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
        color = 0xFFFFFFFF.toInt(); textSize = 32f; style = Paint.Style.FILL
        setShadowLayer(5f, 0f, 2f, 0xCC000000.toInt())
    }

    private var landmarks: List<NormalizedLandmark>? = null
    private var imageWidth = 1
    private var imageHeight = 1
    private var reading: PersonalPostureReading? = null
    private var mirrored = false

    fun setResults(
        result: List<NormalizedLandmark>,
        inputWidth: Int,
        inputHeight: Int,
        reading: PersonalPostureReading?,
        mirrored: Boolean
    ) {
        landmarks = result
        imageWidth = inputWidth.coerceAtLeast(1)
        imageHeight = inputHeight.coerceAtLeast(1)
        this.reading = reading
        this.mirrored = mirrored
        invalidate()
    }

    fun clear() {
        landmarks = null
        reading = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val lm = landmarks ?: return
        if (lm.size < 33) return

        val scale = max(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val scaledW = imageWidth * scale
        val scaledH = imageHeight * scale
        val dx = (width - scaledW) / 2f
        val dy = (height - scaledH) / 2f

        fun p(index: Int): Pt {
            val m = lm[index]
            val sourceX = if (mirrored) 1f - m.x() else m.x()
            return Pt(sourceX * imageWidth * scale + dx, m.y() * imageHeight * scale + dy)
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
        val r = reading

        if (r != null) {
            fillPaint.color = withAlpha(levelColor(r.trunkLevel), 48)
            val path = Path().apply {
                moveTo(shoulder.x - 28f, shoulder.y)
                lineTo(shoulder.x + 28f, shoulder.y)
                lineTo(hip.x + 38f, hip.y)
                lineTo(hip.x - 38f, hip.y)
                close()
            }
            canvas.drawPath(path, fillPaint)

            heatPaint.color = levelColor(r.headLevel)
            canvas.drawLine(ear.x, ear.y, shoulder.x, shoulder.y, heatPaint)
            heatPaint.color = levelColor(r.trunkLevel)
            canvas.drawLine(shoulder.x, shoulder.y, hip.x, hip.y, heatPaint)

            textPaint.color = levelColor(r.headLevel)
            canvas.drawText("머리 전방 +${r.headForwardDeltaPct}%", ear.x + 18f, (ear.y + shoulder.y) / 2f, textPaint)
            textPaint.color = levelColor(r.trunkLevel)
            canvas.drawText("상체 +%.0f°".format(r.trunkDeltaDeg), shoulder.x + 22f, (shoulder.y + hip.y) / 2f, textPaint)
        } else {
            heatPaint.color = 0xCCFFFFFF.toInt()
            canvas.drawLine(ear.x, ear.y, shoulder.x, shoulder.y, heatPaint)
            canvas.drawLine(shoulder.x, shoulder.y, hip.x, hip.y, heatPaint)
            textPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawText("기준 자세 측정 중", shoulder.x + 20f, shoulder.y - 24f, textPaint)
        }

        canvas.drawLine(hip.x, hip.y, hip.x, hip.y - 180f, referencePaint)
        listOf(ear, shoulder, hip).forEach { canvas.drawPoint(it.x, it.y, pointPaint) }
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
