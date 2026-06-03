# Keep MiMo Gson request/response models stable under R8. Gson reflects class
# structure and generic signatures when parsing API responses.
-keep class com.voxengine.engine.mimo.** { *; }
-keepattributes Signature,*Annotation*
