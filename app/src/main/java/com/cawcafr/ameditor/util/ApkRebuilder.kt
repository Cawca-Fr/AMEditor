package com.cawcafr.ameditor.util

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.Locale.getDefault
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkRebuilder {

    private const val TAG = "ApkRebuilder"

    fun rebuildApk(originalApk: File, newManifest: File, outputApk: File) {
        val zipFile = ZipFile(originalApk)

        try {
            ZipOutputStream(FileOutputStream(outputApk)).use { zos ->
                zos.setLevel(9) // Compression max pour gagner de la place

                val entries = zipFile.entries()

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name

                    // 1. FILTRAGE INTELLIGENT
                    // On supprime l'ancien Manifest
                    if (name.equals("AndroidManifest.xml", ignoreCase = true)) {
                        continue
                    }

                    // On supprime UNIQUEMENT les fichiers de signature dans META-INF
                    // Mais on GARDE les fichiers de config (services, maven, kotlin, etc.)
                    if (name.startsWith("META-INF/")) {
                        val upperName = name.uppercase(getDefault())
                        if (upperName.endsWith(".SF") ||
                            upperName.endsWith(".RSA") ||
                            upperName.endsWith(".DSA") ||
                            upperName.endsWith(".EC") ||
                            upperName.endsWith("MANIFEST.MF")) {

                            Log.d(TAG, "Signature supprimée : $name")
                            continue
                        }
                        // Si ce n'est pas une signature, on le GARDE (ex: META-INF/services/...)
                    }

                    // 2. GESTION DES FICHIERS NON COMPRESSÉS (STORED)
                    // (Libs natives .so et resources.arsc)
                    val isNativeLib = name.endsWith(".so", ignoreCase = true)
                    val isArsc = name.equals("resources.arsc", ignoreCase = true)

                    if (isNativeLib || isArsc) {
                        // Mode STORED (Copie sans compression)
                        val buffer = zipFile.getInputStream(entry).readBytes()

                        val newEntry = ZipEntry(name)
                        newEntry.method = ZipEntry.STORED
                        newEntry.size = entry.size
                        newEntry.compressedSize = entry.size
                        newEntry.time = entry.time

                        // Calcul du CRC32 obligatoire
                        val crc = CRC32()
                        crc.update(buffer)
                        newEntry.crc = crc.value

                        zos.putNextEntry(newEntry)
                        zos.write(buffer)
                        zos.closeEntry()
                    }
                    else {
                        // 3. COPIE NORMALE (Compressée)
                        // On crée une nouvelle entrée pour que ZipOutputStream recalcule la compression
                        val newEntry = ZipEntry(name)
                        newEntry.time = entry.time
                        zos.putNextEntry(newEntry)

                        zipFile.getInputStream(entry).use { input ->
                            input.copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                }

                // 4. INJECTION DU NOUVEAU MANIFEST
                Log.d(TAG, "Injection du AndroidManifest.xml patché")
                val manifestEntry = ZipEntry("AndroidManifest.xml")
                zos.putNextEntry(manifestEntry)
                FileInputStream(newManifest).use { input ->
                    input.copyTo(zos)
                }
                zos.closeEntry()
            }
            Log.d(TAG, "Reconstruction terminée : ${outputApk.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Erreur critique reconstruction", e)
            throw e
        } finally {
            zipFile.close()
        }
    }
}