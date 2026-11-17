import java.util.Properties

plugins {
    // AGP 9 起 Kotlin 支持已内置，不能再应用 org.jetbrains.kotlin.android
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * 签名信息从 keystore.properties 读，密码不写进构建脚本也就不会进版本库。
 *
 * 文件不存在时不创建签名配置——新克隆的仓库照样能构建，只是产出未签名 APK，
 * 不会因为缺密码就直接构建失败。
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.personal.roleplay"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.personal.roleplay"
        minSdk = 26
        targetSdk = 37
        // 升级包必须递增 versionCode，否则覆盖安装会被系统拒绝
        versionCode = 11
        versionName = "1.2.8"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// 内置 Kotlin 下 jvmTarget 默认取 android.compileOptions.targetCompatibility（17），无需显式设置

ksp {
    arg("room.schemaLocation", "${projectDir}/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // --- AndroidX 基础 ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // --- Compose（BOM 统一版本）---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- Room ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 自带 SQLite：Android 系统库没有 FTS5，记忆检索需要它
    implementation(libs.androidx.sqlite.bundled)

    // --- 网络：OkHttp + SSE 手写解析，不引额外 SSE 库 ---
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // --- 序列化 ---
    implementation(libs.kotlinx.serialization.json)

    // --- Key 加密存储 ---
    implementation(libs.androidx.security.crypto)

    // --- 图片加载 ---
    implementation(libs.coil.compose)
}
