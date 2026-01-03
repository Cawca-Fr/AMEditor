package com.cawcafr.ameditor.util

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkRebuilder {

    private const val TAG = "ApkRebuilder"

    /**
     * Reconstruit l'APK proprement.
     * 1. Ignore l'ancien META-INF (car invalide).
     * 2. Remplace le AndroidManifest.xml.
     * 3. Recopie les autres fichiers en laissant ZipOutputStream gérer la compression.
     */
    fun rebuildApk(originalApk: File, newManifest: File, outputApk: File) {
        val zipFile = ZipFile(originalApk)

        try {
            ZipOutputStream(FileOutputStream(outputApk)).use { zos ->
                val entries = zipFile.entries()

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name

                    // 1. IGNORER l'ancien manifest et l'ancienne signature
                    if (name.equals("AndroidManifest.xml", ignoreCase = true) ||
                        name.startsWith("META-INF/")) {
                        continue
                    }

                    // 2. COPIER les fichiers normaux
                    // Important : On crée une NOUVELLE entrée pour réinitialiser CRC/Size
                    val newEntry = ZipEntry(name)
                    zos.putNextEntry(newEntry)

                    zipFile.getInputStream(entry).use { input ->
                        input.copyTo(zos)
                    }
                    zos.closeEntry()
                }

                // 3. INJECTER le nouveau Manifest
                Log.d(TAG, "Injection du nouveau AndroidManifest.xml")
                val manifestEntry = ZipEntry("AndroidManifest.xml")
                zos.putNextEntry(manifestEntry)
                FileInputStream(newManifest).use { input ->
                    input.copyTo(zos)
                }
                zos.closeEntry()
            }
            Log.d(TAG, "Reconstruction terminée avec succès : ${outputApk.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de la reconstruction", e)
            throw e // On relance pour que l'UI le sache
        } finally {
            zipFile.close()
        }
    }
}