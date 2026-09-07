# LiteRT-LM (Google AI Edge) — native bridge via JNI
-keep class com.google.ai.edge.litertlm.** { *; }

# Ktor / kotlinx.coroutines (reflection + atomicfu)
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Gson & serialization models: keep generic signatures and data classes serialized via reflection
-keep class com.localgpt.app.data.** { *; }
-keep class com.localgpt.app.localai.** { *; }
-keep class com.localgpt.app.core.server.** { *; }
-keep class com.localgpt.app.core.remote.** { *; }
-keep class com.localgpt.app.ui.chat.PersonaPreset { *; }
-keep class com.localgpt.app.ui.chat.BenchmarkResult { *; }
-keep class com.localgpt.app.skills.** { *; }
-keep class com.localgpt.app.web.** { *; }
-keep class com.localgpt.app.rag.** { *; }
-keep class com.localgpt.app.artifacts.** { *; }
-keep class com.materialkolor.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
