# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
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
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# BouncyCastle cryptography
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Bluetooth LE
-keep class no.nordicsemi.android.ble.** { *; }
-dontwarn no.nordicsemi.android.ble.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }

# Audio/MediaRecorder
-keep class android.media.** { *; }
-dontwarn android.media.**

# VoiceMesh specific classes
-keep class com.voicemesh.android.model.** { *; }
-keep class com.voicemesh.android.protocol.** { *; } 