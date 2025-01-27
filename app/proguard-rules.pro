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
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# General options
#-dontshrink
#-dontoptimize
#-dontobfuscate

# Keep XmlParser, fix obfuscate errors
-dontwarn org.xmlpull.v1.**
-dontnote org.xmlpull.v1.**
-keep class org.xmlpull.** { *; }

# Shrink and obfuscate only Android libraries
-keep class com.kaanelloed.iconeration** { * ; }

# Fix resource errors
-keep class android.content.res** { * ; }

# Fix signing errors
-keep class com.android.apksig.internal** { * ; }

# Fix some antivirus trojan flag
-keep class ru.solrudev.ackpine** { * ; }

# For shrinking troubleshooting, check these files in "app/build/outputs/mapping/release/"
#   - "usage.txt" lists what ProGuard considers as dead code
#   - "seeds.txt" exhaustively lists classes and classes members matched by "keep" rules