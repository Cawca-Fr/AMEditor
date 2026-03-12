package com.cawcafr.ameditor

/**
 * Singleton en mémoire pour partager le contenu XML entre Activities.

 * CYCLE DE VIE :
 * - Set dans MainActivity avant startActivity()
 * - Read dans XmlPreviewActivity / CustomPatchActivity dans onCreate()
 * - Clear() appelé dans MainActivity quand un nouvel APK est sélectionné
 */
object XmlContentHolder {
    private var xmlContent: String? = null

    fun set(content: String) {
        xmlContent = content
    }

    fun get(): String? = xmlContent

    fun clear() {
        xmlContent = null
    }
}