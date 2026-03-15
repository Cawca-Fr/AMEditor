package com.cawcafr.ameditor.util

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkRebuilder {

    private const val TAG            = "ApkRebuilder"
    private const val BUF            = 256 * 1024   // 256 KB
    private const val ALIGN_DEFAULT  = 4            // bytes — alignement standard ZIP
    private const val ALIGN_SO       = 4096         // bytes — page-size pour les libs natives

    // ════════════════════════════════════════════════════════════════════════
    // POURQUOI DEUX NIVEAUX D'ALIGNEMENT ?
    //
    // ALIGN_DEFAULT = 4 bytes
    //   Requis par Android PackageManager pour parser le ZIP (mmap générique).
    //   Corrige "null array" sur ARMv7 / Android 5–6.
    //
    // ALIGN_SO = 4096 bytes (page size)
    //   Les bibliothèques .so sont chargées via mmap() par le linker dynamique
    //   du système. Le linker exige que l'offset du fichier .so dans le ZIP
    //   soit un multiple de la taille de page (4 KB).
    //   Sans ça : INSTALL_FAILED_INVALID_APK / Failed to extract native
    //   libraries, res=-2.
    //
    //   C'est ce que fait zipalign avec le flag -p (page-align shared libs).
    // ════════════════════════════════════════════════════════════════════════

    fun rebuildApk(originalApk: File, newManifest: File, outputApk: File) {

        ZipFile(originalApk).use { zipFile ->

            // CountingOutputStream DIRECTEMENT sous ZipOutputStream —
            // aucun buffer entre les deux, sinon le comptage serait décalé.
            val fileOut     = FileOutputStream(outputApk)
            val countingOut = CountingOutputStream(fileOut)

            ZipOutputStream(countingOut).use { zos ->

                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name  = entry.name

                    // Supprime l'ancien manifest
                    if (name.equals("AndroidManifest.xml", ignoreCase = true)) continue

                    // Supprime les signatures, garde META-INF/services etc.
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

                    if (entry.method == ZipEntry.STORED) {
                        // Les .so exigent l'alignement page-size (4096 bytes)
                        // pour être mappés directement par le linker dynamique.
                        // Tout le reste utilise l'alignement standard (4 bytes).
                        val alignment = if (name.endsWith(".so", ignoreCase = true))
                            ALIGN_SO else ALIGN_DEFAULT
                        writeStoredAligned(zipFile, entry, zos, countingOut, alignment)
                    } else {
                        writeDeflated(zipFile, entry, zos)
                    }
                }

                // Nouveau manifest — DEFLATED, pas besoin d'alignement
                Log.d(TAG, "Injection AndroidManifest.xml patché")
                zos.setLevel(9)
                zos.putNextEntry(ZipEntry("AndroidManifest.xml").apply {
                    method = ZipEntry.DEFLATED
                })
                FileInputStream(newManifest).use { it.copyTo(zos, BUF) }
                zos.closeEntry()
            }
        }

        Log.d(TAG, "Reconstruction + zipalign : ${outputApk.length() / 1024 / 1024} MB")
    }

    // ════════════════════════════════════════════════════════════════════════
    // STORED — avec padding d'alignement
    // ════════════════════════════════════════════════════════════════════════

    private fun writeStoredAligned(
        zipFile:   ZipFile,
        entry:     ZipEntry,
        zos:       ZipOutputStream,
        counter:   CountingOutputStream,
        alignment: Int = ALIGN_DEFAULT
    ) {
        val nameLen    = entry.name.toByteArray(Charsets.UTF_8).size
        val headerBase = counter.count + 30 + nameLen
        val padding    = ((alignment - (headerBase % alignment)) % alignment).toInt()

        val (size, crc) = resolveSizeAndCrc(zipFile, entry)

        val newEntry = ZipEntry(entry.name).apply {
            method         = ZipEntry.STORED
            this.size      = size
            compressedSize = size
            time           = entry.time
            this.crc       = crc
            // Le champ extra sert ici uniquement au padding d'alignement.
            // dataOffset = counter.count + 30 + nameLen + padding → multiple de 4 ✓
            extra          = ByteArray(padding)
        }

        zos.putNextEntry(newEntry)
        zipFile.getInputStream(entry).use { it.copyTo(zos, BUF) }
        zos.closeEntry()
    }

    private fun resolveSizeAndCrc(zipFile: ZipFile, entry: ZipEntry): Pair<Long, Long> {
        // Cas normal : taille et CRC déjà connus dans l'entrée source
        if (entry.size >= 0 && entry.crc >= 0) return entry.size to entry.crc

        // Cas rare : on calcule en streaming via un fichier temp
        val tempFile = File.createTempFile("apk_crc_", null)
        try {
            val crc32 = CRC32()
            var total = 0L
            val buf   = ByteArray(BUF)
            zipFile.getInputStream(entry).use { input ->
                FileOutputStream(tempFile).use { out ->
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        crc32.update(buf, 0, n); out.write(buf, 0, n); total += n
                    }
                }
            }
            return total to crc32.value
        } finally {
            tempFile.delete()
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // DEFLATED — recompression niveau 9
    // ════════════════════════════════════════════════════════════════════════

    private fun writeDeflated(zipFile: ZipFile, entry: ZipEntry, zos: ZipOutputStream) {
        zos.setLevel(9)
        zos.putNextEntry(ZipEntry(entry.name).apply {
            method = ZipEntry.DEFLATED
            time   = entry.time
        })
        zipFile.getInputStream(entry).use { it.copyTo(zos, BUF) }
        zos.closeEntry()
    }

    // ════════════════════════════════════════════════════════════════════════
    // CountingOutputStream
    //
    // Compte les bytes écrits dans le stream ZIP.
    // Doit être positionné DIRECTEMENT après ZipOutputStream, sans buffer
    // intermédiaire, pour que counter.count reflète l'offset exact dans
    // le fichier au moment du calcul du padding.
    // ════════════════════════════════════════════════════════════════════════

    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {

        var count: Long = 0L
            private set

        override fun write(b: Int)                           { out.write(b);           count += 1   }
        override fun write(b: ByteArray)                     { out.write(b);           count += b.size }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len  }
        override fun flush()                                 = out.flush()
        override fun close()                                 = out.close()
    }
}