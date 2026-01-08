package com.cawcafr.ameditor.util

import android.util.Log
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Remplace le script Python pour nettoyer le Manifest XML.
 * Utilise le parser DOM standard d'Android (léger et natif).
 */
object ManifestSanitizer {

    private const val TAG = "ManifestSanitizer"
    private const val NS_ANDROID = "http://schemas.android.com/apk/res/android"

    // Liste des signatures de traqueurs (partielles ou complètes)
    private val TRACKERS = listOf(
        "com.google.android.gms.ads",
        "com.facebook.ads",
        "com.mopub.mobileads",
        "com.startapp.sdk",
        "com.applovin",
        "com.unity3d.ads",
        "com.google.android.gms.measurement",
        "com.adjust.sdk",
        "com.appsflyer",
        "com.ironsource",
        "com.vungle"
    )

    private val PERMISSIONS_TO_REMOVE = listOf(
        "com.google.android.gms.permission.AD_ID"
    )

    /**
     * Prend le contenu XML en String, le nettoie, et retourne le XML propre en String.
     */
    fun sanitize(xmlContent: String): String {
        return try {
            // 1. Initialisation du Parser DOM
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true // Important pour lire "android:name" correctement
            val builder = factory.newDocumentBuilder()

            // 2. Parsing du texte en structure d'arbre (Document)
            val doc = builder.parse(InputSource(StringReader(xmlContent)))

            // 3. Nettoyage
            var removedCount = 0
            removedCount += removeComponents(doc)
            removedCount += removePermissions(doc)

            Log.d(TAG, "Nettoyage terminé : $removedCount éléments supprimés.")

            // 4. Reconversion Arbre -> Texte (String)
            convertDocToString(doc)

        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors du nettoyage XML", e)
            // En cas d'erreur, on renvoie l'original pour ne pas crasher l'app
            xmlContent
        }
    }

    private fun removeComponents(doc: Document): Int {
        var count = 0
        // On cherche dans <application>
        val appNodes = doc.getElementsByTagName("application")
        if (appNodes.length == 0) return 0
        val application = appNodes.item(0) as Element

        val tagsToCheck = listOf("activity", "service", "receiver", "provider", "meta-data")

        for (tag in tagsToCheck) {
            // getElementsByTagName cherche récursivement (enfants, petits-enfants...)
            val elements = application.getElementsByTagName(tag)

            // On stocke les éléments à supprimer dans une liste à part pour ne pas casser la boucle
            val toRemove = mutableListOf<Node>()

            for (i in 0 until elements.length) {
                val element = elements.item(i) as Element
                val name = getAndroidName(element)

                if (isTracker(name)) {
                    toRemove.add(element)
                    Log.i(TAG, "Traqueur détecté : $name ($tag)")
                }
            }

            // Suppression SÉCURISÉE (Le correctif par rapport à ton ancien code)
            for (node in toRemove) {
                // On demande au parent direct du nœud de le supprimer
                // (Le parent peut être <application> ou un autre composant)
                node.parentNode?.removeChild(node)
                count++
            }
        }
        return count
    }

    private fun removePermissions(doc: Document): Int {
        var count = 0
        val root = doc.documentElement // <manifest>
        val permissions = root.getElementsByTagName("uses-permission")
        val toRemove = mutableListOf<Node>()

        for (i in 0 until permissions.length) {
            val element = permissions.item(i) as Element
            val name = getAndroidName(element)

            if (PERMISSIONS_TO_REMOVE.contains(name)) {
                toRemove.add(element)
                Log.i(TAG, "Permission interdite : $name")
            }
        }

        for (node in toRemove) {
            node.parentNode?.removeChild(node)
            count++
        }
        return count
    }

    // Aide pour récupérer l'attribut android:name proprement
    private fun getAndroidName(element: Element): String {
        // Essaie avec le namespace explicite
        var name = element.getAttributeNS(NS_ANDROID, "name")
        // Si vide, essaie le nom brut (au cas où le namespace parser a échoué)
        if (name.isEmpty()) {
            name = element.getAttribute("android:name")
        }
        return name
    }

    private fun isTracker(name: String): Boolean {
        if (name.isEmpty()) return false
        return TRACKERS.any { trackerSig ->
            name.contains(trackerSig, ignoreCase = true)
        }
    }

    private fun convertDocToString(doc: Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        // Configuration pour garder un XML lisible (pretty print)
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")

        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }
}