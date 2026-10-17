plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** SUBDL_KEY from tv/.env (the old Expo name still works), so the key stays out of git. */
val subdlKey: String = rootProject.file(".env").takeIf { it.exists() }?.readLines().orEmpty()
    .map { it.trim() }
    .firstOrNull { it.startsWith("SUBDL_KEY=") || it.startsWith("EXPO_PUBLIC_SUBDL_KEY=") }
    ?.substringAfter('=')?.trim().orEmpty()

android {
    namespace = "com.torrentviewer.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.torrentviewer.tv"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"
        buildConfigField("String", "SUBDL_KEY", "\"$subdlKey\"")
    }

    // libtorrent4j and libVLC each ship a large .so per ABI: one APK per ABI.
    // x86 is the television emulator.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86")
            isUniversalApk = false
        }
    }

    // The Expo build's key, so this installs over it in place.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // android.util.Log in shared code answers quietly on the JVM.
    testOptions { unitTests.isReturnDefaultValues = true }

    // Native libraries stay uncompressed in the APK and are mapped from there.
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    val lt = "2.1.0-38"
    implementation("org.libtorrent4j:libtorrent4j:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-x86:$lt")
    implementation("org.videolan.android:libvlc-all:3.6.3")
    // 1.9.x: Jellyfin's prebuilt FFmpeg audio decoder (AC3/E-AC3/DTS/TrueHD) is built against 1.9.0.
    val media3 = "1.9.4"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")

    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("com.composables:icons-lucide-android:2.2.1")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")

    testImplementation("junit:junit:4.13.2")
    // org.json is an Android stub on the JVM; the real one for unit tests.
    testImplementation("org.json:json:20240303")
}
