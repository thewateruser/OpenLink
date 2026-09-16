// Top-level build file: declares plugin versions once so app/build.gradle.kts can apply
// them without repeating a version number.
//
// Kotlin is on 2.x (rather than the 1.9.x this project started on) because the embedded Ktor
// server needs Ktor 3.x -- Ktor 3 artifacts carry Kotlin 2.0 metadata, which a 1.9 compiler
// refuses to read. Kotlin 2.x also moves the Compose compiler out of `composeOptions` and into
// its own plugin, applied below.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.21" apply false
    id("com.google.devtools.ksp") version "2.1.21-2.0.1" apply false
}
