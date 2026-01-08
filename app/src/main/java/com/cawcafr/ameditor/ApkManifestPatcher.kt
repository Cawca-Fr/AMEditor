package com.cawcafr.ameditor

import android.content.Context
import android.util.Log

// Imports aXML
import com.apk.axml.aXMLDecoder
import com.apk.axml.aXMLEncoder

import com.cawcafr.ameditor.util.ApkRebuilder
import com.cawcafr.ameditor.util.ManifestSanitizer // Import de la nouvelle classe
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile

class ApkManifestPatcher(private val context: Context) {

    private val TAG = "ApkManifestPatcher"

    fun patchApkManifest(inputApk: File, outputApk: File): PatchResult {
        val workDir = File(context.cacheDir, "patch_${System.currentTimeMillis()}")

        try {
            workDir.mkdirs()

            // 1. Extraction
            Log.d(TAG, "1. Extraction...")
            val binaryManifest = File(workDir, "AndroidManifest.xml")
            if (!extractManifestFromApk(inputApk, binaryManifest)) {
                return PatchResult.Error("Échec extraction manifest")
            }

            // 2. Décodage en Texte
            Log.d(TAG, "2. Décodage...")
            val xmlString = decodeManifestToString(binaryManifest)
            if (xmlString == null) {
                return PatchResult.Error("Impossible de décoder le manifest en texte")
            }

            // 3. Nettoyage via ManifestSanitizer (Remplace Python)
            Log.d(TAG, "3. Nettoyage du XML...")
            val cleanedXmlString = ManifestSanitizer.sanitize(xmlString)

            // 4. Encodage en Binaire
            Log.d(TAG, "4. Encodage...")
            val newBinaryManifest = File(workDir, "AndroidManifest_patched.xml")
            if (!encodeStringToAxml(cleanedXmlString, newBinaryManifest)) {
                return PatchResult.Error("Échec de l'encodage XML -> AXML")
            }

            // 5. Reconstruction APK
            Log.d(TAG, "5. Reconstruction APK...")
            ApkRebuilder.rebuildApk(inputApk, newBinaryManifest, outputApk)

            // (Stats simplifiées car le sanitizer gère les comptes en interne)
            return PatchResult.Success(outputApk, PatchStats(1, 1))

        } catch (e: Exception) {
            e.printStackTrace()
            return PatchResult.Error("Erreur: ${e.message}")
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * Utilise aXMLDecoder pour obtenir le texte complet
     */
    private fun decodeManifestToString(binaryFile: File): String? {
        return try {
            FileInputStream(binaryFile).use { fis ->
                val decoder = aXMLDecoder(fis)
                // Cette méthode existe dans ta librairie et renvoie tout le XML en String
                decoder.decodeAsString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur decodeAsString", e)
            null
        }
    }

    /**
     * Utilise aXMLEncoder pour repasser du String au Binaire
     */
    private fun encodeStringToAxml(xmlContent: String, outputFile: File): Boolean {
        return try {
            val encoder = aXMLEncoder()

            // Appel à encodeString(String, Context) qui est dispo dans ton fichier aXMLEncoder.java
            val binaryData = encoder.encodeString(xmlContent, context)

            FileOutputStream(outputFile).use { fos ->
                fos.write(binaryData)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Erreur encodeString", e)
            false
        }
    }

    private fun extractManifestFromApk(apkFile: File, outputFile: File): Boolean {
        return try {
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry("AndroidManifest.xml") ?: return false
                zip.getInputStream(entry).use { input ->
                    outputFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            true
        } catch (e: Exception) { false }
    }
}

// Data classes
data class PatchStats(val removedComponents: Int, val neutralizedConfigs: Int)

sealed class PatchResult {
    data class Success(val outputApk: File, val stats: PatchStats) : PatchResult()
    data class Error(val message: String) : PatchResult()
}