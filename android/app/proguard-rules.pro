# Project-specific ProGuard rules. See https://developer.android.com/studio/build/shrink-code.
# Stub: minification is currently disabled. Add keep rules here when enabling R8 in release.

# Keep kotlinx-serialization generated serializers reachable.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class com.landolisp.**$$serializer { *; }
-keepclassmembers class com.landolisp.** {
    *** Companion;
}
-keepclasseswithmembers class com.landolisp.** {
    kotlinx.serialization.KSerializer serializer(...);
}
