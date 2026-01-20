########################################
# Xerces XML
########################################
-keep class org.apache.xerces.** { *; }
-dontwarn org.apache.xerces.**
-dontwarn org.apache.xml.resolver.**

########################################
# AXML / Manifest Parsing (Reflection)
########################################
-keep class brut.** { *; }
-keep class com.apk.axml.** { *; }
-keep class apk.axml.** { *; }

########################################
# ZipSigner Library
########################################
-keep class kellinwood.security.zipsigner.** { *; }
-keep class kellinwood.zipio.** { *; }
-keep class kellinwood.logging.** { *; }

-dontwarn kellinwood.**

########################################
# Log4j optional dependencies
########################################
-dontwarn javax.mail.**
-dontwarn javax.jms.**
-dontwarn com.sun.jdmk.**
-dontwarn com.sun.jmx.**
-dontwarn org.apache.log4j.**

########################################
# SpongyCastle / BouncyCastle
########################################
-dontwarn org.spongycastle.**
-dontwarn org.bouncycastle.**

########################################
# General android/java internal suppressions
########################################
-dontwarn java.awt.**
-dontwarn javax.naming.**
