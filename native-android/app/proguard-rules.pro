# kotlinx.serialization keeps generated serializers via its bundled rules.
# Room, WorkManager, Coil and OkHttp ship consumer rules.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
