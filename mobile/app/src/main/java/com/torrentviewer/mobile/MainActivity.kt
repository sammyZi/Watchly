package com.torrentviewer.mobile

import com.torrentviewer.core.Inbox
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.torrentviewer.core.AppScope
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Kv
import com.torrentviewer.core.Profiles
import com.torrentviewer.core.SettingsStore
import com.torrentviewer.core.TorrentStream
import com.torrentviewer.core.Watch
import com.torrentviewer.core.openParty
import com.torrentviewer.core.preferHighRefreshRate
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
        // Pictures 8 at a time from one host, not OkHttp's 5, with as many connections kept: metahub, where
        // posters come from, is one HTTP/1.1 server, ~0.6 s a picture here and 1-3 s on mobile data, so a
        // screen of posters came in slow waves of five. The same requests, only closer together.
        coil3.SingletonImageLoader.setSafe { ctx ->
            val client = okhttp3.OkHttpClient.Builder()
                .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 8 })
                .connectionPool(okhttp3.ConnectionPool(8, 5, java.util.concurrent.TimeUnit.MINUTES))
                .build()
            coil3.ImageLoader.Builder(ctx)
                .components { add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { client })) }
                .build()
        }
        Kv.init(context)
        SettingsStore.load()
        Profiles.load()
        com.torrentviewer.core.Simkl.clientId = BuildConfig.SIMKL_CLIENT_ID
        com.torrentviewer.core.Mdblist.clientId = BuildConfig.MDBLIST_CLIENT_ID
        com.torrentviewer.core.AniList.clientId = BuildConfig.ANILIST_CLIENT_ID
        com.torrentviewer.core.AniList.dir = context.filesDir
        com.torrentviewer.core.Tmdb.key = BuildConfig.TMDB_KEY
        com.torrentviewer.core.Party.server = BuildConfig.PARTY_URL
        // 6882: the TV (6881) and the Windows app (51413) may share the router.
        // lan: the stream is also served to the network, for a Cast receiver to fetch.
        engine = TorrentStream(context.applicationContext, port = 6882, lan = true)
        // Readable over adb (Android/data/<app>/files/engine.log): this phone keeps app logs out of logcat.
        engine.logFile = context.getExternalFilesDir(null)?.let { java.io.File(it, "engine.log") }
        // A film stopped part-way keeps its header, index and resume spot, so it opens at once next time.
        engine.keepStarts = true
        // Settings → Streaming → Engine: Nuvio's streams the file instead, read at each play.
        val app = context.applicationContext
        engine.external = { magnet, torrent, hash, index, name ->
            if (SettingsStore.value.value.nuvioEngine) NuvioTorrent.play(app, magnet, torrent, hash, index, name) else null
        }
        engine.externalStop = NuvioTorrent::stop
        com.torrentviewer.core.Party.log = { engine.note("party: $it") }
        // DHT bootstrapped while the viewer browses, not after they press Play.
        AppScope.launch(Dispatchers.IO) { engine.warmUp() }
        watch = Watch(engine)
        // A leader's Reopen puts its film back on, unless something is playing already.
        com.torrentviewer.core.Party.reopen = { m -> if (watch.phase == com.torrentviewer.core.Phase.IDLE) with(watch) { openParty(m) } }
        Downloads.init(context)
        EpisodeAlerts.schedule(context.applicationContext)
    }
}

/** Picture-in-picture, between the player (which knows whether a film is playing) and the activity. */
object Pip {
    var active by mutableStateOf(false)

    fun supported(context: Context) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
}

/** A FragmentActivity because the Cast button's device chooser is a dialog fragment. */
class MainActivity : FragmentActivity() {
    /** A magnet or .torrent handed to us by another app. */
    private val incoming = mutableStateOf<Uri?>(null)

    // The language picked in Settings, words and layout direction both; the phone's own when none is.
    override fun attachBaseContext(newBase: android.content.Context) {
        com.torrentviewer.core.I18n.ensure(newBase)
        super.attachBaseContext(com.torrentviewer.core.I18n.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Services.init(applicationContext)
        Ink.accent = accentOf(SettingsStore.value.value.accent)
        // The page before the first frame, so a light theme never opens on a flash of dark.
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        applyTheme(darkFor(SettingsStore.value.value.themeMode, systemDark))
        // AniList's sign-in coming back is not something to open.
        if (!com.torrentviewer.core.AniList.signIn(intent?.dataString)) incoming.value = intent?.data
        setContent {
            androidx.compose.foundation.layout.Box {
                App(Services.watch, incoming)
                FilmSplash(skip = incoming.value != null)
            }
        }
        preferHighRefreshRate()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!com.torrentviewer.core.AniList.signIn(intent.dataString)) incoming.value = intent.data
    }

    override fun onResume() {
        super.onResume()
        preferHighRefreshRate()
        com.torrentviewer.core.refreshWatched()
        Inbox.load()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        Pip.active = isInPictureInPictureMode
    }

    override fun onDestroy() {
        if (isFinishing) AppScope.launch(Dispatchers.IO) { Services.engine.shutdown() }
        super.onDestroy()
    }
}
