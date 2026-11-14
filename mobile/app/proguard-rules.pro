# libtorrent4j is SWIG-generated: native code finds these classes and methods by name.
-keep class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**

# libvlcjni.so resolves its video-output callbacks by name over JNI; renamed, the
# release build dies the moment the first frame goes up.
-keep class org.videolan.** { *; }
-keep interface org.videolan.** { *; }
-dontwarn org.videolan.**

# WorkManager (new-episode alerts) builds its Room database by reflection, constructor and all: stripped,
# the app died before its first screen with NoSuchMethodException on WorkDatabase_Impl.<init>.
-keep class androidx.work.impl.WorkDatabase_Impl { <init>(); }
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }

# DefaultRenderersFactory finds the FFmpeg audio renderer by reflection; its JNI by name.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
