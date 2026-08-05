# ============================================================================
# FOXTRADER — ProGuard/R8 rules
# ============================================================================

# Keep Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers,allowshrinking class * { @kotlinx.serialization.SerialName <fields>; }

# Keep data models (serialized over network + Room)
-keep class com.foxtrader.app.domain.model.** { *; }
-keep class com.foxtrader.app.data.remote.dto.** { *; }
-keep class com.foxtrader.app.data.local.entity.** { *; }

# Hilt
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Retrofit
-keepattributes Signature, Exceptions
-keepclasseswithmembers class * { @retrofit2.http.* <methods>; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ---------------------------------------------------------------------------
# Defensive rules for the release (R8) build. Most libraries ship consumer
# rules, but these guard the reflection-driven paths that commonly break under
# full-mode R8 for this stack.
# ---------------------------------------------------------------------------

# Kotlinx Serialization: keep the synthetic companion serializer accessors and
# every @Serializable type's generated serializer, on both the type and its
# companion, so runtime serializer lookup never fails after shrinking.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Enums are frequently used inside serialized models and via valueOf/values().
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Retrofit service interfaces (methods carry HTTP annotations; keep signatures).
-keep interface retrofit2.** { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Room generated implementations.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**
