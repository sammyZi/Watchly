# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/Cellar/android-sdk/24.3.3/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

-keep class com.facebook.react.turbomodule.** { *; }

# Expo + libtorrent (JNI / reflection)
-keep class expo.modules.** { *; }
-keep class org.libtorrent4j.** { *; }
-keepclassmembers class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**
-keep class com.facebook.react.** { *; }

# libVLC. libvlcjni.so resolves the video-output callbacks by name over JNI —
# AWindowNativeHandler, onSurfacesCreated, dispatchEventFromNative. R8 renames
# them, and the lookup happens the moment VLC attaches to the surface, so the
# release build dies exactly when buffering ends and the first frame goes up.
-keep class org.videolan.** { *; }
-keep interface org.videolan.** { *; }
-dontwarn org.videolan.**
