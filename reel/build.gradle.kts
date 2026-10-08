plugins {
    id("com.android.application") version "9.3.1" apply false
    // AGP 9 compiles Kotlin itself; this only puts KGP 2.4.10 on the classpath so it
    // matches the Compose compiler plugin below.
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
