package com.torrentviewer.tv
import expo.modules.splashscreen.SplashScreenManager

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent

import com.facebook.react.bridge.Arguments
import com.facebook.react.modules.core.DeviceEventManagerModule

import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

import expo.modules.ReactActivityDelegateWrapper

class MainActivity : ReactActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    // Set the theme to AppTheme BEFORE onCreate to support
    // coloring the background, status bar, and navigation bar.
    // This is required for expo-splash-screen.
    // setTheme(R.style.AppTheme);
    // @generated begin expo-splashscreen - expo prebuild (DO NOT MODIFY) sync-f3ff59a738c56c9a6119210cb55f0b613eb8b6af
    SplashScreenManager.registerOnActivity(this)
    // @generated end expo-splashscreen
    super.onCreate(null)
    preferHighRefreshRate()
  }

  override fun onResume() {
    super.onResume()
    preferHighRefreshRate()
  }

  override fun onDestroy() {
    if (isFinishing && !expo.modules.torrentstream.TorrentStreamModule.keepAlive()) {
      expo.modules.torrentstream.TorrentStreamModule.dismissNotify(this)
    }
    super.onDestroy()
  }

  /** Prefer the panel's highest mode up to 120Hz when the device offers it. */
  private fun preferHighRefreshRate() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    try {
      val display =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display
        else windowManager.defaultDisplay
      val current = display.mode
      val best =
        display.supportedModes
          .filter {
            it.physicalWidth == current.physicalWidth &&
              it.physicalHeight == current.physicalHeight &&
              it.refreshRate <= 120.5f
          }
          .maxByOrNull { it.refreshRate } ?: return

      val params = window.attributes
      params.preferredDisplayModeId = best.modeId
      params.preferredRefreshRate = best.refreshRate.coerceAtMost(120f)
      window.attributes = params
    } catch (_: Exception) {
      // Some OEMs reject mode switches; stay on the system default.
    }
  }

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "main"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate {
    return ReactActivityDelegateWrapper(
          this,
          BuildConfig.IS_NEW_ARCHITECTURE_ENABLED,
          object : DefaultReactActivityDelegate(
              this,
              mainComponentName,
              fabricEnabled
          ){})
  }

  /**
   * Forward the remote's transport keys to JavaScript.
   *
   * Core React Native has no TV key API at all — `TVEventHandler` only exists
   * in the react-native-tvos fork — so without this the player could never hear
   * the ‹ › keys, and seeking would be reachable only by arrowing onto an
   * on-screen button first.
   *
   * Media keys are swallowed, because nothing else in this app wants them. The
   * D-pad is only *reported*: focus navigation still has to work on every other
   * screen, so those events must fall through to the focus system untouched.
   */
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    val name = when (event.keyCode) {
      KeyEvent.KEYCODE_MEDIA_REWIND -> "rewind"
      KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "forward"
      KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
      KeyEvent.KEYCODE_MEDIA_PLAY,
      KeyEvent.KEYCODE_MEDIA_PAUSE -> "playPause"
      KeyEvent.KEYCODE_MEDIA_NEXT -> "next"
      KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "previous"
      KeyEvent.KEYCODE_DPAD_LEFT -> "left"
      KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
      KeyEvent.KEYCODE_DPAD_UP -> "up"
      KeyEvent.KEYCODE_DPAD_DOWN -> "down"
      else -> null
    }

    if (name != null && event.action == KeyEvent.ACTION_DOWN) {
      emitKey(name, event.repeatCount)
      val navigational =
        name == "left" || name == "right" || name == "up" || name == "down"
      if (!navigational) return true
    }
    return super.dispatchKeyEvent(event)
  }

  /** Best effort — before the bridge is up there is no one to tell. */
  private fun emitKey(name: String, repeat: Int) {
    runCatching {
      val context =
        (application as MainApplication).reactHost.currentReactContext ?: return
      val payload = Arguments.createMap().apply {
        putString("key", name)
        putInt("repeat", repeat)
      }
      context
        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
        .emit("tvKey", payload)
    }
  }

  /**
    * Align the back button behavior with Android S
    * where moving root activities to background instead of finishing activities.
    * @see <a href="https://developer.android.com/reference/android/app/Activity#onBackPressed()">onBackPressed</a>
    */
  override fun invokeDefaultOnBackPressed() {
      if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R) {
          if (!moveTaskToBack(false)) {
              // For non-root activities, use the default implementation to finish them.
              super.invokeDefaultOnBackPressed()
          }
          return
      }

      // Use the default back button implementation on Android S
      // because it's doing more than [Activity.moveTaskToBack] in fact.
      super.invokeDefaultOnBackPressed()
  }
}
