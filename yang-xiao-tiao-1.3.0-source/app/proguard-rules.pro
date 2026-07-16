# Skip App - ProGuard Rules

# Keep the accessibility service
-keep class com.skip.app.AdSkipService { *; }
-keep class com.skip.app.BootReceiver { *; }
-keep class com.skip.app.NotificationHelper { *; }

# Keep companion object fields for accessibility service
-keepclassmembers class com.skip.app.AdSkipService$Companion { *; }

# Keep Compose
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Keep data classes used for pattern matching
-keep class com.skip.app.AdSkipService$PatternEntry { *; }
