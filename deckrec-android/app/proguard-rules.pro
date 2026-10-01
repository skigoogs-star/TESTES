-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.deckrec.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.deckrec.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
