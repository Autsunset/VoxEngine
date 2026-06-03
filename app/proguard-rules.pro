# Keep Gson model fields used by MiMo API JSON serialization/deserialization.
-keepclassmembers class com.voxengine.engine.mimo.** {
    <fields>;
}

-keepattributes Signature,*Annotation*
