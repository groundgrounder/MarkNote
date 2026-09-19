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

/**
 * 预览里被改写的两种 span：任务列表复选框、文档内锚点链接。
 *
 * 都放在这个文件里（而不是 MarkdownPreview.kt）：那边只负责把 Markwon 接进 Compose，
 * 这里只管「怎么画、点了怎么算」。
 */

/**
 * 任务列表的复选框：接管渲染文本里 `[ ]` / `[x]` 这三个字符的绘制。
 *
 * ⚠️ 用 [ReplacementSpan] 而**不是**把文本换成 `☐` / `☑`：替换文本会改变长度，
 * 后面所有 span 的偏移随之错位 —— 公式（JLatexMathNode）、图片、搜索高亮全都跟着歪，
 * 而且这类错位只在「文档里既有任务列表又有公式」时才现形，极难查。ReplacementSpan 只接管
 * 绘制，底层那三个字符原地不动，所有偏移保持不变。
 *
 * 尺寸与颜色都按预览字号和主题来：复选框要跟着预览字号缩放（设置里可调），
 * 否则大字号下会显得像个小点。
 */
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

/**
 * 文档内锚点链接（`[文字](#标题)`）。点它不该把 `#标题` 甩给浏览器（浏览器只会报错），
 * 而是让预览滚到对应标题 —— 复用大纲跳转那条路（见 EditorScreen 的 jumpToHeading）。
 *
 * 颜色显式设成链接色：Markwon 自己的链接是 `LinkSpan`（带主题色的 URLSpan），
 * 我们换掉了它的 span，就得自己把颜色接上，否则锚点链接会跟普通文字一个样。
 */
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
