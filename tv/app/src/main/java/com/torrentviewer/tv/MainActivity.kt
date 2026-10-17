package com.torrentviewer.tv

import com.torrentviewer.core.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The engine and the session outlive the activity — a TV recreates it on HDMI and mode changes. */
object Services {
    lateinit var engine: TorrentStream
        private set
    lateinit var watch: Watch
        private set

    fun init(context: Context) {
        if (::engine.isInitialized) return
        Kv.init(context)
        SettingsStore.load()
        Profiles.load()
        engine = TorrentStream(context.applicationContext)
        watch = Watch(engine)
    }
}

class MainActivity : ComponentActivity() {
    /** A magnet or .torrent handed to us by another app. */
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Services.init(applicationContext)
        incoming.value = intent?.data
        setContent { App(Services.watch, incoming) }
        // Compose paints an opaque page over all of it; the window's own fill was wasted work.
        window.setBackgroundDrawable(null)
        preferHighRefreshRate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = intent.data
    }

    override fun onResume() {
        super.onResume()
        preferHighRefreshRate()
    }

    override fun onDestroy() {
        if (isFinishing) AppScope.launch(Dispatchers.IO) { Services.engine.shutdown() }
        super.onDestroy()
    }

    /** The panel's highest mode up to 120Hz at the current resolution. */
    private fun preferHighRefreshRate() {
        runCatching {
            @Suppress("DEPRECATION")
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display ?: return else windowManager.defaultDisplay
            val current = display.mode
            val best = display.supportedModes
                .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && it.refreshRate <= 120.5f }
                .maxByOrNull { it.refreshRate } ?: return
            window.attributes = window.attributes.apply {
                preferredDisplayModeId = best.modeId
            }
        }
    }
}
