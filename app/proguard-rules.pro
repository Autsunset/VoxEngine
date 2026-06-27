# Keep MiMo Gson request/response models stable under R8. Gson reflects class
# structure and generic signatures when parsing API responses.
-keep class com.voxengine.engine.mimo.** { *; }
-keepattributes Signature,*Annotation*

# RoleProfile / RoleVoiceStyle are reflected by Gson for the reader role-config
# round-trip in DataStore. R8 must not rename the class (the generic signature
# Map<String,RoleVoiceStyle> references it by original name) nor its fields.
-keep class com.voxengine.reader.RoleProfile { *; }
-keep class com.voxengine.reader.RoleVoiceStyle { *; }

# VoiceEntity is reflected by Gson during voice config import/export.
-keep class com.voxengine.data.VoiceEntity { *; }

