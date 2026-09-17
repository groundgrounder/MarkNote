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

/**
 * 预览里两种「失败占位」的绘制：图片加载失败、公式渲染失败。
 *
 * 2026-09-16 从 MarkdownPreview.kt 拆出来，并去掉了两处**逐字重复的绘制骨架**：
 * 原先 imageErrorDrawable 与 latexErrorDrawable 各有约 30 行完全同构的代码
 * （换算 → TextPaint → ellipsize → 建 bitmap → 填充圆角矩形 → 描边圆角矩形 → 算基线画字），
 * 只有圆角、内边距、底色浓度、行高系数、宽高夹取、省略方向这几处不同。
 * 现在骨架只有 [placeholderDrawable] 一份，差异全收在 [PlaceholderBox] 里。
 *
 * 两者都必须自己画上**原文**：errorHandler 返回的 drawable 是**整体替换**该元素的绘制的，
 * 不画的话连「原本写了什么」都看不见了。
 */

/** dp → 像素 */
private fun dp(value: Float, context: Context): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

/** sp → 像素（顺带尊重用户的字体缩放） */
private fun sp(value: Float, context: Context): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, context.resources.displayMetrics)

/**
 * 占位框的像素参数。两个调用点只在这几个值上不同，绘制骨架完全共用。
 *
 * [maxWidth] 取 `Float.MAX_VALUE` 表示不限宽（公式占位只按屏宽限**文本**，不夹外框）。
 */
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

/**
 * 画一块「单行文字 + 浅底 + 描边框」的占位。
 *
 * 文字按 [PlaceholderBox.maxTextWidth] 省略后居中基线绘制；外框宽度取文字宽度 + 左右内边距，
 * 再夹到 [PlaceholderBox.minWidth] / [PlaceholderBox.maxWidth] 之间。
 */
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

/** 加载失败时顶替图片的占位：一个描边框 + 失败原因（单行，超出宽度省略） */
internal fun imageErrorDrawable(context: Context, color: Int, error: Throwable): Drawable {
    val reason = error.message?.takeIf { it.isNotBlank() }
        ?: context.getString(R.string.image_error_generic)
    val padH = dp(12f, context)
    val maxWidth = dp(260f, context)
    return placeholderDrawable(
        context = context,
        color = color,
        text = reason,
        // 图片那边的字号是固定的 12sp，不跟随预览字号
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

/**
 * 公式渲染失败时顶替公式的占位：错误色的公式源 + 描边框。
 *
 * 为什么必须自己接 errorHandler：默认（不接）时 ext-latex 只在
 * `catch (Throwable)` 里写一条 `Log.e("JLatexMathPlugin", "Error displaying latex: …")` 就结束，
 * 既不设置渲染结果、placeholder() 又返回 null —— 界面上虽然还留着公式源
 * （ReplacementSpan 的占位文本仍在，所以失败时不会变成一片空白），
 * 但**没有任何「这里出错了」的视觉信号**，用户只会以为「这个公式没渲染」。
 * 图片那边已经接了 errorHandler 显示失败原因，公式这边不对齐说不过去。
 *
 * 只画公式源、**不画失败原因**：公式多在行内，两行高的占位会把行距撑坏；
 * 而错误色的公式源本身就够指出「是哪条命令写坏了」。具体原因
 * （`Unknown symbol or command or predefined TeXFormula: 'xxx'`）仍写在 logcat 里。
 */
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
            // 行内公式的占位不能宽到把整行撑破 —— 撑破的后果是「同一段里后面的文字被挤到下一行」，
            // 看起来像段落断了。所以上限只取屏宽的七成，超长就中间省略：
            // 两头各留一点，好让「\begin{...} … \end{...}」这类长公式也能看出是哪一条
            maxTextWidth = context.resources.displayMetrics.widthPixels * 0.7f - padH * 2,
            truncateAt = TextUtils.TruncateAt.MIDDLE,
        ),
    )
}
