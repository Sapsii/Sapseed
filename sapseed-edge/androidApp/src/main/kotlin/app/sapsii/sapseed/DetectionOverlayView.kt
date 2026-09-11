package app.sapsii.sapseed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import app.sapsii.sapseed.edge.model.Detection
import java.util.Locale

/** Draws numbered detector results over a FILL_CENTER/CENTER_CROP camera preview. */
class DetectionOverlayView(context: Context) : View(context) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 255, 204)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = resources.displayMetrics.scaledDensity * 12f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 15, 23, 42)
        style = Paint.Style.FILL
    }

    private var detections: List<Detection> = emptyList()
    private var sourceWidth = 1
    private var sourceHeight = 1

    fun show(detections: List<Detection>, sourceWidth: Int, sourceHeight: Int) {
        this.detections = detections.sortedByDescending(Detection::confidence)
        this.sourceWidth = sourceWidth.coerceAtLeast(1)
        this.sourceHeight = sourceHeight.coerceAtLeast(1)
        invalidate()
    }

    fun clear() {
        detections = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (detections.isEmpty()) return

        val scale = maxOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        val renderedWidth = sourceWidth * scale
        val renderedHeight = sourceHeight * scale
        val offsetX = (width - renderedWidth) / 2f
        val offsetY = (height - renderedHeight) / 2f
        val horizontalPadding = resources.displayMetrics.density * 5f
        val verticalPadding = resources.displayMetrics.density * 3f

        detections.forEachIndexed { index, detection ->
            val bounds = detection.bounds
            val rectangle = RectF(
                offsetX + bounds.left * renderedWidth,
                offsetY + bounds.top * renderedHeight,
                offsetX + bounds.right * renderedWidth,
                offsetY + bounds.bottom * renderedHeight,
            )
            canvas.drawRect(rectangle, boxPaint)

            val label = String.format(
                Locale.US,
                "%02d  %s  %.0f%%",
                index + 1,
                detection.label.uppercase(Locale.US),
                detection.confidence * 100f,
            )
            val textWidth = labelPaint.measureText(label)
            val textHeight = labelPaint.fontMetrics.run { bottom - top }
            val labelLeft = rectangle.left.coerceIn(0f, (width - textWidth - horizontalPadding * 2).coerceAtLeast(0f))
            val labelBottom = rectangle.top.coerceAtLeast(textHeight + verticalPadding * 2)
            canvas.drawRect(
                labelLeft,
                labelBottom - textHeight - verticalPadding * 2,
                labelLeft + textWidth + horizontalPadding * 2,
                labelBottom,
                labelBackgroundPaint,
            )
            canvas.drawText(
                label,
                labelLeft + horizontalPadding,
                labelBottom - verticalPadding - labelPaint.fontMetrics.bottom,
                labelPaint,
            )
        }
    }
}
