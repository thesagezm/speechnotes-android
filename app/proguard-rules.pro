# Release minification rules. R8 was off entirely until 2026-10-24 — the
# universal APK shipped ~168 MB of native libs and a full unshrunk dex set.
# Rules here are only what the app itself needs beyond the consumer rules
# bundled with onnxruntime / mlkit / okhttp / coil / androidx.

# --- kotlinx.serialization -------------------------------------------------
# Generated serializers are looked up reflectively via the companion
# `serializer()`; keep them and the serializable classes' field names
# (the JSON field names are the iOS-sharing contract — see data/Persistence.kt).
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class ** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.speechnotes.app.**$$serializer { *; }
-keepclassmembers class com.speechnotes.app.** { *** Companion; }
-keepclassmembers class com.speechnotes.app.** { kotlinx.serialization.KSerializer serializer(...); }

# JSON is decoded via Persistence.json.decodeFromString<List<Note>>(raw) etc.;
# keep @Serializable classes themselves (R8 would otherwise rename fields).
-keep @kotlinx.serialization.Serializable class com.speechnotes.app.** { *; }

# --- pdfbox-android --------------------------------------------------------
# Loads font/resource configuration reflectively from assets.
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.**

# --- onnxruntime -----------------------------------------------------------
# JNI peers are looked up by name.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# --- okhttp / okio ---------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- misc ------------------------------------------------------------------
# Keep line numbers for the in-app Logs screen / logcat triage; obfuscate
# everything else.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
