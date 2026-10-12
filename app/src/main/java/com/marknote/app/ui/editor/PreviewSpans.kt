package com.marknote.app.ui.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
import android.view.View
import kotlin.math.max
import kotlin.math.roundToInt

internal class TaskBoxSpan(
    private val sizePx: Float,
    private val checked: Boolean,
    private val outlineColor: Int,
    private val fillColor: Int,
    private val tickColor: Int,
) : ReplacementSpan() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = Path()

    override fun getSize(
        paint: Paint,
        text: CharSequence?,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int = sizePx.roundToInt()

    override fun draw(
        canvas: Canvas,
        text: CharSequence?,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        val fm = paint.fontMetrics
        val centerY = y + (fm.ascent + fm.descent) / 2f
        val left = x
        val right = left + sizePx
        val topEdge = centerY - sizePx / 2f
        val bottomEdge = centerY + sizePx / 2f
        val radius = sizePx * 0.2f
        this.paint.reset()
        if (checked) {
            this.paint.style = Paint.Style.FILL
            this.paint.color = fillColor
            canvas.drawRoundRect(left, topEdge, right, bottomEdge, radius, radius, this.paint)
            tick.reset()
            tick.moveTo(left + sizePx * 0.26f, centerY + sizePx * 0.02f)
            tick.lineTo(left + sizePx * 0.44f, centerY + sizePx * 0.2f)
            tick.lineTo(left + sizePx * 0.76f, centerY - sizePx * 0.22f)
            this.paint.style = Paint.Style.STROKE
            this.paint.strokeWidth = max(1.5f, sizePx * 0.13f)
            this.paint.strokeCap = Paint.Cap.ROUND
            this.paint.strokeJoin = Paint.Join.ROUND
            this.paint.color = tickColor
            canvas.drawPath(tick, this.paint)
        } else {
            this.paint.style = Paint.Style.STROKE
            this.paint.strokeWidth = max(1f, sizePx * 0.09f)
            this.paint.color = outlineColor
            canvas.drawRoundRect(left, topEdge, right, bottomEdge, radius, radius, this.paint)
        }
    }
}

internal class AnchorLinkSpan(
    private val anchor: String,
    private val linkColor: Int,
    private val onAnchor: (String) -> Unit,
) : ClickableSpan() {

    override fun onClick(widget: View) {
        onAnchor(anchor)
    }

    override fun updateDrawState(ds: TextPaint) {
        ds.color = linkColor
    }
}
