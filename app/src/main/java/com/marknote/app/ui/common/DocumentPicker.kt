package com.marknote.app.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

class OpenDocumentWithInitialUri(private val initial: Uri?) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).apply {
            initial?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }
}
