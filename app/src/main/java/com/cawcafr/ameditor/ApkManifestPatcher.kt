package com.cawcafr.ameditor

import android.content.Context
import android.util.Log
import com.apk.axml.aXMLDecoder
import com.apk.axml.aXMLEncoder
import com.cawcafr.ameditor.util.ApkRebuilder
import com.cawcafr.ameditor.util.CustomPatchData
import com.cawcafr.ameditor.util.ManifestSanitizer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile

class ApkManifestPatcher(private val context: Context) {

    private val TAG = "ApkManifestPatcher"

    fun patchApkManifest(
        inputApk: File,
        outputApk: File,
        logCallback: (String) -> Unit = {}
    ): PatchResult {

        val workDir = File(context.cacheDir, "patch_${System.currentTimeMillis()}")

        try {
            workDir.mkdirs()

            // Step 1
            logCallback("Step 1: Extracting AndroidManifest.xml...")
            val binaryManifest = File(workDir, "AndroidManifest.xml")
            if (!extractManifestFromApk(inputApk, binaryManifest)) {
                return PatchResult.Error("Manifest extraction failed")
            }

            // Step 2
            logCallback("Step 2: Decoding: AXML -> XML...")
            val xmlString = decodeManifestToString(binaryManifest)
            if (xmlString == null) {
                return PatchResult.Error("Failed to decode AXML")
            }

            // Step 3
            logCallback("Step 3: Patching...")
            val cleanedXmlString = ManifestSanitizer.sanitize(xmlString, logCallback)

            // Step 4
            logCallback("Step 4: Encoding: XML -> AXML...")
            val newBinaryManifest = File(workDir, "AndroidManifest_patched.xml")
            if (!encodeStringToAxml(cleanedXmlString, newBinaryManifest)) {
                return PatchResult.Error("Failed to encode XML to AXML")
            }

            // Step 5
            logCallback("Step 5: Rebuilding APK...")
            ApkRebuilder.rebuildApk(inputApk, newBinaryManifest, outputApk)

            logCallback("Process finished.")
            return PatchResult.Success(outputApk, PatchStats(1, 1))

        } catch (e: Exception) {
            e.printStackTrace()
            logCallback("Error: ${e.message}")
            return PatchResult.Error("Error: ${e.message}")
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun decodeManifestToString(binaryFile: File): String? {
        return try {
            FileInputStream(binaryFile).use { fis ->
                aXMLDecoder(fis).decodeAsString()
            }
        } catch (e: Exception) { null }
    }

    private fun encodeStringToAxml(xmlContent: String, outputFile: File): Boolean {
        return try {
            val binaryData = aXMLEncoder().encodeString(xmlContent, context)
            FileOutputStream(outputFile).use { fos -> fos.write(binaryData) }
            true
        } catch (e: Exception) { false }
    }

    /**
     * Extrait et décode le AndroidManifest.xml sans le modifier.
     * Retourne le contenu XML sous forme de String.
     */
    fun fetchManifestContent(apkFile: File): String {
        var zipFile: ZipFile? = null
        try {
            zipFile = ZipFile(apkFile)
            val entry = zipFile.getEntry("AndroidManifest.xml")
                ?: throw Exception("AndroidManifest.xml not found in APK")

            // On décode le binaire AXML en Texte lisible
            val inputStream = zipFile.getInputStream(entry)
            return aXMLDecoder(inputStream).decodeAsString()
                ?: throw Exception("Failed to decode AXML")

        } catch (e: Exception) {
            throw Exception("Preview Error: ${e.message}")
        } finally {
            zipFile?.close()
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

    // Ajoute cette fonction surchargée :
    fun applyCustomPatch(
        inputApk: File,
        outputApk: File,
        patchData: CustomPatchData,
        logCallback: (String) -> Unit
    ): PatchResult {
        // ... (Le début est identique : dossier temp, extraction, décodage) ...
        // Je ne remets pas tout le code pour être concis, copie la logique de patchApkManifest

        val workDir = File(context.cacheDir, "custom_patch_${System.currentTimeMillis()}")
        try {
            workDir.mkdirs()
            // ... Extract & Decode ...
            val binaryManifest = File(workDir, "AndroidManifest.xml")
            extractManifestFromApk(inputApk, binaryManifest)
            val xmlString = decodeManifestToString(binaryManifest) ?: return PatchResult.Error("Decode failed")

            logCallback("Step 3: Applying Custom Patch Rules...")

            // APPEL A LA NOUVELLE FONCTION DU SANITIZER
            val cleanedXmlString = ManifestSanitizer.applyCustomPatch(xmlString, patchData, logCallback)

            // ... Encode & Rebuild ...
            val newBinaryManifest = File(workDir, "AndroidManifest_patched.xml")
            encodeStringToAxml(cleanedXmlString, newBinaryManifest)
            ApkRebuilder.rebuildApk(inputApk, newBinaryManifest, outputApk)

            return PatchResult.Success(outputApk, PatchStats(1, 1))
        } catch (e: Exception) {
            return PatchResult.Error(e.message ?: "Unknown error")
        } finally {
            workDir.deleteRecursively()
        }
    }
}

sealed class PatchResult {
    data class Success(val outputApk: File, val stats: PatchStats) : PatchResult()
    data class Error(val message: String) : PatchResult()
}

data class PatchStats(
    val removedComponents: Int,
    val neutralizedConfigs: Int
)
