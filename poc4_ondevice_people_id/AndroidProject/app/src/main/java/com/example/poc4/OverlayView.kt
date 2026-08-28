package com.example.poc4

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

data class RecognizedFace(
    val boundingBox: Rect,
    val name: String,
    val relationship: String = "",
    val confidence: Float
)

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val recognizedFaces = mutableListOf<RecognizedFace>()
    private val scaledFaceRects = mutableListOf<Pair<RectF, RecognizedFace>>()
    private var selectedFaceIndex: Int? = null

    private var imageWidth = 1
    private var imageHeight = 1

    private val boxPaint = Paint().apply {
        color = Color.parseColor("#06B6D4") // Accent Cyan
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val selectedBoxPaint = Paint().apply {
        color = Color.parseColor("#F59E0B") // Amber Highlight for selected face
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
    }

    private val textBgPaint = Paint().apply {
        color = Color.parseColor("#E20F172A") // Deep slate dark blue
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    private val subTextPaint = Paint().apply {
        color = Color.parseColor("#38BDF8") // Light cyan for percentage
        textSize = 34f
        typeface = Typeface.DEFAULT
        isAntiAlias = true
    }

    fun setFaces(faces: List<RecognizedFace>, imgWidth: Int, imgHeight: Int) {
        recognizedFaces.clear()
        recognizedFaces.addAll(faces)
        imageWidth = if (imgWidth > 0) imgWidth else 1
        imageHeight = if (imgHeight > 0) imgHeight else 1
        selectedFaceIndex = null // Reset touch selection on new image
        invalidate()
    }

    fun clear() {
        recognizedFaces.clear()
        scaledFaceRects.clear()
        selectedFaceIndex = null
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val touchX = event.x
            val touchY = event.y

            var newlySelected: Int? = null
            for ((index, pair) in scaledFaceRects.withIndex()) {
                val rectF = pair.first
                // Expand touch target area slightly for easier finger tapping
                val touchRect = RectF(rectF.left - 30f, rectF.top - 30f, rectF.right + 30f, rectF.bottom + 30f)
                if (touchRect.contains(touchX, touchY)) {
                    newlySelected = index
                    break
                }
            }

            // Toggle selection or select new face
            selectedFaceIndex = if (selectedFaceIndex == newlySelected) null else newlySelected
            performClick()
            invalidate()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        return super.performClick()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        scaledFaceRects.clear()
        if (recognizedFaces.isEmpty()) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val scale = Math.min(viewWidth / imageWidth, viewHeight / imageHeight)
        val dx = (viewWidth - imageWidth * scale) / 2f
        val dy = (viewHeight - imageHeight * scale) / 2f

        for ((index, face) in recognizedFaces.withIndex()) {
            val rect = face.boundingBox

            val left = rect.left * scale + dx
            val top = rect.top * scale + dy
            val right = rect.right * scale + dx
            val bottom = rect.bottom * scale + dy

            val rectF = RectF(left, top, right, bottom)
            scaledFaceRects.add(Pair(rectF, face))

            val isSelected = (selectedFaceIndex == index)

            // 1. Draw bounding box rectangle for ALL faces
            canvas.drawRect(rectF, if (isSelected) selectedBoxPaint else boxPaint)

            // 2. Draw text label ONLY when user touches/selects this face rectangle!
            if (isSelected) {
                val relText = if (face.relationship.isNotEmpty()) " (${face.relationship})" else ""
                val mainLabelText = "${face.name}$relText"
                val scorePctText = String.format(java.util.Locale.US, "Similaridade: %.1f%%", face.confidence * 100)

                val textWidth1 = textPaint.measureText(mainLabelText)
                val textWidth2 = subTextPaint.measureText(scorePctText)
                val maxTextWidth = Math.max(textWidth1, textWidth2)

                val bannerHeight = 90f
                val labelLeft = Math.max(10f, left)
                val labelTop = Math.max(10f, top - bannerHeight - 10f)
                val labelRight = labelLeft + maxTextWidth + 30f
                val labelBottom = labelTop + bannerHeight

                val bannerRect = RectF(labelLeft, labelTop, labelRight, labelBottom)
                canvas.drawRoundRect(bannerRect, 16f, 16f, textBgPaint)

                canvas.drawText(mainLabelText, labelLeft + 15f, labelTop + 40f, textPaint)
                canvas.drawText(scorePctText, labelLeft + 15f, labelTop + 78f, subTextPaint)
            }
        }
    }
}
