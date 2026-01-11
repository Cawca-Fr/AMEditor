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

object ManifestSanitizer {

    private const val TAG = "ManifestSanitizer"
    private const val NS_ANDROID = "http://schemas.android.com/apk/res/android"

    fun sanitize(xmlContent: String, logCallback: (String) -> Unit): String {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()

            val doc = builder.parse(InputSource(StringReader(xmlContent)))

            // CORRECTION CRASH : Nettoyage sécurisé des espaces vides
            try {
                stripEmptyTextNodes(doc)
            } catch (e: Exception) {
                logCallback("⚠️ Warning: Le formatage XML a échoué, on continue quand même.")
            }

            var removedCount = 0
            var disabledCount = 0

            removedCount += removeElements(doc, logCallback) { disabledCount++ }
            removedCount += removePermissions(doc, logCallback)
            removedCount += removeIntentsAndPackages(doc, logCallback)

            logCallback("🧹 Nettoyage terminé : $removedCount éléments supprimés, $disabledCount désactivés.")

            convertDocToString(doc)
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors du nettoyage XML", e)
            // CORRECTION LOG : On affiche l'exception entière ($e) et pas juste le message (null)
            logCallback("❌ Erreur XML Critique : $e")
            // En cas d'erreur, on renvoie l'original (l'app ne sera pas patchée mais marchera)
            xmlContent
        }
    }

    private fun removeElements(doc: Document, logger: (String) -> Unit, onDisable: () -> Unit): Int {
        var count = 0
        val appNodes = doc.getElementsByTagName("application")
        if (appNodes.length == 0) return 0
        val application = appNodes.item(0) as Element

        val tagsToCheck = listOf(
            "activity", "activity-alias", "service", "receiver", "provider",
            "meta-data", "uses-library", "property"
        )

        for (tag in tagsToCheck) {
            val elements = application.getElementsByTagName(tag)

            // On sépare la logique de collecte pour éviter les bugs d'index lors de la suppression
            val toRemove = mutableListOf<Node>()
            val toDisable = mutableListOf<Element>()

            for (i in 0 until elements.length) {
                val element = elements.item(i) as Element
                val name = getAndroidName(element)

                if (TrackersList.COMPONENTS_TO_DISABLE.contains(name)) {
                    toDisable.add(element)
                }
                else if (TrackersList.isTracker(name)) {
                    toRemove.add(element)
                }
            }

            // Suppression
            for (node in toRemove) {
                node.parentNode?.removeChild(node)
                count++
            }

            // Désactivation
            for (element in toDisable) {
                val name = getAndroidName(element)
                element.setAttributeNS(NS_ANDROID, "android:enabled", "false")
                element.setAttributeNS(NS_ANDROID, "android:exported", "false")
                logger("⚠️ Désactivé (Anti-Crash): $name")
                onDisable()
            }
        }
        return count
    }

    private fun removeIntentsAndPackages(doc: Document, logger: (String) -> Unit): Int {
        var count = 0
        val queriesNodes = doc.getElementsByTagName("queries")

        for (q in 0 until queriesNodes.length) {
            val queriesTag = queriesNodes.item(q) as Element

            val packageTags = queriesTag.getElementsByTagName("package")
            val pkgToRemove = mutableListOf<Node>()
            for (i in 0 until packageTags.length) {
                val el = packageTags.item(i) as Element
                if (TrackersList.isTracker(getAndroidName(el))) pkgToRemove.add(el)
            }
            pkgToRemove.forEach { it.parentNode?.removeChild(it); count++ }

            val intentTags = queriesTag.getElementsByTagName("intent")
            val intentToRemove = mutableListOf<Node>()
            for (i in 0 until intentTags.length) {
                if (shouldRemoveIntent(intentTags.item(i) as Element)) intentToRemove.add(intentTags.item(i))
            }
            intentToRemove.forEach { it.parentNode?.removeChild(it); count++ }
        }
        return count
    }

    private fun shouldRemoveIntent(intentElement: Element): Boolean {
        val actions = intentElement.getElementsByTagName("action")
        for (i in 0 until actions.length) {
            val name = getAndroidName(actions.item(i) as Element)
            if (TrackersList.isTracker(name)) return true
        }
        return false
    }

    private fun removePermissions(doc: Document, logger: (String) -> Unit): Int {
        var count = 0
        val root = doc.documentElement
        val permissions = root.getElementsByTagName("uses-permission")
        val toRemove = mutableListOf<Node>()

        for (i in 0 until permissions.length) {
            val element = permissions.item(i) as Element
            val name = getAndroidName(element)

            if (TrackersList.PERMISSIONS_TO_REMOVE.contains(name) || TrackersList.isTracker(name)) {
                toRemove.add(element)
            }
        }

        for (node in toRemove) {
            val name = getAndroidName(node as Element)
            node.parentNode?.removeChild(node)
            logger("🚫 Permission retirée: $name")
            count++
        }
        return count
    }

    private fun getAndroidName(element: Element): String {
        var name = element.getAttributeNS(NS_ANDROID, "name")
        if (name.isEmpty()) name = element.getAttribute("android:name")
        return name
    }

    /**
     * CORRECTION DU CRASH ICI :
     * On vérifie si nodeValue est null avant de faire trim().
     */
    private fun stripEmptyTextNodes(node: Node) {
        val childNodes = node.childNodes
        var i = 0
        while (i < childNodes.length) {
            val child = childNodes.item(i)

            // Check null-safety pour nodeValue
            val value = child.nodeValue

            if (child.nodeType == Node.TEXT_NODE && (value == null || value.trim().isEmpty())) {
                node.removeChild(child)
                i--
            } else if (child.nodeType == Node.ELEMENT_NODE) {
                stripEmptyTextNodes(child)
            }
            i++
        }
    }

    private fun convertDocToString(doc: Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        // On augmente l'indentation pour bien voir la hiérarchie
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")

        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }
}