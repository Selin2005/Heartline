# One configuration for every published build (dev, beta and stable are the same release build).
# R8 only shrinks the libraries: Heartline's own code is kept whole and no name is changed, so
# the Data Layer messages, serialization, Room, WorkManager, widgets and anything else found by
# name work exactly as in the tested code, and crash reports and logs stay readable.
-dontobfuscate
-keep class com.heartline.** { *; }
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# ONNX Runtime (ECG second opinion, PaPaGei): its native code finds these classes and their
# constructors by name, and the AAR ships no keep rules.
-keep class ai.onnxruntime.** { *; }
