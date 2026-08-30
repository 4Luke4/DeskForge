# JNI entry points are resolved by name from the native engine.
-keepclasseswithmembernames class * {
    native <methods>;
}

# BuildConfig is used by the diagnostics screen to report the exact build.
-keep class com.deskforge.app.BuildConfig { *; }
