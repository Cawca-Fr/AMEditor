package com.cawcafr.ameditor.util

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object ApkRebuilder {

    /**
     * Reconstruit un APK en remplaçant le AndroidManifest.xml par un nouveau.
     *
     * @param originalApk  Fichier APK source (non modifié)
     * @param manifestAxml Nouveau manifest AXML binaire
     * @param outputApk    Fichier APK de sortie
     */
    @JvmStatic
    fun rebuildApkJava(originalApk: File, manifestAxml: File, outputApk: File) {
        ZipFile(originalApk).use { zin ->
            ZipOutputStream(outputApk.outputStream()).use { zout ->
                for (entry in zin.entries()) {
                    val newEntry = ZipEntry(entry.name)
                    newEntry.time = entry.time
                    newEntry.size = -1 // laisser le deflater calculer
                    zout.putNextEntry(newEntry)

                    if (entry.name == "AndroidManifest.xml") {
                        manifestAxml.inputStream().use { it.copyTo(zout) }
                    } else {
                        zin.getInputStream(entry).use { it.copyTo(zout) }
                    }

                    zout.closeEntry()
                }
            }
        }
    }
}