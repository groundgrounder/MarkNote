package com.marknote.app.data

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class DocumentEncoding(
    val charset: Charset,
    val withBom: Boolean = false,
) {
    companion object {
        val UTF8 = DocumentEncoding(Charsets.UTF_8)
    }
}

data class DecodedText(val text: String, val encoding: DocumentEncoding)

object TextEncoding {

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

        return DecodedText(String(bytes, Charsets.UTF_8), DocumentEncoding.UTF8)
    }

    fun decodeTruncated(bytes: ByteArray): DecodedText {

        val bom = bomAt(bytes)
        val body = if (bom == null) bytes else bytes.copyOfRange(bom.second, bytes.size)
        val order = if (bom == null) candidates else listOf(bom.first)
        for (charset in order) {

            for (dropped in 0..3) {
                val usable = body.size - dropped
                if (usable <= 0) break
                strictDecode(body.copyOf(usable), charset)?.let {
                    return DecodedText(it, DocumentEncoding(charset, withBom = bom != null))
                }
            }
        }
        return DecodedText(String(body, Charsets.UTF_8), DocumentEncoding.UTF8)
    }

    fun encode(text: String, encoding: DocumentEncoding): ByteArray {
        val body = text.toByteArray(encoding.charset)
        if (!encoding.withBom) return body
        return bomBytes(encoding.charset) + body
    }

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

    private fun charsetOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()

    private fun ByteArray.startsWith(vararg prefix: Int): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it].toByte() }
    }
}
