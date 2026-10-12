package com.marknote.app.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import com.marknote.app.R

private fun dp(value: Float, context: Context): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

private fun sp(value: Float, context: Context): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

private data class PlaceholderBox(
    val radius: Float,
    val padH: Float,
    val padV: Float,
    val fillAlpha: Int,
    val lineHeightFactor: Float,
    val minWidth: Float,
    val maxWidth: Float,
    val maxTextWidth: Float,
    val truncateAt: TextUtils.TruncateAt,
)

private fun placeholderDrawable(
    context: Context,
    color: Int,
    text: String,
    textSizePx: Float,
    box: PlaceholderBox,
): Drawable {
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        this.textSize = textSizePx
    }
    val shown = TextUtils.ellipsize(text, textPaint, box.maxTextWidth, box.truncateAt).toString()
    val width = (textPaint.measureText(shown) + box.padH * 2)
        .coerceIn(box.minWidth, box.maxWidth)
        .toInt()
    val height = (textSizePx * box.lineHeightFactor + box.padV * 2).toInt()

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val inset = dp(1f, context) / 2
    val left = inset
    val top = inset
    val right = width - inset
    val bottom = height - inset
    canvas.drawRoundRect(
        left, top, right, bottom, box.radius, box.radius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; alpha = box.fillAlpha },
    )
    canvas.drawRoundRect(
        left, top, right, bottom, box.radius, box.radius,
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = dp(1f, context)
        },
    )
    val baseline = height / 2f - (textPaint.ascent() + textPaint.descent()) / 2f
    canvas.drawText(shown, box.padH, baseline, textPaint)
    return BitmapDrawable(context.resources, bitmap)
}

internal fun imageErrorDrawable(context: Context, color: Int, error: Throwable): Drawable {
    val reason = error.message?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.image_error_generic)
    val padH = dp(12f, context)
    val maxWidth = dp(260f, context)
    return placeholderDrawable(
        context = context,
        color = color,
        text = reason,

        textSizePx = sp(12f, context),
        box = PlaceholderBox(
            radius = dp(6f, context),
            padH = padH,
            padV = 0f,
            fillAlpha = 0x1A,
            lineHeightFactor = 2.4f,
            minWidth = dp(120f, context),
            maxWidth = maxWidth,
            maxTextWidth = maxWidth - padH * 2,
            truncateAt = TextUtils.TruncateAt.END,
        ),
    )
}

internal fun latexErrorDrawable(
    context: Context,
    color: Int,
    textSizeSp: Int,
    latex: String,
): Drawable {
    val padH = dp(8f, context)
    return placeholderDrawable(
        context = context,
        color = color,
        text = latex,
        textSizePx = sp(textSizeSp.toFloat(), context),
        box = PlaceholderBox(
            radius = dp(4f, context),
            padH = padH,
            padV = dp(3f, context),
            fillAlpha = 0x14,
            lineHeightFactor = 1.5f,
            minWidth = dp(28f, context),
            maxWidth = Float.MAX_VALUE,

            maxTextWidth = context.resources.displayMetrics.widthPixels * 0.7f - padH * 2,
            truncateAt = TextUtils.TruncateAt.MIDDLE,
        ),
    )
}
