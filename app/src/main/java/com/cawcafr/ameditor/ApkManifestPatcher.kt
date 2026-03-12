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

            logCallback("Step 1: Extracting AndroidManifest.xml...")
            val binaryManifest = File(workDir, "AndroidManifest.xml")
            if (!extractManifestFromApk(inputApk, binaryManifest)) {
                return PatchResult.Error("Manifest extraction failed")
            }

            logCallback("Step 2: Decoding: AXML -> XML...")
            val xmlString = decodeManifestToString(binaryManifest)
                ?: return PatchResult.Error("Failed to decode AXML")

            logCallback("Step 3: Patching...")
            val cleanedXmlString = ManifestSanitizer.sanitize(xmlString, logCallback)

            logCallback("Step 4: Encoding: XML -> AXML...")
            val newBinaryManifest = File(workDir, "AndroidManifest_patched.xml")
            if (!encodeStringToAxml(cleanedXmlString, newBinaryManifest)) {
                return PatchResult.Error("Failed to encode XML to AXML")
            }

            logCallback("Step 5: Rebuilding APK...")
            ApkRebuilder.rebuildApk(inputApk, newBinaryManifest, outputApk)

            // ── FIX CACHE : supprimer l'APK source dès que le rebuild est terminé ──
            // inputApk = selected_internal.apk (~154 MB) — plus nécessaire après ça
            if (inputApk.name == "selected_internal.apk") {
                inputApk.delete()
                Log.d(TAG, "Input APK deleted from cache after rebuild")
            }

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

    fun fetchManifestContent(apkFile: File): String {
        // Pas de copie ici — on lit directement depuis le ZipFile en streaming.
        // Aucun fichier temporaire créé, aucun impact sur le cache.
        var zipFile: ZipFile? = null
        try {
            zipFile = ZipFile(apkFile)
            val entry = zipFile.getEntry("AndroidManifest.xml")
                ?: throw Exception("AndroidManifest.xml not found in APK")
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

    fun applyCustomPatch(
        inputApk: File,
        outputApk: File,
        patchData: CustomPatchData,
        logCallback: (String) -> Unit
    ): PatchResult {
        val workDir = File(context.cacheDir, "custom_patch_${System.currentTimeMillis()}")
        try {
            workDir.mkdirs()
            val binaryManifest = File(workDir, "AndroidManifest.xml")
            extractManifestFromApk(inputApk, binaryManifest)
            val xmlString = decodeManifestToString(binaryManifest)
                ?: return PatchResult.Error("Decode failed")

            logCallback("Step 3: Applying Custom Patch Rules...")
            val cleanedXmlString = ManifestSanitizer.applyCustomPatch(xmlString, patchData, logCallback)

            val newBinaryManifest = File(workDir, "AndroidManifest_patched.xml")
            encodeStringToAxml(cleanedXmlString, newBinaryManifest)
            ApkRebuilder.rebuildApk(inputApk, newBinaryManifest, outputApk)

            // ── FIX CACHE : même chose ici ──
            if (inputApk.name == "selected_internal.apk") {
                inputApk.delete()
                Log.d(TAG, "Input APK deleted from cache after custom patch")
            }

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