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

# ONNX Runtime woła swoje klasy z natywnego kodu — R8 nie widzi tych referencji.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
