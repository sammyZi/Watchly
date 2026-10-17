package com.torrentviewer.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ContextThemeWrapper
import androidx.core.content.FileProvider
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.common.images.WebImage
import java.io.File

/** The default media receiver: plays a URL, no receiver app of our own. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder().setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID).build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** Null without Play services — no Cast on this phone, and nothing to show for it. */
fun castContext(context: Context): CastContext? = runCatching {
    @Suppress("DEPRECATION")
    CastContext.getSharedInstance(context)
}.getOrNull()

/**
 * The Cast button. MediaRouter's device chooser is an AppCompat dialog, and this app's
 * theme is plain Material — so the button gets an AppCompat context of its own.
 */
fun castButton(context: Context): MediaRouteButton =
    MediaRouteButton(ContextThemeWrapper(context, androidx.appcompat.R.style.Theme_AppCompat_NoActionBar)).also {
        runCatching { CastButtonFactory.setUpMediaRouteButton(context.applicationContext, it) }
    }

private fun mimeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    "avi" -> "video/x-msvideo"
    else -> "video/mp4"
}

fun castRequest(url: String, name: String, title: String, art: String?, atSeconds: Double): MediaLoadRequestData {
    val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
        putString(MediaMetadata.KEY_TITLE, title)
        art?.let { addImage(WebImage(Uri.parse(it))) }
    }
    val info = MediaInfo.Builder(url).setStreamType(MediaInfo.STREAM_TYPE_BUFFERED).setContentType(mimeOf(name)).setMetadata(meta).build()
    return MediaLoadRequestData.Builder().setMediaInfo(info).setCurrentTime((atSeconds * 1000).toLong()).setAutoplay(true).build()
}

/**
 * Hand what is playing to another app — VLC, MX Player, anything that opens video. A stream
 * is an http address any app on the phone can fetch; a downloaded file goes as content://
 * with a read grant, as Android refuses file:// across apps. Position in VLC's convention.
 */
fun openExternally(context: Context, url: String, name: String, atSeconds: Double): Boolean {
    val local = url.startsWith("file://") || url.startsWith("/")
    val data = if (local) {
        val file = File(Uri.parse(url).path ?: url.removePrefix("file://"))
        runCatching { FileProvider.getUriForFile(context, "${context.packageName}.files", file) }.getOrNull() ?: return false
    } else {
        Uri.parse(url)
    }
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(data, mimeOf(name))
        putExtra("title", name)
        if (atSeconds > 0) {
            putExtra("position", (atSeconds * 1000).toLong())
            putExtra("from_start", false)
        }
        if (local) {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The chooser is a second intent; the grant travels with the clip.
            clipData = android.content.ClipData.newRawUri(name, data)
        }
    }
    val chooser = Intent.createChooser(view, "Play with").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (local) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    return runCatching { context.startActivity(chooser) }.isSuccess
}
