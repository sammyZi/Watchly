package com.torrentviewer.tv

import com.torrentviewer.core.*
import coil3.request.*
import coil3.*
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
        com.torrentviewer.core.Simkl.clientId = BuildConfig.SIMKL_CLIENT_ID
        com.torrentviewer.core.Mdblist.clientId = BuildConfig.MDBLIST_CLIENT_ID
        // No client id: the TV is signed in to AniList from the phone, which has AniList's page.
        com.torrentviewer.core.AniList.dir = context.filesDir
        com.torrentviewer.core.Tmdb.key = BuildConfig.TMDB_KEY
        com.torrentviewer.core.Party.server = BuildConfig.PARTY_URL
        com.torrentviewer.core.ArtFadeMs = 0
        // Pictures are decoded two at a time, not four: a television has four slow cores, and a row
        // coming on screen took all of them just as the page needed one to move and one to draw.
        coil3.SingletonImageLoader.setSafe { coil3.ImageLoader.Builder(it).bitmapFactoryMaxParallelism(2).build() }
        engine = TorrentStream(context.applicationContext)
        // A film stopped part-way keeps its header, index and resume spot, so Continue Watching opens it at once
        // (16-20s from the swarm on this TV before), as on the phone.
        engine.keepStarts = true
        // Less than the phone's 1GB: a television's own storage is small, and shared with every other app on it.
        engine.keepStartsCap = 400L * 1024 * 1024
        com.torrentviewer.core.Party.log = { engine.note("party: $it") }
        watch = Watch(engine)
        // A leader's Reopen puts its film back on, unless something is playing already.
        com.torrentviewer.core.Party.reopen = { m -> if (watch.phase == com.torrentviewer.core.Phase.IDLE) watch.openParty(m) }
    }
}

class MainActivity : ComponentActivity() {
    /** A magnet or .torrent handed to us by another app. */
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Services.init(applicationContext)
        incoming.value = intent?.data
        setContent {
            androidx.compose.foundation.layout.Box {
                App(Services.watch, incoming)
                com.torrentviewer.core.Splash(skip = incoming.value != null)
            }
        }
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
        com.torrentviewer.core.refreshWatched()
    }

    override fun onDestroy() {
        if (isFinishing) AppScope.launch(Dispatchers.IO) { Services.engine.shutdown() }
        super.onDestroy()
    }
}
