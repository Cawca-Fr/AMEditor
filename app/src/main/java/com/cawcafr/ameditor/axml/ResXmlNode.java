package com.cawcafr.ameditor.axml;

public abstract class ResXmlNode {

    private int nameIndex = -1;
    private int namespaceIndex = -1;

    public int getNameIndex() {
        return nameIndex;
    }

    public void setNameIndex(int index) {
        this.nameIndex = index;
    }

    public int getNamespaceIndex() {
        return namespaceIndex;
    }

    public void setNamespaceIndex(int index) {
        this.namespaceIndex = index;
    }

    public abstract String getName();
}