package com.cawcafr.ameditor.axml.arsc.chunk.xml;

import com.cawcafr.ameditor.axml.arsc.chunk.StringPool;
import com.cawcafr.ameditor.axml.arsc.item.ResXmlAttribute;

import java.util.ArrayList;
import java.util.List;

/**
 * Représente un élément (balise XML) logique de l'arbre, avec ses attributs et enfants.
 */
public class ResXmlElement {

    private final StringPool stringPool;
    private int nameIndex = -1;
    private int namespaceIndex = -1;
    private final List<ResXmlAttribute> attributes = new ArrayList<>();
    private final List<ResXmlElement> children = new ArrayList<>();

    public ResXmlElement(StringPool pool) {
        this.stringPool = pool;
    }

    public void setName(String tag) {
        if (tag == null) return;
        int idx = stringPool.indexOf(tag);
        if (idx == -1) {
            stringPool.add(tag);
            idx = stringPool.indexOf(tag);
        }
        this.nameIndex = idx;
    }

    public String getName() {
        return stringPool.get(nameIndex);
    }

    public void setNamespace(String ns) {
        if (ns == null) return;
        int idx = stringPool.indexOf(ns);
        if (idx == -1) {
            stringPool.add(ns);
            idx = stringPool.indexOf(ns);
        }
        this.namespaceIndex = idx;
    }

    public String getNamespace() {
        return stringPool.get(namespaceIndex);
    }

    public int getNameIndex() { return nameIndex; }
    public int getNamespaceIndex() { return namespaceIndex; }

    public void addAttribute(ResXmlAttribute attr) {
        attributes.add(attr);
    }
    public List<ResXmlAttribute> listAttributes() { return attributes; }

    public void addChild(ResXmlElement child) {
        children.add(child);
    }

    public List<ResXmlElement> getChildren() {
        return children;
    }

    @Override
    public String toString() {
        return "<" + getName() + " attrs=" + attributes.size() + " children=" + children.size() + ">";
    }
}