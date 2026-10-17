package com.torrentviewer.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.torrentviewer.core.AppScope
import com.torrentviewer.core.Kv
import com.torrentviewer.core.Profiles
import com.torrentviewer.core.SettingsStore
import com.torrentviewer.core.TorrentStream
import com.torrentviewer.core.Watch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The engine and the session outlive the activity — rotation recreates nothing, but a config change still might. */
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
        // 6882: the TV (6881) and the Windows app (51413) may share the router.
        engine = TorrentStream(context.applicationContext, port = 6882)
        watch = Watch(engine)
    }
}

class MainActivity : ComponentActivity() {
    /** A magnet or .torrent handed to us by another app. */
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark bars over a dark page, whatever the system theme.
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Services.init(applicationContext)
        incoming.value = intent?.data
        setContent { App(Services.watch, incoming) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = intent.data
    }

    override fun onDestroy() {
        if (isFinishing) AppScope.launch(Dispatchers.IO) { Services.engine.shutdown() }
        super.onDestroy()
    }
}
