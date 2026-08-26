package expo.modules.vlcplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * Where a headset's buttons land.
 *
 * An active MediaSession is enough on stock Android, but not everywhere: some
 * vendor ROMs pick the media-button target from the last app that registered a
 * *receiver* for it, and keep sending there regardless of who is playing. On
 * the phone this was found on, every press was still going to a video app that
 * had been closed for hours. Declaring a receiver and naming it on the session
 * is what puts this app in that running.
 *
 * It only forwards; the session's callback is still the one that decides what
 * a press means.
 */
class MediaButtons : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
    val key =
      if (android.os.Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
      }
    // Only the press. A headset sends down and up for one tap, and acting on
    // both toggles play straight back to where it started.
    if (key == null || key.action != KeyEvent.ACTION_DOWN) return
    android.util.Log.i("VlcPlayer", "media button ${key.keyCode}")
    VlcPlayerView.onMediaKey(key.keyCode)
  }
}
