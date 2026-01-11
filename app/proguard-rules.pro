########################################
# Xerces XML (Full Keep – safest option)
########################################
-keep class org.apache.xerces.** { *; }
-dontwarn org.apache.xerces.**

########################################
# AXML / Manifest Parsing (Reflection)
########################################
-keep class brut.** { *; }
-keep class com.apk.axml.** { *; }
-keep class apk.axml.** { *; }

########################################
# Chaquopy Python Integration (If Used)
########################################
-keep class com.chaquo.python.** { *; }