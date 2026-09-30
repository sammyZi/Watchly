package expo.modules.vlcplayer

import expo.modules.kotlin.Promise
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class VlcPlayerModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("VlcPlayer")

    View(VlcPlayerView::class) {
      Events("onLoaded", "onProgress", "onBuffering", "onEnded", "onFailed", "onTracks")

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
      Prop("cacheMs") { view: VlcPlayerView, value: Int -> view.setCacheMs(value) }

      AsyncFunction("seekTo") { view: VlcPlayerView, seconds: Double ->
        view.seekTo((seconds * 1000).toLong())
      }

      AsyncFunction("reload") { view: VlcPlayerView, seconds: Double ->
        view.reload((seconds * 1000).toLong())
      }

      AsyncFunction("pause") { view: VlcPlayerView -> view.pause() }

      AsyncFunction("stats") { view: VlcPlayerView -> view.stats() }

      AsyncFunction("snapshot") { view: VlcPlayerView, promise: Promise ->
        view.snapshot(promise)
      }

      OnViewDestroys { view: VlcPlayerView -> view.release() }
    }
  }
}
