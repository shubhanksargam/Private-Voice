# sherpa-onnx's Kotlin API is called from, and calls into, JNI. R8 must not
# rename or strip these or the native side fails to resolve them at runtime.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
