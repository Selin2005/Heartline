# Samsung Health Sensor SDK: binds to Health Sensor Service through AIDL/Parcelables.
-keep class com.samsung.android.service.health.** { *; }
-dontwarn com.samsung.android.service.health.**

# Keep line numbers for readable crash reports from device testing.
-keepattributes SourceFile,LineNumberTable
