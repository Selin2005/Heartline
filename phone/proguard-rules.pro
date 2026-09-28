# Keep line numbers for readable crash reports from device testing.
-keepattributes SourceFile,LineNumberTable

# ONNX Runtime (ECG second opinion, PaPaGei): its native code finds these classes and their
# constructors by name, and the AAR ships no keep rules.
-keep class ai.onnxruntime.** { *; }
