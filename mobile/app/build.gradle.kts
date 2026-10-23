plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** SUBDL_KEY from mobile/.env (the Expo name still works), so the key stays out of git. */
val subdlKey: String = rootProject.file(".env").takeIf { it.exists() }?.readLines().orEmpty()
    .map { it.trim() }
    .firstOrNull { it.startsWith("SUBDL_KEY=") || it.startsWith("EXPO_PUBLIC_SUBDL_KEY=") }
    ?.substringAfter('=')?.trim().orEmpty()

android {
    namespace = "com.torrentviewer.mobile"
    compileSdk = 37

    defaultConfig {
        // The Expo build's id and key (debug.keystore): a release installs over it in place
        // and imports its AsyncStorage on first launch.
        applicationId = "com.torrentviewer.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"
        buildConfigField("String", "SUBDL_KEY", "\"$subdlKey\"")
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
        manifestPlaceholders["appLabel"] = "Watchly"
    }

    // libtorrent4j and libVLC each ship a large .so per ABI: one APK per ABI.
    // -PuniversalApk adds one APK with every ABI, for the release page.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86")
            isUniversalApk = providers.gradleProperty("universalApk").isPresent
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // Beside the Expo app rather than over it, while the rewrite is short of parity.
        debug {
            applicationIdSuffix = ".next"
            manifestPlaceholders["appLabel"] = "Watchly Next"
            signingConfig = signingConfigs.getByName("debug")
        }
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
    // Google Cast: the Expo build's version, with MediaRouter's device chooser.
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    // Only for the AppCompat theme that chooser dialog insists on.
    implementation("androidx.appcompat:appcompat:1.7.1")
}
