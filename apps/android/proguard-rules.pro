# Cortex-N × SAPIENT × AURA ProGuard Rules

# ============================================================================
# Keep native methods
# ============================================================================
-keepclasseswithmembernames class * {
    native <methods>;
}

# ============================================================================
# ONNX Runtime
# ============================================================================
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# ============================================================================
# ExecuTorch
# ============================================================================
-keep class org.pytorch.executorch.** { *; }
-dontwarn org.pytorch.executorch.**

# ============================================================================
# TensorFlow Lite
# ============================================================================
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ============================================================================
# Gson
# ============================================================================
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Keep data classes used with Gson
-keep class com.cortexn.app.policy.PromptSchemas** { *; }
-keep class com.cortexn.audiogen.PromptSchema** { *; }

# ============================================================================
# Kotlin Coroutines
# ============================================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ============================================================================
# Jetpack Compose
# ============================================================================
-keep class androidx.compose.runtime.** { *; }
-dontwarn androidx.compose.**

# ============================================================================
# AndroidX
# ============================================================================
-keep class androidx.lifecycle.** { *; }
-keep class androidx.navigation.** { *; }
-dontwarn androidx.lifecycle.**

# ============================================================================
# Remove logging in release
# ============================================================================
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

-assumenosideeffects class timber.log.Timber {
    public static *** d(...);
    public static *** v(...);
}

# ============================================================================
# Keep custom exceptions
# ============================================================================
-keep public class * extends java.lang.Exception

# ============================================================================
# Enum support
# ============================================================================
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ============================================================================
# Parcelable
# ============================================================================
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# ============================================================================
# Service classes
# ============================================================================
-keep class com.cortexn.app.services.** { *; }

# ============================================================================
# Reflection
# ============================================================================
-keepattributes *Annotation*,Signature,Exception,InnerClasses,EnclosingMethod

# ============================================================================
# Optimization
# ============================================================================
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-dontpreverify
