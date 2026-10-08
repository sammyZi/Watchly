# libtorrent4j is SWIG-generated: native code finds these classes and methods by name.
-keep class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**

# libVLC calls back into its Java classes from native code by name.
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**
