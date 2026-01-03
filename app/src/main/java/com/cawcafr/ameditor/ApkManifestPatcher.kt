package com.cawcafr.ameditor

import android.content.Context
import android.util.Log
import com.apk.axml.aXMLDecoder
import com.apk.axml.aXMLEncoder
import com.chaquo.python.Python
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream


class ApkManifestPatcher(private val context: Context) {

    private val TAG = "ApkManifestPatcher"

    private val TRACKERS = listOf(
        "com.google.android.gms.ads",
        "com.facebook.ads",
        "com.mopub.mobileads",
        "com.startapp.sdk",
        "com.applovin",
        "com.unity3d.ads",
        "com.google.firebase.analytics",
        "com.google.android.gms.measurement"
    )

    private val PERMISSIONS_TO_REMOVE = listOf(
        "android.permission.READ_PHONE_STATE",
        "android.permission.GET_ACCOUNTS",
        "com.google.android.gms.permission.AD_ID"
    )

    /**
     * Workflow complet : Extrait -> Décode -> Patch -> Encode -> Remplace
     */
    fun patchApkManifest(inputApk: File, outputApk: File): PatchResult {
        val workDir = File(context.cacheDir, "manifest_patch_${System.currentTimeMillis()}")

        try {
            workDir.mkdirs()

            Log.d(TAG, "Étape 1 : Extraction du manifest binaire...")
            val binaryManifest = File(workDir, "AndroidManifest.xml") // Le binaire original
            if (!extractManifestFromApk(inputApk, binaryManifest)) {
                return PatchResult.Error("Échec de l'extraction du manifest")
            }

            Log.d(TAG, "Étape 2 : Décodage (AXML -> XML Texte)...")
            val decodedXmlFile = File(workDir, "AndroidManifest_readable.xml")
            if (!decodeManifest(binaryManifest, decodedXmlFile)) {
                return PatchResult.Error("Échec du décodage du manifest")
            }

            Log.d(TAG, "Étape 3 : Application du Patch (Suppression traqueurs)...")
            // On modifie directement le fichier XML texte
            val stats = patchManifestXml(decodedXmlFile)

            Log.d(TAG, "Étape 4 : Encodage (XML Texte -> AXML)...")
            // On écrase le binaire original par le nouveau binaire généré
            val newBinaryManifest = File(workDir, "AndroidManifest_new_binary.xml")
            if (!encodeManifest(decodedXmlFile, newBinaryManifest)) {
                return PatchResult.Error("Échec de l'encodage du manifest")
            }

            Log.d(TAG, "Étape 5 : Remplacement dans l'APK...")
            if (!replaceManifestInApk(inputApk, newBinaryManifest, outputApk)) {
                return PatchResult.Error("Échec de la reconstruction de l'APK")
            }

            Log.d(TAG, "Succès ! APK patché créé.")
            return PatchResult.Success(outputApk, stats)

        } catch (e: Exception) {
            e.printStackTrace()
            return PatchResult.Error("Exception: ${e.message}")
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * ÉTAPE 1 : Extraire le AndroidManifest.xml (binaire) de l'APK
     */
    private fun extractManifestFromApk(apkFile: File, outputFile: File): Boolean {
        return try {
            ZipFile(apkFile).use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml")
                if (manifestEntry == null) {
                    Log.e(TAG, "Erreur : AndroidManifest.xml introuvable")
                    return false
                }

                zip.getInputStream(manifestEntry).use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * ÉTAPE 2 : Convertir AXML (Binaire) en XML (Texte)
     * Utilise la librairie aXMLDecoder
     */
    private fun decodeManifest(binaryFile: File, outputXmlFile: File): Boolean {
        return try {
            FileInputStream(binaryFile).use { fis ->
                val decoder = aXMLDecoder(fis) // Constructeur de la librairie
                val xmlString = decoder.decodeAsString() // Conversion magique
                outputXmlFile.writeText(xmlString)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erreur décodage AXML", e)
            false
        }
    }

    /**
     * ÉTAPE 3 : Modifier le fichier XML Texte (DOM Parser)
     */
    private fun patchManifestXml(xmlFile: File): PatchStats {
        // 1. Lire le contenu du fichier XML généré par aXMLDecoder
        val xmlContent = xmlFile.readText()

        // 2. Appeler le script Python
        val python = Python.getInstance()
        val pythonModule = python.getModule("manifest_patcher") // Le nom de ton fichier .py

        // Appel de la fonction 'process_manifest_content' définie dans le Python
        val result = pythonModule.callAttr("process_manifest_content", xmlContent).toString()

        // 3. Vérification des erreurs
        if (result.startsWith("ERROR_PYTHON")) {
            Log.e(TAG, "Erreur dans le script Python : $result")
            throw RuntimeException(result)
        }

        // 4. Écraser le fichier XML avec la version corrigée par Python
        xmlFile.writeText(result)

        // Note : Comme Python fait le travail, on ne peut pas facilement compter
        // les stats (removedCount) sans parser le retour JSON.
        // Pour l'instant on retourne des stats fictives ou on adapte le Python pour renvoyer du JSON.
        return PatchStats(1, 1)
    }

    /**
     * ÉTAPE 4 : Convertir XML (Texte) en AXML (Binaire)
     * Utilise la librairie aXMLEncoder. C'est ici que la magie opère sans Apktool.
     */
    private fun encodeManifest(xmlFile: File, outputBinaryFile: File): Boolean {
        return try {
            val xmlContent = xmlFile.readText()
            val encoder = aXMLEncoder()

            // Le context est nécessaire pour re-compiler les IDs (ex: @string/app_name)
            val binaryData = encoder.encodeString(xmlContent, context)

            FileOutputStream(outputBinaryFile).use { fos ->
                fos.write(binaryData)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erreur encodage XML vers AXML", e)
            false
        }
    }

    /**
     * ÉTAPE 5 : Remplacer le fichier dans l'APK (Zip manipulation)
     */
    private fun replaceManifestInApk(originalApk: File, newManifest: File, outputApk: File): Boolean {
        return try {
            ZipFile(originalApk).use { zipIn ->
                ZipOutputStream(FileOutputStream(outputApk)).use { zipOut ->
                    val entries = zipIn.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val entryName = entry.name

                        // On ignore le manifest original et les signatures (sinon l'apk est corrompu)
                        if (entryName == "AndroidManifest.xml" || entryName.startsWith("META-INF/")) {
                            continue
                        }

                        zipOut.putNextEntry(ZipEntry(entryName))
                        if (!entry.isDirectory) {
                            zipIn.getInputStream(entry).use { input -> input.copyTo(zipOut) }
                        }
                        zipOut.closeEntry()
                    }

                    // On ajoute notre NOUVEAU manifest
                    zipOut.putNextEntry(ZipEntry("AndroidManifest.xml"))
                    newManifest.inputStream().use { input -> input.copyTo(zipOut) }
                    zipOut.closeEntry()
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

data class PatchStats(val removedComponents: Int, val neutralizedConfigs: Int)

sealed class PatchResult {
    data class Success(val outputApk: File, val stats: PatchStats) : PatchResult()
    data class Error(val message: String) : PatchResult()
}