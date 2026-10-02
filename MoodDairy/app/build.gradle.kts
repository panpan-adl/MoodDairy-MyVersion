import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
    id("com.google.dagger.hilt.android") version "2.48"
}

fun quoteForBuildConfig(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

fun String.ensureTrailingSlash(): String = if (endsWith("/")) this else "$this/"

fun String.removeTrailingSlash(): String = trimEnd('/')

/** 避免 local.properties 中误写 http://127.0.0.1%3A8000/ 导致 Retrofit 解析 host 失败 */
fun String.normalizeHttpUrlEscapes(): String =
    trim().replace("%3A", ":").replace("%3a", ":")

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

// 模拟器默认 10.0.2.2:8000；USB 真机调试请执行 adb reverse tcp:8000 tcp:8000 后在 local.properties 中设置 api.base.url=http://127.0.0.1:8000/
val apiBaseUrl = (
    localProperties.getProperty("api.base.url")
        ?: providers.gradleProperty("api.base.url").orNull
        ?: "http://10.0.2.2:8000/"
).trim().normalizeHttpUrlEscapes().ensureTrailingSlash()

val mediaBaseUrl = (
    localProperties.getProperty("media.base.url")
        ?: providers.gradleProperty("media.base.url").orNull
        ?: apiBaseUrl
).trim().normalizeHttpUrlEscapes().removeTrailingSlash()

android {
    namespace = "com.example.mydiary"
    compileSdk = 36  // 提升到36以满足依赖要求

    defaultConfig {
        applicationId = "com.example.mydiary"
        minSdk = 26  // 提高到26以支持java.time API
        targetSdk = 35  // 使用Android 14目标SDK
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_BASE_URL", quoteForBuildConfig(apiBaseUrl))
        buildConfigField("String", "MEDIA_BASE_URL", quoteForBuildConfig(mediaBaseUrl))

        // Live2D 架构支持
        ndk {
            abiFilters += listOf(
                "armeabi-v7a",  // 32位 ARM
                "arm64-v8a",    // 64位 ARM（真机）
                "x86"           // 32位 x86（模拟器测试）
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }

    // 确保 assets 目录被正确识别
    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }
}

dependencies {
    // Live2D Cubism SDK
    implementation(files("libs/Live2DCubismCore.aar"))
    implementation(project(":Framework:framework"))

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended:1.7.5")
    
    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    
    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")
    
    // Retrofit & OkHttp (网络请求)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    
    // Coil (图片加载)
    implementation("io.coil-kt:coil-compose:2.5.0")
    
    // Room (本地数据库)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    
    // Hilt (依赖注入)
    implementation("com.google.dagger:hilt-android:2.48")
    ksp("com.google.dagger:hilt-compiler:2.48")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    
    // DataStore (轻量级存储)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // EncryptedSharedPreferences
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // CameraX (视频录制)；1.4.0+ 含 16KB 页对齐的 .so，满足 Android 15+ / Play 要求
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-video:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // ML Kit 人脸检测 + TFLite 表情分类（聊天页摄像头表情识别）
    // 2.17.0 起支持 FULLY_CONNECTED op v12（新版 TF 导出的 TFLite 需要）
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")

    // Media3 (音频播放)
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.media3:media3-common:1.2.1")
    
    // Testing
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
    testImplementation("io.mockk:mockk:1.13.9")
    
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
