# Renkin is open source, so renaming classes hides nothing and only makes crash logs unreadable
# without a per-build mapping file; Mihon and Morphe skip it too. Shrinking and optimisation stay on.
-dontobfuscate
# Crash logs keep real file names and line numbers.
-keepattributes SourceFile,LineNumberTable

# ARSCLib bundles its own XmlPull API and android.content.res classes, which share names with the
# framework's. Upstream kept them whole to fix errors after enabling R8.
-dontwarn org.xmlpull.v1.**
-dontnote org.xmlpull.v1.**
-keep class org.xmlpull.** { *; }
-keep class android.content.res** { *; }

# apksig builds its ASN.1 models reflectively; shrinking them breaks pack signing.
-keep class com.android.apksig.internal** { *; }

# Shrinking Ackpine made some antivirus scanners flag the APK as a trojan.
-keep class ru.solrudev.ackpine** { *; }
