# Keep Kotlinx Serialization metadata.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class com.dangeedums.acmeter.**$$serializer { *; }
-keepclassmembers class com.dangeedums.acmeter.** {
    *** Companion;
}
-keepclasseswithmembers class com.dangeedums.acmeter.** {
    kotlinx.serialization.KSerializer serializer(...);
}
