package com.cawcafr.ameditor.util

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ApkRebuilder {

    private const val TAG = "ApkRebuilder"

    private fun copyZipEntry(entry: ZipEntry): ZipEntry {
        return ZipEntry(entry.name).apply {
            comment = entry.comment
            method = entry.method
            size = entry.size
            compressedSize = entry.compressedSize
            crc = entry.crc
            time = entry.time
            extra = entry.extra
        }
    }

    /**
     * Reconstruction classique avec ZipInputStream/ZipOutputStream (moins recommandée).
     * Avec logs détaillés sur les octets lus/écrits!
     */
    fun rebuildApkJava(
        originalApk: File,
        newManifest: File,
        outputApk: File,
        useStoredMode: Boolean = false
    ) {
        Log.d(TAG, "Début rebuildApkJava, useStoredMode=$useStoredMode")
        ZipInputStream(FileInputStream(originalApk)).use { zis ->
            ZipOutputStream(FileOutputStream(outputApk)).use { zos ->

                var entry = zis.nextEntry
                while (entry != null) {
                    Log.d(
                        TAG,
                        "Traitement entrée ZIP : ${entry.name}, method=${entry.method}, size=${entry.size}, compressedSize=${entry.compressedSize}"
                    )

                    if (entry.name.equals("AndroidManifest.xml", ignoreCase = true)) {
                        Log.d(TAG, "Remplacement de AndroidManifest.xml")
                        if (useStoredMode) {
                            val manifestBytes = newManifest.readBytes()
                            val crcCalc = CRC32().apply { update(manifestBytes) }
                            val size = manifestBytes.size.toLong()
                            val newEntry = ZipEntry("AndroidManifest.xml").apply {
                                method = ZipEntry.STORED
                                this.size = size
                                compressedSize = size
                                crc = crcCalc.value
                                time = entry.time
                            }
                            zos.putNextEntry(newEntry)
                            zos.write(manifestBytes)
                            Log.d(TAG, "Manifest patché : ${manifestBytes.size} octets écrits")
                            zos.closeEntry()
                        } else {
                            val newEntry = ZipEntry("AndroidManifest.xml").apply {
                                method = ZipEntry.DEFLATED
                                time = entry.time
                            }
                            zos.putNextEntry(newEntry)
                            FileInputStream(newManifest).use { fis ->
                                val manifestBytes = fis.readBytes()
                                Log.d(
                                    TAG,
                                    "Lecture manifest patché : ${manifestBytes.size} octets lus"
                                )
                                zos.write(manifestBytes)
                                Log.d(
                                    TAG,
                                    "Écriture manifest patché : ${manifestBytes.size} octets écrits"
                                )
                            }
                            zos.closeEntry()
                        }
                    } else {
                        zos.putNextEntry(copyZipEntry(entry))
                        val allBytes = zis.readBytes()
                        Log.d(TAG, "Lecture entrée ${entry.name} : ${allBytes.size} octets lus")
                        zos.write(allBytes)
                        Log.d(TAG, "Écriture entrée ${entry.name} : ${allBytes.size} octets écrits")
                        zos.closeEntry()
                    }
                    entry = zis.nextEntry
                }
            }
        }
        Log.d(TAG, "Fin rebuildApkJava")
    }

    /**
     * Méthode robuste avec ZipFile pour copier contenu compressé tel quel.
     * Recommandée pour éviter les erreurs de tailles compressées invalides.
     */
    fun rebuildApkWithZipFileStrict(originalApk: File, newManifest: File, outputApk: File) {
        val TAG = "ApkRebuilder"
        val zipFile = ZipFile(originalApk)
        ZipOutputStream(FileOutputStream(outputApk)).use { zos ->
            val entries = zipFile.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                Log.d(TAG, "Copie stricte entrée ZIP : ${entry.name}, method=${entry.method}, size=${entry.size}, compressedSize=${entry.compressedSize}, crc=${entry.crc}")
                if (entry.name.equals("AndroidManifest.xml", ignoreCase = true)) {
                    Log.d(TAG, "Injection manifest patché (recompressé)")
                    val newEntry = ZipEntry("AndroidManifest.xml").apply {
                        method = ZipEntry.DEFLATED
                        time = entry.time
                    }
                    zos.putNextEntry(newEntry)
                    FileInputStream(newManifest).use { fis ->
                        val manifestBytes = fis.readBytes()
                        zos.write(manifestBytes)
                        Log.d(TAG, "Écriture manifest patché : ${manifestBytes.size} octets écrits")
                    }
                    zos.closeEntry()
                } else {
                    // Copie stricte : conserve l’ensemble des attributs ZIP d’origine
                    val newEntry = ZipEntry(entry)
                    zos.putNextEntry(newEntry)
                    zipFile.getInputStream(entry).use { raw ->
                        val allBytes = raw.readBytes()
                        zos.write(allBytes)
                        Log.d(TAG, "Flux compressé copié pour ${entry.name} : ${allBytes.size} octets (attendu : ${entry.size}, compressé : ${entry.compressedSize})")
                    }
                    zos.closeEntry()
                }
            }
        }
        zipFile.close()
        Log.d(TAG, "Fin rebuildApkWithZipFileStrict")
    }
}