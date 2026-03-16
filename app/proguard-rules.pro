########################################
# Xerces XML
########################################
-keep class org.apache.xerces.** { *; }
-dontwarn org.apache.xerces.**
-dontwarn org.apache.xml.resolver.**

# Supprime tous les appels à Log.d (Debug) et Log.v (Verbose) dans la version finale
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}
########################################
# AXML / Manifest Parsing (Reflection)
########################################
-keep class brut.** { *; }
-keep class com.apk.axml.** { *; }
-keep class apk.axml.** { *; }

# Obfuscation du code AMEditor
-keep class com.cawcafr.ameditor.MainActivity
-keep class com.cawcafr.ameditor.XmlPreviewActivity
-keep class com.cawcafr.ameditor.util.CustomPatchActivity


# Garder les classes de LSParanoid
-keep class org.lsposed.lsparanoid.** { *; }         # ✅ corrigé
-keep @org.lsposed.lsparanoid.Obfuscate class * { *; } # ✅ corrigé
-dontwarn org.lsposed.lsparanoid.**
# 2. On permet à R8 de renommer et déplacer vos autres classes (logic, utils, etc.)
#    Cela forcera l'obfuscation de classes comme TrackersList, AxmlManager, etc.
#    Elles seront renommées en a, b, c...
-repackageclasses ''
-allowaccessmodification

# 3. On évite de garder les noms des classes pour les exceptions (plus sécurisé)
-keepattributes !SourceFile,!LineNumberTable

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
