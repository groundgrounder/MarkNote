package com.marknote.app

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import com.marknote.app.data.AppLocaleStore
import java.util.Locale

/**
 * 让 applicationContext 的资源跟随应用内语言设置。
 *
 * Application 实例一个进程只创建一次，而语言可以在运行中切换，所以不能像 Activity 那样
 * 在 attachBaseContext 里包一次就完事（包完就固定了，切换语言后 applicationContext
 * 仍是旧语言，Repository 层的提示文案会不对）。这里改为覆写 getResources()：
 * 每次取资源时按当前偏好解析并缓存，切换语言后立刻生效。
 *
 * Activity 侧的包装见 MainActivity.attachBaseContext —— 两处都需要，缺一不可。
 */
class MarkNoteApplication : Application() {

    private var localizedResources: Resources? = null
    private var localizedTag: String? = null

    override fun onCreate() {
        super.onCreate()
        // 进程启动时先解析一次当前语言（API 33+ 要问系统「按应用语言」）。
        // 不放在 getResources() 里懒加载，是因为那个方法可能在 Application 初始化完成前
        // 就被调用，那时系统服务还取不到。
        AppLocaleStore.refresh(this)
    }

    override fun getResources(): Resources {
        val tag = AppLocaleStore.current(this).tag
        // 跟随系统：不包装，用系统给的资源
        if (tag.isEmpty()) return super.getResources()
        if (localizedResources == null || localizedTag != tag) {
            val locale = Locale.forLanguageTag(tag)
            // 基线取未包装的系统配置，避免在已本地化的配置上反复叠加
            val config = Configuration(super.getResources().configuration).apply {
                setLocale(locale)
                setLayoutDirection(locale)
            }
            localizedResources = createConfigurationContext(config).resources
            localizedTag = tag
        }
        return localizedResources!!
    }
}
