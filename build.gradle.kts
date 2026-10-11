// Root Explorer Pro — root build script.
// Plugin versions are declared here with apply false so subprojects can apply them.
//
// AGP 9.x is required rather than optional on this machine: the AndroidX releases this
// project uses (Compose BOM 2026.09.00, core-ktx 1.19.1, lifecycle 2.11.0) publish Gradle
// metadata that refuses to build with AGP below 9.1.0.
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
