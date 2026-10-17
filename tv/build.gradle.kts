plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
    // AGP 9 compiles Kotlin itself; this only puts KGP on the classpath to match the Compose plugin.
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
