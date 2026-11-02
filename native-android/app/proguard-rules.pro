# kotlinx.serialization keeps generated serializers via its bundled rules.
# Room, WorkManager, Coil and OkHttp ship consumer rules.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
# sherpa-onnx reads and fills these classes from JNI by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }
# commons-compress references optional codecs the app never uses (only bzip2 + tar are used).
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn org.apache.commons.lang3.**
