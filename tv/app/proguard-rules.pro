# libtorrent4j is SWIG-generated: native code finds these classes and methods by name.
-keep class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**

# libvlcjni.so resolves its video-output callbacks by name over JNI; renamed, the
# release build dies the moment the first frame goes up.
-keep class org.videolan.** { *; }
-keep interface org.videolan.** { *; }
-dontwarn org.videolan.**

# DefaultRenderersFactory finds the FFmpeg audio renderer by reflection; its JNI by name.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
