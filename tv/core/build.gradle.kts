// What the TV and phone apps share: the torrent engine, the players, indexers and metadata,
// the store, and the look (Ink, Type, artwork, title marks). The phone's build includes this
// directory as its own :core, so it stays free of anything D-pad specific.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.torrentviewer.core"
    compileSdk = 37

    defaultConfig { minSdk = 24 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // android.util.Log in shared code answers quietly on the JVM.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val lt = "2.1.0-38"
    api("org.libtorrent4j:libtorrent4j:$lt")
    api("org.libtorrent4j:libtorrent4j-android-arm:$lt")
    api("org.libtorrent4j:libtorrent4j-android-arm64:$lt")
    api("org.libtorrent4j:libtorrent4j-android-x86:$lt")
    api("org.videolan.android:libvlc-all:3.6.3")
    // 1.9.x: Jellyfin's prebuilt FFmpeg audio decoder (AC3/E-AC3/DTS/TrueHD) is built against 1.9.0.
    val media3 = "1.9.4"
    api("androidx.media3:media3-exoplayer:$media3")
    api("androidx.media3:media3-ui:$media3")
    api("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")

    api(platform("androidx.compose:compose-bom:2026.06.01"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.animation:animation")
    api("androidx.compose.material3:material3")
    api("com.composables:icons-lucide-android:2.2.1")
    api("io.coil-kt.coil3:coil-compose:3.6.3")
    api("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    api("androidx.core:core-ktx:1.17.0")

    testImplementation("junit:junit:4.13.2")
    // org.json is an Android stub on the JVM; the real one for unit tests.
    testImplementation("org.json:json:20240303")
}
