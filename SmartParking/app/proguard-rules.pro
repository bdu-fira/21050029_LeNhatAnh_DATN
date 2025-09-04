# app/proguard-rules.pro

# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

# Keep generic signature of Call, Response (R8 full mode strips signatures from non-kept items).
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# With R8 full mode generic signatures are stripped for classes that are not
# kept. Suspend functions are wrapped in continuations where the type argument
# is used.
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions
-keepattributes RuntimeVisibleAnnotations
-keepattributes RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Data models - Keep all model classes
-keep class com.example.parkingmobiapp.models.** { *; }
-keep class com.example.parkingmobiapp.api.** { *; }

# Keep custom classes used with Gson
-keep class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep Parcelable classes
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Serializable classes
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# AndroidX
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-dontwarn androidx.**

# Material Design
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# WorkManager (only if using WorkManager - uncomment if needed)
# -keep class * extends androidx.work.Worker
# -keep class * extends androidx.work.InputMerger
# -keep class androidx.work.impl.background.systemalarm.RescheduleReceiver

# EventBus (only if using EventBus - uncomment if needed)
# -keepattributes *Annotation*
# -keepclassmembers class * {
#     @org.greenrobot.eventbus.Subscribe <methods>;
# }
# -keep enum org.greenrobot.eventbus.ThreadMode { *; }

# Firebase (only if using Firebase - uncomment if needed)
# -keep class com.google.firebase.** { *; }
# -keep class com.google.android.gms.** { *; }
# -dontwarn com.google.firebase.**
# -dontwarn com.google.android.gms.**

# WebSocket (only if using WebSocket - uncomment if needed)
# -keep class org.java_websocket.** { *; }
# -keep interface org.java_websocket.** { *; }

# Timber (only if using Timber - uncomment if needed)
# -dontwarn org.jetbrains.annotations.**

# Application specific classes
-keep class com.example.parkingmobiapp.MainActivity { *; }
-keep class com.example.parkingmobiapp.SplashActivity { *; }
-keep class com.example.parkingmobiapp.SettingsActivity { *; }
-keep class com.example.parkingmobiapp.NotificationHistoryActivity { *; }

# Keep services
-keep class com.example.parkingmobiapp.services.** { *; }

# Keep receivers (if any)
-keep class com.example.parkingmobiapp.receivers.** { *; }

# Keep utils
-keep class com.example.parkingmobiapp.utils.** { *; }

# Keep notification click handlers
-keepclassmembers class * {
    public void onClick*(...);
    public void on*Click*(...);
}

# Keep all native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep setters in Views so that animations can still work.
-keepclassmembers public class * extends android.view.View {
    void set*(***);
    *** get*();
}

# Keep Activity subclasses
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference

# Keep custom views
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep View constructors
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep enum fields
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Remove debug logs in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# Remove Timber logs in release (only if using Timber - uncomment if needed)
# -assumenosideeffects class timber.log.Timber* {
#     public static *** v(...);
#     public static *** d(...);
#     public static *** i(...);
#     public static *** w(...);
#     public static *** e(...);
# }

# Optimization settings
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
-dontpreverify

# Keep line numbers for better crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile