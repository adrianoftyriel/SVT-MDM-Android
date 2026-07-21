# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class org.svt.mdm.transport.dto.** { *; }
-keep,includedescriptorclasses class org.svt.mdm.**$$serializer { *; }
-keepclassmembers class org.svt.mdm.** {
    *** Companion;
}
-keepclasseswithmembers class org.svt.mdm.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# HiveMQ MQTT client relies on some optional deps; silence missing warnings.
-dontwarn io.reactivex.**
-dontwarn org.slf4j.**
