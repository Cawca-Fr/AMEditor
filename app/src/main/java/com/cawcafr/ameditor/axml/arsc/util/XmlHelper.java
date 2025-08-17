package com.cawcafr.ameditor.axml.arsc.util;

import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;

public class XmlHelper {
    /**
     * Parse un XML texte en Document DOM
     */
    public static Document parseXml(File xmlFile) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        return dbf.newDocumentBuilder().parse(xmlFile);
    }
}