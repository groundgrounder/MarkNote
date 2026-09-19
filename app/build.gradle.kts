plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.marknote.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.marknote.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "1.5.0"
    }

    /**
     * 打包签名。
     *
     * 默认的 debug 签名是 AGP 在 `~/.android/debug.keystore` 不存在时**现场随机生成**的钥匙：
     * CI 每换一台新机器就是一把新的，于是每个 Release 包签名都不同，用户覆盖安装会被系统拒绝
     * （`INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`）。
     *
     * 所以这里固定用一把钥匙：`keystore/marknote.jks` 存在就用它（CI 从仓库 secret 还原，
     * 本地有这个文件也用），不存在才退回默认 debug 签名 —— 这样刚 clone 的机器上开发环境
     * 照样能构建，只是签出来的包不能与正式包互相覆盖。
     *
     * 这把钥匙只用于「sideload 用的 Debug 构建」，密码写在这里不成问题；将来若要上架商店，
     * 得另起一对 release 签名，并且不能与它共用。
     */
    signingConfigs {
        getByName("debug") {
            val keystoreFile = rootProject.file("keystore/marknote.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "marknote"
                keyAlias = "marknote"
                keyPassword = "marknote"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Markdown 渲染（现成库，View 体系，经 AndroidView 嵌入 Compose）
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:image:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    // LaTeX 公式（$$…$$ 行内、独占一行的 $$ 块级）。ext-latex 的行内公式是装在
    // inline-parser 上的，所以 inline-parser 必须一起引入
    implementation("io.noties.markwon:inline-parser:4.6.2")
    implementation("io.noties.markwon:ext-latex:4.6.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
