package com.cawcafr.ameditor

import android.content.Context
import android.util.Log
import com.apk.axml.aXMLDecoder
import com.apk.axml.aXMLEncoder
import com.cawcafr.ameditor.util.ApkRebuilder
import com.cawcafr.ameditor.util.CustomPatchData
import com.cawcafr.ameditor.util.ManifestSanitizer
import org.lsposed.lsparanoid.Obfuscate
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipFile
@Obfuscate
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

            logCallback(context.getString(R.string.log_step_extract_manifest))
            val binaryManifest = File(workDir, "AndroidManifest.xml")
            if (!extractManifestFromApk(inputApk, binaryManifest)) {
                return PatchResult.Error(context.getString(R.string.error_manifest_extraction))
            }
            // Taille binaire réelle du manifest dans l'APK (ZipEntry, pas la string décodée)
            val manifestInputSize = binaryManifest.length()

            logCallback(context.getString(R.string.log_step_decode_manifest))
            val xmlString = decodeManifestToString(binaryManifest)
                ?: return PatchResult.Error(context.getString(R.string.error_decode_failed))

            logCallback(context.getString(R.string.log_step_patching))
            val cleanedXmlString = ManifestSanitizer.sanitize(context, xmlString, logCallback)

            logCallback(context.getString(R.string.log_step_encode_manifest))
            val newBinaryManifest = File(workDir, "AndroidManifest_patched.xml")
            if (!encodeStringToAxml(cleanedXmlString, newBinaryManifest)) {
                return PatchResult.Error(context.getString(R.string.error_encode_failed))
            }
            val manifestOutputSize = newBinaryManifest.length()

            logCallback(context.getString(R.string.log_step_rebuild_apk))
            ApkRebuilder.rebuildApk(inputApk, newBinaryManifest, outputApk)

            if (inputApk.name == "selected_internal.apk") {
                inputApk.delete()
                Log.d(TAG, "Input APK deleted from cache after rebuild")
            }

            logCallback(context.getString(R.string.log_process_finished))
            return PatchResult.Success(
                outputApk,
                PatchStats(1, 1, manifestInputSize, manifestOutputSize)
            )

        } catch (e: Exception) {
            e.printStackTrace()
            logCallback(context.getString(R.string.generic_error_exception, e.message))
            return PatchResult.Error(context.getString(R.string.generic_error_exception, e.message))
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
     * Décode le manifest pour la prévisualisation.
     * Retourne la paire (xmlString, binarySize) :
     *   - xmlString   : XML lisible (pour affichage / XmlContentHolder)
     *   - binarySize  : taille réelle du binaire AXML dans le ZIP (pour affichage)
     *
     * IMPORTANT : binarySize ≠ xmlString.length — le binaire AXML est toujours
     * plus compact que la représentation texte XML (~14 KB d'écart typique).
     */
    fun fetchManifestContent(apkFile: File): Pair<String, Long> {
        ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
                ?: throw Exception("AndroidManifest.xml not found in APK")

            // Taille binaire réelle depuis les métadonnées ZIP (pas la string décodée)
            val binarySize = entry.size   // octets du binaire AXML dans le ZIP

            val xmlString = zip.getInputStream(entry).use { stream ->
                aXMLDecoder(stream).decodeAsString()
                    ?: throw Exception("Failed to decode AXML")
            }

            return Pair(xmlString, binarySize)
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
            val manifestInputSize = binaryManifest.length()

            val xmlString = decodeManifestToString(binaryManifest)
                ?: return PatchResult.Error(context.getString(R.string.error_decode_failed))

            logCallback(context.getString(R.string.log_step_custom_patch))
            val cleanedXmlString = ManifestSanitizer.applyCustomPatch(context, xmlString, patchData, logCallback)

            val newBinaryManifest = File(workDir, "AndroidManifest_patched.xml")
            encodeStringToAxml(cleanedXmlString, newBinaryManifest)
            val manifestOutputSize = newBinaryManifest.length()

            ApkRebuilder.rebuildApk(inputApk, newBinaryManifest, outputApk)

            if (inputApk.name == "selected_internal.apk") {
                inputApk.delete()
                Log.d(TAG, "Input APK deleted from cache after custom patch")
            }

            return PatchResult.Success(
                outputApk,
                PatchStats(1, 1, manifestInputSize, manifestOutputSize)
            )
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
    val neutralizedConfigs: Int,
    /** Taille du binaire AXML original dans l'APK (ZipEntry.size). */
    val manifestInputSize: Long  = 0L,
    /** Taille du binaire AXML patché (fichier encodé avant injection). */
    val manifestOutputSize: Long = 0L
)