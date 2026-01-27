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

            // Nettoyage préventif des nœuds vides
            try { stripEmptyTextNodes(doc) } catch (e: Exception) {}

            var removedCount = 0
            var disabledCount = 0

            removedCount += removeElements(doc) { disabledCount++ }
            removedCount += removePermissions(doc)
            removedCount += removeIntentsAndPackages(doc)

            val totalPatched = removedCount + disabledCount
            logCallback("Patched successfully !")
            logCallback("Patched : $totalPatched elements")

            convertDocToString(doc)
        } catch (e: Exception) {
            Log.e(TAG, "XML Error", e)
            logCallback("Critical XML Error: ${e.javaClass.simpleName} - ${e.message}")
            xmlContent
        }
    }

    private fun removeElements(doc: Document, onDisable: () -> Unit): Int {
        var count = 0
        val appNodes = doc.getElementsByTagName("application")
        if (appNodes.length == 0) return 0

        // Safe Cast
        val application = appNodes.item(0) as? Element ?: return 0

        val tagsToCheck = listOf(
            "activity", "activity-alias", "service", "receiver", "provider",
            "meta-data", "uses-library", "property"
        )

        for (tag in tagsToCheck) {
            val elements = application.getElementsByTagName(tag)
            val toRemove = mutableListOf<Node>()
            val toDisable = mutableListOf<Element>()

            for (i in 0 until elements.length) {
                // Safe Cast : On vérifie que c'est bien un Element
                val node = elements.item(i)
                if (node !is Element) continue

                val name = getAndroidName(node)

                if (isComponentToDisable(name)) {
                    toDisable.add(node)
                }
                else if (TrackersList.isTracker(name)) {
                    toRemove.add(node)
                }
            }

            for (node in toRemove) {
                node.parentNode?.removeChild(node)
                count++
            }

            for (element in toDisable) {
                element.setAttributeNS(NS_ANDROID, "android:enabled", "false")
                element.setAttributeNS(NS_ANDROID, "android:exported", "false")
                onDisable()
            }
        }
        return count
    }

    private fun isComponentToDisable(name: String): Boolean {
        return TrackersList.COMPONENTS_TO_DISABLE.any {
            name.contains(it, ignoreCase = true)
        }
    }

    private fun removeIntentsAndPackages(doc: Document): Int {
        var count = 0
        val queriesNodes = doc.getElementsByTagName("queries")

        for (q in 0 until queriesNodes.length) {
            val queriesTag = queriesNodes.item(q) as? Element ?: continue

            // Package tags
            val packageTags = queriesTag.getElementsByTagName("package")
            val pkgToRemove = mutableListOf<Node>()
            for (i in 0 until packageTags.length) {
                val el = packageTags.item(i) as? Element ?: continue
                if (TrackersList.isTracker(getAndroidName(el))) pkgToRemove.add(el)
            }
            pkgToRemove.forEach { it.parentNode?.removeChild(it); count++ }

            // Intent tags
            val intentTags = queriesTag.getElementsByTagName("intent")
            val intentToRemove = mutableListOf<Node>()
            for (i in 0 until intentTags.length) {
                val intentEl = intentTags.item(i) as? Element ?: continue
                if (shouldRemoveIntent(intentEl)) {
                    intentToRemove.add(intentEl)
                }
            }
            intentToRemove.forEach { it.parentNode?.removeChild(it); count++ }
        }
        return count
    }

    private fun shouldRemoveIntent(intentElement: Element): Boolean {
        val actions = intentElement.getElementsByTagName("action")
        for (i in 0 until actions.length) {
            val el = actions.item(i) as? Element ?: continue
            val name = getAndroidName(el)
            if (TrackersList.isTracker(name)) return true
        }
        return false
    }

    private fun removePermissions(doc: Document): Int {
        var count = 0
        val root = doc.documentElement ?: return 0
        val permissions = root.getElementsByTagName("uses-permission")
        val toRemove = mutableListOf<Node>()

        for (i in 0 until permissions.length) {
            val element = permissions.item(i) as? Element ?: continue
            val name = getAndroidName(element)

            if (TrackersList.PERMISSIONS_TO_REMOVE.contains(name) || TrackersList.isTracker(name)) {
                toRemove.add(element)
            }
        }

        for (node in toRemove) {
            node.parentNode?.removeChild(node)
            count++
        }
        return count
    }

    private fun getAndroidName(element: Element): String {
        var name = element.getAttributeNS(NS_ANDROID, "name")
        if (name.isEmpty()) name = element.getAttribute("android:name")
        return name
    }

    private fun stripEmptyTextNodes(node: Node) {
        val childNodes = node.childNodes
        var i = 0
        while (i < childNodes.length) {
            val child = childNodes.item(i)
            // Vérification de nullité
            if (child == null) {
                i++
                continue
            }

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
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")

        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }

    /**
     * NOUVELLE FONCTION : Applique un patch personnalisé (Custom Patch)
     */
    fun applyCustomPatch(
        xmlContent: String,
        patchData: CustomPatchData,
        logCallback: (String) -> Unit
    ): String {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(InputSource(StringReader(xmlContent)))

            // Nettoyage format
            try { stripEmptyTextNodes(doc) } catch (e: Exception) {}

            var deleted = 0
            var disabled = 0

            val appNodes = doc.getElementsByTagName("application")
            if (appNodes.length > 0) {
                val application = appNodes.item(0) as Element

                // On scanne TOUS les éléments enfants de <application>
                val childNodes = application.childNodes
                val toRemove = mutableListOf<Node>()

                for (i in 0 until childNodes.length) {
                    val node = childNodes.item(i)
                    if (node !is Element) continue

                    val name = getAndroidName(node)
                    if (name.isEmpty()) continue

                    // Vérification DELETE
                    if (patchData.itemsToDelete.contains(name)) {
                        toRemove.add(node)
                        deleted++
                        logCallback("Custom Delete: $name")
                    }
                    // Vérification DEACTIVATE
                    else if (patchData.itemsToDisable.contains(name)) {
                        node.setAttributeNS(NS_ANDROID, "android:enabled", "false")
                        node.setAttributeNS(NS_ANDROID, "android:exported", "false")
                        disabled++
                        logCallback("Custom Disable: $name")
                    }
                }

                // Application des suppressions
                toRemove.forEach { it.parentNode.removeChild(it) }
            }

            // On pourrait aussi scanner les permissions si besoin, mais
            // pour l'instant concentrons-nous sur les composants

            logCallback("Custom Patch Applied: $deleted deleted, $disabled disabled.")
            convertDocToString(doc)

        } catch (e: Exception) {
            Log.e(TAG, "Custom Patch Error", e)
            logCallback("Error applying custom patch: ${e.message}")
            xmlContent
        }
    }
}