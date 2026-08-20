package com.torrentviewer.tv
import expo.modules.splashscreen.SplashScreenManager

import android.os.Build
import android.os.Bundle

import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

import androidx.mediarouter.media.MediaRouter

import com.reactnative.googlecast.api.RNGCCastContext

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
    // The TV is the cast *receiver*, so nothing here ever casts out. The
    // library still initialises because it is linked in, and a TV box without
    // Play Services throws from this call — on the phone that could not happen.
    runCatching { RNGCCastContext.getSharedInstance(this) }
    preferHighRefreshRate()
  }

  /**
   * Nothing to handle — registering it is the point.
   *
   * MediaRouter only scans the network while some callback has asked it to.
   * Normally that is Cast's own MediaRouteButton; this app draws its own cast
   * control, so without this the router stays passive, finds no receivers, and
   * the cast dialog opens onto an empty list.
   */
  private val routeCallback = object : MediaRouter.Callback() {}

  override fun onResume() {
    super.onResume()
    preferHighRefreshRate()
  }

  override fun onPause() {
    // Active scanning is expensive; it belongs to the foreground only.
    runCatching { MediaRouter.getInstance(this).removeCallback(routeCallback) }
    super.onPause()
  }

  override fun onDestroy() {
    if (isFinishing && !expo.modules.torrentstream.TorrentStreamModule.keepAlive()) {
      expo.modules.torrentstream.TorrentStreamModule.dismissNotify(this)
    }
    super.onDestroy()
  }

  private fun startCastDiscovery() {
    runCatching {
      val castContext = RNGCCastContext.getSharedInstance(this) ?: return
      // Null until the cast context finishes initialising — nothing to scan for
      // yet, and onResume runs again on the way back from anywhere.
      val selector = castContext.mergedSelector ?: return
      MediaRouter.getInstance(this).addCallback(
        selector,
        routeCallback,
        MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
      )
    }
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
