plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** From tv/.env, then the phone's mobile/.env, so keys stay out of git: the first of [names] set. */
fun env(vararg names: String): String = listOf(".env", "../mobile/.env").map(rootProject::file).filter { it.exists() }.flatMap { it.readLines() }
    .map { it.trim() }
    .firstOrNull { line -> names.any { line.startsWith("$it=") } }
    ?.substringAfter('=')?.trim().orEmpty()

// The old Expo name still works.
val subdlKey = env("SUBDL_KEY", "EXPO_PUBLIC_SUBDL_KEY")

android {
    namespace = "com.torrentviewer.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.torrentviewer.tv"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "2.4.0"
        buildConfigField("String", "SUBDL_KEY", "\"$subdlKey\"")
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"${env("SIMKL_CLIENT_ID")}\"")
        buildConfigField("String", "MDBLIST_CLIENT_ID", "\"${env("MDBLIST_CLIENT_ID")}\"")
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    }

    // libtorrent4j and libVLC each ship a large .so per ABI: one APK per ABI.
    // x86 is the television emulator.
    // -PuniversalApk adds one APK with every ABI, for the release page.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86")
            isUniversalApk = providers.gradleProperty("universalApk").isPresent
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
    // The QR beside Stremio's sign-in code: a remote can't type, a phone camera can read.
    implementation("com.google.zxing:core:3.5.3")
}
