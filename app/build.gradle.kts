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
        versionCode = 21
        versionName = "1.6.1"
    }

    val keystoreFile = rootProject.file("keystore/marknote.jks")
    val aiStudioDebugKeystore = rootProject.file("debug.keystore")
    signingConfigs {
        getByName("debug") {
            when {
                aiStudioDebugKeystore.exists() -> {
                    storeFile = aiStudioDebugKeystore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
                keystoreFile.exists() -> {
                    storeFile = keystoreFile
                    storePassword = "marknote"
                    keyAlias = "marknote"
                    keyPassword = "marknote"
                }
            }
        }
        create("release") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = providers.environmentVariable("MARKNOTE_KEYSTORE_PASSWORD")
                    .orElse("marknote").get()
                keyAlias = providers.environmentVariable("MARKNOTE_KEY_ALIAS")
                    .orElse("marknote").get()
                keyPassword = providers.environmentVariable("MARKNOTE_KEY_PASSWORD")
                    .orElse("marknote").get()
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("release")
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

    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:image:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")

    implementation("io.noties.markwon:inline-parser:4.6.2")
    implementation("io.noties.markwon:ext-latex:4.6.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
