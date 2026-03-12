package com.cawcafr.ameditor.util

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkRebuilder {

    private const val TAG         = "ApkRebuilder"
    private const val BUFFER_SIZE = 256 * 1024   // 256 KB

    fun rebuildApk(originalApk: File, newManifest: File, outputApk: File) {

        ZipFile(originalApk).use { zipFile ->
            ZipOutputStream(
                FileOutputStream(outputApk).buffered(BUFFER_SIZE)
            ).use { zos ->

                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name  = entry.name

                    // ── 1. Supprime l'ancien manifest ────────────────────────────
                    if (name.equals("AndroidManifest.xml", ignoreCase = true)) continue

                    // ── 2. Supprime les signatures (mais garde META-INF/services) ─
                    if (name.startsWith("META-INF/")) {
                        val upper = name.uppercase()
                        if (upper.endsWith(".SF")          ||
                            upper.endsWith(".RSA")         ||
                            upper.endsWith(".DSA")         ||
                            upper.endsWith(".EC")          ||
                            upper.endsWith("MANIFEST.MF")) {
                            Log.d(TAG, "Signature supprimée : $name")
                            continue
                        }
                    }

                    // ── 3. Respecte la méthode de compression ORIGINALE ──────────
                    // FIX : on lit entry.method et on reproduit exactement.

                    if (entry.method == ZipEntry.STORED) {
                        writeStoredEntry(zipFile, entry, zos)
                    } else {
                        writeDeflatedEntry(zipFile, entry, zos)
                    }
                }

                // ── 4. Injecte le nouveau manifest (DEFLATED, compression max) ───
                Log.d(TAG, "Injection du AndroidManifest.xml patché")
                zos.setLevel(9)
                val manifestEntry = ZipEntry("AndroidManifest.xml").apply {
                    method = ZipEntry.DEFLATED
                }
                zos.putNextEntry(manifestEntry)
                FileInputStream(newManifest).use { it.copyTo(zos, BUFFER_SIZE) }
                zos.closeEntry()
            }
        }

        Log.d(TAG, "Reconstruction terminée : ${outputApk.length() / 1024 / 1024} MB")
    }

    // ════════════════════════════════════════════════════════════════════════
    // STORED — copie octet par octet, aucune compression
    // ════════════════════════════════════════════════════════════════════════

    private fun writeStoredEntry(zipFile: ZipFile, entry: ZipEntry, zos: ZipOutputStream) {
        // Cas normal : size et crc déjà connus dans l'entrée source
        if (entry.size >= 0 && entry.crc >= 0) {
            val newEntry = ZipEntry(entry.name).apply {
                method         = ZipEntry.STORED
                size           = entry.size
                compressedSize = entry.size
                time           = entry.time
                crc            = entry.crc
            }
            zos.putNextEntry(newEntry)
            zipFile.getInputStream(entry).use { it.copyTo(zos, BUFFER_SIZE) }
            zos.closeEntry()
            return
        }

        // Cas rare : size/crc inconnus → passe via fichier temp pour calculer
        val tempFile = File.createTempFile("apk_stored_", null)
        try {
            val crc32 = CRC32()
            var totalSize = 0L
            val buf = ByteArray(BUFFER_SIZE)

            zipFile.getInputStream(entry).use { input ->
                FileOutputStream(tempFile).use { out ->
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        crc32.update(buf, 0, n)
                        out.write(buf, 0, n)
                        totalSize += n
                    }
                }
            }

            val newEntry = ZipEntry(entry.name).apply {
                method         = ZipEntry.STORED
                size           = totalSize
                compressedSize = totalSize
                time           = entry.time
                crc            = crc32.value
            }
            zos.putNextEntry(newEntry)
            tempFile.inputStream().use { it.copyTo(zos, BUFFER_SIZE) }
            zos.closeEntry()
        } finally {
            tempFile.delete()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // DEFLATED — recompression niveau 9
    // ════════════════════════════════════════════════════════════════════════
    //
    // L'API Java standard ne permet pas de copier les bytes DEFLATED bruts.
    // On décompresse + recompresse à level 9 (identique à ce que font aapt/apktool).
    // La taille reste quasi identique à l'original (±0.1% sur du DEX/XML).

    private fun writeDeflatedEntry(zipFile: ZipFile, entry: ZipEntry, zos: ZipOutputStream) {
        zos.setLevel(9)
        val newEntry = ZipEntry(entry.name).apply {
            method = ZipEntry.DEFLATED
            time   = entry.time
        }
        zos.putNextEntry(newEntry)
        zipFile.getInputStream(entry).use { it.copyTo(zos, BUFFER_SIZE) }
        zos.closeEntry()
    }
}