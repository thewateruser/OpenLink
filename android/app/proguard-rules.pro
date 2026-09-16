# MVP: release builds are not minified (see app/build.gradle.kts, isMinifyEnabled = false),
# so these rules are currently inert. Kept as a starting point for when minification is
# enabled for a real release build -- Ktor and kotlinx.serialization both need help from R8.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class com.openlink.child.server.** {
    *** Companion;
}
-keepclasseswithmembers class com.openlink.child.prefs.PairedParent {
    *** Companion;
}
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** serializer(...);
}

# Ktor server: engines and plugins are resolved reflectively / via ServiceLoader.
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**

# Ktor logs through SLF4J; no binding is shipped, which is a warning rather than an error.
-dontwarn org.slf4j.**

# ZXing (QR encoding only).
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
