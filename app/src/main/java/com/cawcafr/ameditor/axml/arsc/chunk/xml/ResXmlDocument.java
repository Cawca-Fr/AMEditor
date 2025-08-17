package com.cawcafr.ameditor.axml.arsc.chunk.xml;

import com.cawcafr.ameditor.axml.arsc.chunk.StringPool;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Représente un document XML binaire complet (AndroidManifest.xml au format AXML).
 */
public class ResXmlDocument {

    private final StringPool stringPool;
    private final ResXmlElement rootElement;

    public ResXmlDocument() {
        stringPool = new StringPool();
        rootElement = new ResXmlElement(stringPool);
    }

    public void parse(File xmlFile) throws Exception {
        try (InputStream in = new FileInputStream(xmlFile)) {
            // Ici, il faudrait utiliser un vrai parser de texte XML
            // et remplir le stringPool + rootElement
            // (à implémenter selon besoin/text parser)
        }
    }

    public void write(FileOutputStream fos) throws Exception {
        // Écrit le pool de chaînes, puis la structure XML
        stringPool.writeBytes(fos);
        // Puis écrire rootElement, ses attributs, etc.
        // (implémenter l'écriture des chunks XML ici)
    }

    public StringPool getStringPool() {
        return stringPool;
    }

    public ResXmlElement getRootElement() {
        return rootElement;
    }
}