# Regole ProGuard del jar desktop di release (packageUniversalJar), in aggiunta a quelle di Compose.

# Ktor: RfiClient e FnmClient creano HttpClient() senza engine, che arriva via ServiceLoader.
-keep class * implements io.ktor.client.HttpClientEngineContainer { *; }

# kotlinx.serialization: cache, impostazioni e risposte FNM.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class **$$serializer { *; }

# Nucleus e Skiko: il codice nativo richiama classi e metodi Kotlin per nome.
-keep class dev.nucleusframework.** { *; }
-keep class org.jetbrains.skiko.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# La specializzazione dei tipi restringe il ritorno dei metodi nelle facciate Kotlin multi-file
# (Okio__JvmOkioKt, ParagraphKt__ActualParagraph_skikoKt…) e la JVM rifiuta il risultato con
# VerifyError "Bad return type". Le altre ottimizzazioni restano attive.
-optimizations !method/specialization/returntype,!method/specialization/parametertype

# JLayer: decoder MP3 del jingle degli annunci.
-keep class javazoom.** { *; }

# Dipendenze opzionali che non ci sono a runtime.
-dontwarn org.slf4j.**
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.debug.**
-dontwarn org.graalvm.**

# MapLibre Compose: il motore nativo richiama classi Kotlin con la Foreign Function API, LWJGL
# carica le sue native per nome.
-keep class org.maplibre.** { *; }
-keep class org.lwjgl.** { *; }
-dontwarn org.maplibre.**
-dontwarn org.lwjgl.**
