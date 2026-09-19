# R8 rules for the release build (isMinifyEnabled = true).

# ---- kotlinx.serialization (docs: kotlinx.serialization/docs/serialization-guide.md, "R8 / ProGuard") ----
# The API layer (net/) serializes and parses JSON with generated serializers. R8 cannot see that they are needed
# (they are looked up through Companion.serializer()), and without these rules it strips them: the debug build and the
# unit tests work, and the release app fails at the first pairing. Keep the generated serializers and companions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class app.nya.smsforward.node.**$$serializer { *; }
-keepclassmembers class app.nya.smsforward.node.** {
    *** Companion;
}
-keepclasseswithmembers class app.nya.smsforward.node.** {
    kotlinx.serialization.KSerializer serializer(...);
}
