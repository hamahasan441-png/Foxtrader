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
