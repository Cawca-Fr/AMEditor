package com.cawcafr.ameditor.axml;

import com.cawcafr.ameditor.axml.arsc.chunk.StringPool;
import com.cawcafr.ameditor.axml.arsc.chunk.xml.*;
import com.cawcafr.ameditor.axml.arsc.item.ResXmlAttribute;
import com.cawcafr.ameditor.axml.arsc.pool.ResXmlStringPool;
import com.cawcafr.ameditor.axml.arsc.value.ValueType;

import org.w3c.dom.*;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Convertit un fichier AndroidManifest.xml texte en AXML binaire complet
 * avec gestion du namespace Android et génération du ResourceMap.
 */
public class AXMLWriter {

    public static final String ANDROID_NS_PREFIX = "android";
    public static final String ANDROID_NS_URI = "http://schemas.android.com/apk/res/android";

    // Classe interne pour mapper attribut -> ID
    private static class ResourceEntry {
        String name;
        int id;
        ResourceEntry(String name, int id) {
            this.name = name;
            this.id = id;
        }
    }

    // Table complète extraite de public.xml du framework Android
    private static final List<ResourceEntry> ANDROID_ATTRIBUTES = new ArrayList<>();
    static {
        addAndroidAttr("theme", 0x01010000);
        addAndroidAttr("label", 0x01010001);
        addAndroidAttr("icon", 0x01010002);
        addAndroidAttr("name", 0x01010003);
        addAndroidAttr("manageSpaceActivity", 0x01010004);
        addAndroidAttr("allowClearUserData", 0x01010005);
        addAndroidAttr("permission", 0x01010006);
        addAndroidAttr("readPermission", 0x01010007);
        addAndroidAttr("writePermission", 0x01010008);
        addAndroidAttr("protectionLevel", 0x01010009);
        addAndroidAttr("permissionGroup", 0x0101000a);
        addAndroidAttr("sharedUserId", 0x0101000b);
        addAndroidAttr("hasCode", 0x0101000c);
        addAndroidAttr("persistent", 0x0101000d);
        addAndroidAttr("enabled", 0x0101000e);
        addAndroidAttr("exported", 0x0101000f);
        addAndroidAttr("process", 0x01010010);
        addAndroidAttr("taskAffinity", 0x01010011);
        addAndroidAttr("multiprocess", 0x01010012);
        addAndroidAttr("finishOnTaskLaunch", 0x01010013);
        addAndroidAttr("clearTaskOnLaunch", 0x01010014);
        addAndroidAttr("stateNotNeeded", 0x01010015);
        addAndroidAttr("excludeFromRecents", 0x01010016);
        addAndroidAttr("authorities", 0x01010017);
        addAndroidAttr("syncable", 0x01010018);
        addAndroidAttr("initOrder", 0x01010019);
        addAndroidAttr("grantUriPermissions", 0x0101001a);
        addAndroidAttr("priority", 0x0101001b);
        addAndroidAttr("launchMode", 0x0101001c);
        addAndroidAttr("screenOrientation", 0x0101001e);
        addAndroidAttr("configChanges", 0x0101001f);
        addAndroidAttr("description", 0x01010020);
        addAndroidAttr("targetPackage", 0x01010021);
        addAndroidAttr("handleProfiling", 0x01010022);
        addAndroidAttr("functionalTest", 0x01010023);

        // Application / manifest meta
        addAndroidAttr("versionCode", 0x0101021b);
        addAndroidAttr("versionName", 0x0101021c);
        addAndroidAttr("installLocation", 0x010102b7);
        addAndroidAttr("minSdkVersion", 0x0101020c);
        addAndroidAttr("targetSdkVersion", 0x01010270);
        addAndroidAttr("maxSdkVersion", 0x01010277);
        addAndroidAttr("debuggable", 0x0101000f);
        addAndroidAttr("allowBackup", 0x01010280);
        addAndroidAttr("largeHeap", 0x010103a9);
        addAndroidAttr("supportsRtl", 0x010103af);

        // Intent filters
        addAndroidAttr("scheme", 0x01010027);
        addAndroidAttr("host", 0x01010028);
        addAndroidAttr("port", 0x01010029);
        addAndroidAttr("path", 0x0101002a);
        addAndroidAttr("pathPrefix", 0x0101002b);
        addAndroidAttr("pathPattern", 0x0101002c);
        addAndroidAttr("mimeType", 0x01010026);
        // Activity/task specifics
        addAndroidAttr("uiOptions", 0x01010382);
        addAndroidAttr("hardwareAccelerated", 0x010102d3);
    }

