package com.torrentviewer.mobile

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** Picture-in-picture, between the player (which knows whether a film is playing) and the activity. */
object Pip {
    var active by mutableStateOf(false)
    /** The window's params while a film plays, else null: Home then just leaves. */
    var params: (() -> PictureInPictureParams?)? = null

    fun supported(context: Context) =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
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

    /** Home while a film plays shrinks it to a window. Android 12+ does this itself from the params' auto-enter. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && Pip.supported(this)) {
            Pip.params?.invoke()?.let { runCatching { enterPictureInPictureMode(it) } }
        }
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
