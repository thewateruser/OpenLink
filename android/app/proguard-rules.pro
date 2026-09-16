# MVP: release builds are not minified (see app/build.gradle.kts, isMinifyEnabled = false),
# so these rules are currently inert. Kept as a starting point for when minification is
# enabled for a real release build.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class com.openlink.child.network.model.** {
    *** Companion;
}

# socket.io / engine.io
-keep class io.socket.** { *; }
-keep class org.json.** { *; }
