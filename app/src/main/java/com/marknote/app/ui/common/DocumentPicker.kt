package com.marknote.app.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 系统文档选择器，并把 [initial] 作为初始位置（EXTRA_INITIAL_URI）。
 *
 * 重新授权时把原文档 Uri 传进来，文件选择器会尽量停在原目录，用户少翻几层。
 * 注意：实例需用 remember 缓存，否则每次重组都会重建而触发 launcher 反复注册。
 */
class OpenDocumentWithInitialUri(private val initial: Uri?) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply {
            initial?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
}
