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
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
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

    // Native libraries stay uncompressed in the APK and are mapped from there.
    packaging { jniLibs { useLegacyPackaging = false } }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
}
