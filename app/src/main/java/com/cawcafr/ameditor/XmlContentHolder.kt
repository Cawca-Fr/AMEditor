package com.cawcafr.ameditor

import java.io.File

/**
 * Singleton partagé pour le contenu XML.
 * OPTIMISÉ : Stocke un fichier temporaire au lieu d'une String géante en RAM.
 */
object XmlContentHolder {
    private var xmlFile: File? = null

    fun set(file: File) {
        xmlFile = file
    }

    /** Lit le contenu complet du fichier en String (pour compatibilité) */
    fun get(): String? {
        val file = xmlFile ?: return null
        return try {
            file.readText()
        } catch (e: Exception) {
            null
        }
    }

    fun getFile(): File? = xmlFile

    fun clear() {
        xmlFile?.delete()
        xmlFile = null
    }
}
