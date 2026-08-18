# Room generates classes referenced only reflectively at runtime.
-keep class pl.nightvox.data.db.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class pl.nightvox.** {
    *** Companion;
}
-keepclasseswithmembers class pl.nightvox.** {
    kotlinx.serialization.KSerializer serializer(...);
}
