package com.marknote.app.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * 从 Compose 的 LocalContext 向上找到宿主 Activity。
 * Context 通常被包装了好几层（ContextThemeWrapper、我们自己的本地化包装等），
 * 所以要沿 baseContext 逐层找，不能只看最外层。
 */
fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
