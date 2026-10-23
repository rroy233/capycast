import java.util.Properties

/** 用户可见的语义化版本号，发布时按实际改动更新。 */
val APP_VERSION_NAME = "0.1.3-beta.3"

/** Android 用于比较更新顺序的整数版本号，每次发布 APK 都必须递增。 */
val APP_VERSION_CODE = 10

/** 支持正式版与 Beta 预发布版本的语义化版本号格式。 */
private val semanticVersionPattern = Regex("^\\d+\\.\\d+\\.\\d+(?:-beta\\.\\d+)?$")

require(semanticVersionPattern.matches(APP_VERSION_NAME)) {
    "APP_VERSION_NAME must follow MAJOR.MINOR.PATCH or MAJOR.MINOR.PATCH-beta.N"
}
require(APP_VERSION_CODE > 0) {
    "APP_VERSION_CODE must be a positive integer"
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.roy233.tvcast"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.roy233.tvcast"
        minSdk = 21
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = APP_VERSION_CODE
        versionName = APP_VERSION_NAME
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        buildConfigField("String", "UPDATE_API_BASE_URL", "\"https://api.roy233.com\"")
        buildConfigField("String", "UPDATE_APP_ID", "\"capycast\"")
    }

    /** 读取未纳入版本控制的发布签名配置。 */
    val releaseKeystoreProperties = Properties().apply {
        val propertiesFile = rootProject.file("keystore.properties")
        if (propertiesFile.isFile) {
            propertiesFile.inputStream().use { input -> load(input) }
        }
    }

    signingConfigs {
        create("release") {
            if (releaseKeystoreProperties.isNotEmpty()) {
                storeFile = file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            // 解析层会记录 Android 日志；本地 JVM 测试无需真实 Logcat。
            isReturnDefaultValues = true
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // Local copy of the official libVLC 3.6.5 AAR (includes DASH demuxer).
    implementation(files("libs/libvlc-all-3.6.5.aar"))
    // DanmakuFlameMaster 0.9.25 AAR (Apache-2.0), the engine used by 云视听.
    implementation(files("libs/DanmakuFlameMaster-0.9.25.aar"))
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.brotli:dec:0.1.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")

    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("io.github.kyant0:backdrop:1.0.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
}

/** 输出工作流校验与发布所需的版本元数据。 */
tasks.register("printReleaseMetadata") {
    doLast {
        println("versionName=$APP_VERSION_NAME")
        println("versionCode=$APP_VERSION_CODE")
    }
}
