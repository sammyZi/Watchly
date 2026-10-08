plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.reel"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.reel"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // libtorrent4j and libVLC each ship a large .so per ABI: one APK per ABI.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            // R8: drops unused code, and with it most of the 1,665 Lucide icons (about 40 are used).
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

    buildFeatures { compose = true }

    // Native libraries stay uncompressed in the APK and are used from there. Legacy packaging
    // compressed them *and* unpacked a second ~66 MB copy on install.
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    val lt = "2.1.0-38"
    implementation("org.libtorrent4j:libtorrent4j:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:$lt")
    implementation("org.libtorrent4j:libtorrent4j-android-x86:$lt")
    implementation("org.videolan.android:libvlc-all:3.6.3")

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
}
