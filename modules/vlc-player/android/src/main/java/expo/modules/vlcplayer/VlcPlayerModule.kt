package expo.modules.vlcplayer

import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class VlcPlayerModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("VlcPlayer")

    // Synchronous, for the AppState handler: going into PiP pauses the activity,
    // and that must not read as leaving the app.
    Function("isInPip") {
      android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
        appContext.currentActivity?.isInPictureInPictureMode == true
    }

    View(VlcPlayerView::class) {
      // `onRemote` carries a headset or car-stereo button press up to React, so
      // the overlay's own paused state follows the same truth the media session
      // just acted on.
      Events(
        "onLoaded",
        "onProgress",
        "onBuffering",
        "onEnded",
        "onFailed",
        "onTracks",
        "onRemote",
        "onPip"
      )

      Prop("autoPip") { view: VlcPlayerView, value: Boolean -> view.setAutoPip(value) }

      /** Shown on the playback notification and the lock screen. */
      Prop("title") { view: VlcPlayerView, value: String? -> view.setTitle(value) }

      /** The poster for that notification: a web address or a file on disk. */
      Prop("artwork") { view: VlcPlayerView, value: String? -> view.setArtwork(value) }

      AsyncFunction("enterPip") { view: VlcPlayerView -> view.enterPip() }

      AsyncFunction("stats") { view: VlcPlayerView -> view.stats() }

      Prop("source") { view: VlcPlayerView, value: String? -> view.setSource(value) }
      Prop("paused") { view: VlcPlayerView, value: Boolean -> view.setPaused(value) }
      Prop("rate") { view: VlcPlayerView, value: Float -> view.setRate(value) }

      // Read once when the media loads, so it must arrive before `source`
      // does its work. Expo applies props in declaration order on first mount.
      Prop("startPosition") { view: VlcPlayerView, value: Double ->
        view.setStartPosition((value * 1000).toLong())
      }
      Prop("subtitleTrack") { view: VlcPlayerView, value: Int? -> view.setSubtitleTrack(value) }
      Prop("audioTrack") { view: VlcPlayerView, value: Int? -> view.setAudioTrack(value) }
      Prop("subtitleFiles") { view: VlcPlayerView, value: List<String>? -> view.setSubtitleFiles(value) }
      Prop("scaleType") { view: VlcPlayerView, value: String? -> view.setScaleType(value) }
      Prop("subtitleDelay") { view: VlcPlayerView, value: Int -> view.setSubtitleDelay(value) }
      // Text-renderer options, read when a LibVLC instance is made. setSource posts
      // its load, so the first one already sees these from the same prop batch;
      // a later change restarts playback in place (see VlcPlayerView.restyle).
      Prop("subtitleSize") { view: VlcPlayerView, value: Int -> view.subtitleSize = value }
      Prop("subtitleColor") { view: VlcPlayerView, value: Int -> view.subtitleColor = value }
      Prop("subtitleBackground") { view: VlcPlayerView, value: Boolean -> view.subtitleBackground = value }

      AsyncFunction("seekTo") { view: VlcPlayerView, seconds: Double ->
        view.seekTo((seconds * 1000).toLong())
      }

      AsyncFunction("reload") { view: VlcPlayerView, seconds: Double ->
        view.reload((seconds * 1000).toLong())
      }

      AsyncFunction("pause") { view: VlcPlayerView -> view.pause() }

      AsyncFunction("snapshot") { view: VlcPlayerView, promise: Promise ->
        view.snapshot(promise)
      }

      OnViewDestroys { view: VlcPlayerView -> view.release() }
    }
  }
}
