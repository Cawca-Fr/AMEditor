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

    /**
     * Nettoyage automatique standard (Trackers, etc.)
     */
    fun sanitize(xmlContent: String, logCallback: (String) -> Unit): String {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()

            val doc = builder.parse(InputSource(StringReader(xmlContent)))

            // Nettoyage préventif des nœuds vides
            try {
                stripEmptyTextNodes(doc)
            } catch (e: Exception) {
            }

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

    // --- LOGIQUE STANDARD (Interne) ---

    private fun removeElements(doc: Document, onDisable: () -> Unit): Int {
        var count = 0
        val appNodes = doc.getElementsByTagName("application")
        if (appNodes.length == 0) return 0

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
                val node = elements.item(i)
                if (node !is Element) continue

                val name = getAndroidName(node)

                if (isComponentToDisable(name)) {
                    toDisable.add(node)
                } else if (TrackersList.isTracker(name)) {
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
     * NOUVELLE FONCTION : Applique un patch basé sur des index précis.
     * Résout le problème des doublons ou des éléments sans nom.
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

            try { stripEmptyTextNodes(doc) } catch (e: Exception) {}

            var deleted = 0
            var disabled = 0

            // Compteurs pour suivre où on en est dans le fichier (comme dans l'Activity)
            val tagCounters = mutableMapOf<String, Int>()

            // Liste des nœuds à traiter (on ne peut pas modifier la liste pendant qu'on la parcourt)
            val nodesToDelete = mutableListOf<Node>()
            val nodesToDisable = mutableListOf<Element>()

            // On doit parcourir l'arbre complet dans le MÊME ORDRE que l'Activity (Depth First / Document Order)
            // TreeWalker est parfait pour ça, mais une récursion simple suffit ici.
            val allNodes = getAllNodesOrdered(doc.documentElement)

            for (node in allNodes) {
                if (node !is Element) continue

                val tagName = node.tagName // ex: "activity" ou "android:activity" selon namespace
                // Nettoyage du nom de tag (parfois le parser ajoute des préfixes)
                val cleanTagName = if (tagName.contains(":")) tagName.substringAfter(":") else tagName

                val currentIndex = tagCounters.getOrDefault(cleanTagName, 0)

                // Vérifier si ce nœud spécifique est dans notre liste de cibles
                val target = patchData.targets.find {
                    it.tagName == cleanTagName &&
                            it.occurrenceIndex == currentIndex
                }

                if (target != null) {
                    val nodeName = getAndroidName(node)

                    // Double vérification optionnelle (si le nom existe, il doit correspondre)
                    // Cela évite de supprimer le mauvais truc si le fichier a changé entre temps
                    if (target.androidName != null && target.androidName != nodeName) {
                        Log.w(TAG, "Mismatch for $cleanTagName #$currentIndex : expected ${target.androidName}, found $nodeName. Skipping.")
                    } else {
                        // Action !
                        if (target.type == ActionType.DELETE) {
                            nodesToDelete.add(node)
                            logCallback("Delete: <$cleanTagName> index $currentIndex ($nodeName)")
                            deleted++
                        } else if (target.type == ActionType.DISABLE) {
                            nodesToDisable.add(node)
                            logCallback("Disable: <$cleanTagName> index $currentIndex ($nodeName)")
                            disabled++
                        }
                    }
                }

                // Incrémenter le compteur pour ce tag
                tagCounters[cleanTagName] = currentIndex + 1
            }

            // Application des modifications
            for (node in nodesToDelete) {
                node.parentNode?.removeChild(node)
            }

            for (element in nodesToDisable) {
                if (element.parentNode != null) { // Si pas déjà supprimé
                    element.setAttributeNS(NS_ANDROID, "android:enabled", "false")
                    element.setAttributeNS(NS_ANDROID, "android:exported", "false")
                }
            }

            logCallback("Custom Patch Applied: $deleted deleted, $disabled disabled.")
            convertDocToString(doc)

        } catch (e: Exception) {
            Log.e(TAG, "Custom Patch Error", e)
            logCallback("Error applying custom patch: ${e.message}")
            xmlContent
        }
    }

    /**
     * Récupère tous les nœuds dans l'ordre du document (plat) pour assurer la synchro des index
     */
    private fun getAllNodesOrdered(root: Node): List<Node> {
        val list = mutableListOf<Node>()
        list.add(root)
        val children = root.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                list.addAll(getAllNodesOrdered(child))
            }
        }
        return list
    }
}