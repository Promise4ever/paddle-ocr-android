# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# ONNX Runtime（JNI）
-keep class com.microsoft.onnxruntime.** { *; }
-dontwarn com.microsoft.onnxruntime.**
-dontwarn ai.onnxruntime.**

# OpenCV（JNI）
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Room（自带 consumer 规则，这里兜底）
-keep class * extends androidx.room.RoomDatabase { *; }
