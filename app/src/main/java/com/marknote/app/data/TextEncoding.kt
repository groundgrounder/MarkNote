package com.marknote.app.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文档的编码信息：读取时探测出来，写回时沿用同一种编码。
 *
 * 为什么需要它：markdown 文件不一定是 UTF-8。Windows 记事本、不少国产编辑器默认
 * GBK/GB2312，老一点的文档还可能是 Big5。若一律按 UTF-8 读，会先显示成乱码；
 * 用户一旦编辑保存，文件会被整体改写成 UTF-8 —— 原文不可逆损坏。
 *
 * [withBom] 单独记一笔，是因为 BOM（字节序标记）在正文里是零宽的 U+FEFF：留在文本里
 * 会让「首行是标题」这类行首匹配失效（`^#` 匹配不到 `\uFEFF#`），所以读进来要剥掉，
 * 写回去要补上，保持文件字节层面的原样。
 */
data class DocumentEncoding(
    val charset: Charset,
    val withBom: Boolean = false,
) {
    companion object {
        val UTF8 = DocumentEncoding(Charsets.UTF_8)
    }
}

/** 一次解码的结果：正文（已剥掉 BOM）与写回时要用的编码 */
data class DecodedText(val text: String, val encoding: DocumentEncoding)

/**
 * 编码探测与转换。
 *
 * 探测顺序刻意如此：
 * 1. **BOM 优先**——这是文件自己声明的编码，没有猜测成分，必须采信；
 * 2. **UTF-8 严格解码**——现代文件绝大多数是 UTF-8（纯 ASCII 也在此列），能过就不必再猜；
 * 3. **GB18030**——GBK / GB2312 的超集，覆盖绝大多数中文老文件；
 * 4. 都不成立时退回 UTF-8 容错解码，宁可让用户看到替换字符，也好过读不出内容。
 *
 * 为什么没有 Big5：GB18030 的双字节空间（首字节 0x81-0xFE、尾字节 0x40-0x7E/0x80-0xFE）
 * 完全覆盖 Big5 的编码空间，也就是**任何一份合法的 Big5 文件都能被 GB18030 成功解码**，
 * 无法靠"能不能解码"来区分。把 Big5 排在前面则会让大量 GBK 文件被误判（GBK 的实际使用
 * 规模远大于 Big5），代价更大。所以繁体老文件会显示成乱码，但因写回用的是同一个编码，
 * 文件不会被破坏（见下）。
 *
 * 关于探测错误的代价：读与写用的是同一个编码，而 GB18030 是字节序列与字符之间的一一映射，
 * 所以即便猜错，显示是乱码、文件字节却能原样往返，不会出现「打开看一眼就把文件改坏」。
 * 相比之下，「一律按 UTF-8 读、再按 UTF-8 写回」才是真正的破坏性做法——那才是这次要修的问题。
 */
object TextEncoding {

    /** 严格解码的候选编码，按优先级排列（BOM 已在 [decode] 里优先处理） */
    private val candidates: List<Charset> = listOfNotNull(
        Charsets.UTF_8,
        charsetOrNull("GB18030"),
    )

    fun decode(bytes: ByteArray): DecodedText {
        bomAt(bytes)?.let { (charset, bomLength) ->
            val text = String(bytes, bomLength, bytes.size - bomLength, charset)
            return DecodedText(text, DocumentEncoding(charset, withBom = true))
        }
        for (charset in candidates) {
            strictDecode(bytes, charset)?.let { return DecodedText(it, DocumentEncoding(charset)) }
        }
        // 所有严格解码都失败：至少把内容交给用户，写回时按 UTF-8（与旧行为一致）
        return DecodedText(String(bytes, Charsets.UTF_8), DocumentEncoding.UTF8)
    }

    /**
     * 解码「可能被切在半截上的一段字节」，用于列表摘要（只读前若干字节）。
     *
     * 与 [decode] 的区别：这里的末尾很可能是被切开的半个多字节字符，逐字节严格解码必然
     * 失败。所以每个候选编码都额外容忍丢掉末尾 1~3 个残字节再试——既能让 GBK 摘要正确
     * 显示（否则列表里是乱码，和编辑器对不上），也顺带解决了「按固定字节数截断时摘要
     * 末尾出现「�」」的问题。
     */
    fun decodeTruncated(bytes: ByteArray): DecodedText {
        for (charset in candidates) {
            for (dropped in 0..3) {
                val usable = bytes.size - dropped
                if (usable <= 0) break
                strictDecode(bytes.copyOf(usable), charset)?.let {
                    return DecodedText(it, DocumentEncoding(charset))
                }
            }
        }
        return DecodedText(String(bytes, Charsets.UTF_8), DocumentEncoding.UTF8)
    }

    fun encode(text: String, encoding: DocumentEncoding): ByteArray {
        val body = text.toByteArray(encoding.charset)
        if (!encoding.withBom) return body
        return bomBytes(encoding.charset) + body
    }

    /** 识别开头的 BOM，返回（去掉 BOM 的字节编码，BOM 长度） */
    private fun bomAt(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.startsWith(0xEF, 0xBB, 0xBF) -> Charsets.UTF_8 to 3
        bytes.startsWith(0xFF, 0xFE) -> Charsets.UTF_16LE to 2
        bytes.startsWith(0xFE, 0xFF) -> Charsets.UTF_16BE to 2
        else -> null
    }

    private fun bomBytes(charset: Charset): ByteArray = when (charset) {
        Charsets.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        Charsets.UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        Charsets.UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        else -> ByteArray(0)
    }

    /** 整段解码，遇到非法字节立刻放弃（用于判断"这份字节到底是不是这种编码"） */
    private fun strictDecode(bytes: ByteArray, charset: Charset): String? {
        if (bytes.isEmpty()) return if (charset == Charsets.UTF_8) "" else null
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** 设备不一定提供某个字符集，取不到就跳过，别让探测本身变成崩溃点 */
    private fun charsetOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it].toByte() }
    }
}