    private static void addAndroidAttr(String name, int id) {
        ANDROID_ATTRIBUTES.add(new ResourceEntry(name, id));
    }

    private static int findAndroidAttrId(String name) {
        for (ResourceEntry e : ANDROID_ATTRIBUTES) {
            if (e.name.equals(name)) return e.id;
        }
        return -1;
    }

    /**
     * Encode un AndroidManifest.xml texte en flux binaire AXML complet.
     */
    public static void encode(File xmlFile, OutputStream out) throws Exception {
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(xmlFile);
        document.getDocumentElement().normalize();

        // Structure principale AXML
        ResXmlTree tree = new ResXmlTree();
        ResXmlStringPool pool = tree.getStringPool();

        // ResourceMap (vide pour l'instant)
        ResXmlResourceMap resMap = new ResXmlResourceMap();
        tree.addXmlChunk(resMap);

        // Namespace Android
        ResXmlStartNamespace nsStart = new ResXmlStartNamespace(pool);
        nsStart.setPrefix(ANDROID_NS_PREFIX);
        nsStart.setUri(ANDROID_NS_URI);
        tree.addXmlChunk(nsStart);

        // Ajout récursif des éléments du manifest
        addNode(document.getDocumentElement(), pool, tree, resMap);

        // Fermeture namespace Android
        ResXmlEndNamespace nsEnd = new ResXmlEndNamespace();
        nsEnd.setPrefixIndex(pool.indexOf(ANDROID_NS_PREFIX));
        nsEnd.setUriIndex(pool.indexOf(ANDROID_NS_URI));
        tree.addXmlChunk(nsEnd);

        // Écriture binaire
        tree.writeBytes(out);
        out.flush();
    }

    private static void addNode(Element elem, ResXmlStringPool pool, ResXmlTree tree, ResXmlResourceMap resMap) {
        if (pool.indexOf(elem.getTagName()) == -1) {
            pool.add(elem.getTagName());
        }

        ResXmlStartElement startChunk = new ResXmlStartElement(pool);
        startChunk.setName(elem.getTagName());

        // Attributs
        NamedNodeMap attrs = elem.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node n = attrs.item(i);

            if (pool.indexOf(n.getNodeName()) == -1) {
                pool.add(n.getNodeName());
            }
            if (pool.indexOf(n.getNodeValue()) == -1) {
                pool.add(n.getNodeValue());
            }

            ResXmlAttribute attr = new ResXmlAttribute(pool);
            attr.setName(n.getNodeName());
            attr.setRawValue(n.getNodeValue());
            attr.setValueType(ValueType.STRING);
            attr.setData(0);
            startChunk.addAttribute(attr);

            // Ajout au ResourceMap si attribut Android connu
            if (n.getNodeName().startsWith(ANDROID_NS_PREFIX + ":")) {
                String localName = n.getNodeName().substring(ANDROID_NS_PREFIX.length() + 1);
                int resId = findAndroidAttrId(localName);
                if (resId != -1 && !resMap.getResourceIds().contains(resId)) {
                    resMap.addResourceId(resId);
                }
            }
        }

        pool.attachElement(startChunk);
        tree.addXmlChunk(startChunk);

        // Enfants
        NodeList nodes = elem.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node child = nodes.item(i);
            if (child instanceof Element) {
                addNode((Element) child, pool, tree, resMap);
            }
        }

        ResXmlEndElement endChunk = new ResXmlEndElement();
        endChunk.setNameIndex(pool.indexOf(elem.getTagName()));
        tree.addXmlChunk(endChunk);
    }
}