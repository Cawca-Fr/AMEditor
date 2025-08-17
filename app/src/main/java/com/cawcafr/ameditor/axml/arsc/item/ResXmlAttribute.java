package com.cawcafr.ameditor.axml.arsc.item;

import com.cawcafr.ameditor.axml.arsc.chunk.StringPool;
import com.cawcafr.ameditor.axml.arsc.value.ValueType;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Représente un attribut d'élément XML dans le format AXML Android.
 */
public class ResXmlAttribute {

    private final StringPool stringPool;

    private int namespaceIndex = -1;
    private int nameIndex = -1;
    private int rawValueIndex = -1;
    private ValueType valueType = ValueType.STRING;
    private int data = 0;

    public ResXmlAttribute(StringPool pool) {
        this.stringPool = pool;
    }

    public String getNamespace() {
        return namespaceIndex >= 0 ? stringPool.get(namespaceIndex) : null;
    }

    public void setNamespace(String ns) {
        if (ns == null) {
            namespaceIndex = -1;
            return;
        }
        ensureInPool(ns);
        this.namespaceIndex = stringPool.indexOf(ns);
    }

    public int getNamespaceIndex() {
        return namespaceIndex;
    }

    public void setNamespaceIndex(int idx) {
        this.namespaceIndex = idx;
    }

    public String getName() {
        return nameIndex >= 0 ? stringPool.get(nameIndex) : null;
    }

    public void setName(String name) {
        ensureInPool(name);
        this.nameIndex = stringPool.indexOf(name);
    }

    public int getNameIndex() {
        return nameIndex;
    }

    public void setNameIndex(int idx) {
        this.nameIndex = idx;
    }

    public void setRawValue(String rawValue) {
        ensureInPool(rawValue);
        this.rawValueIndex = stringPool.indexOf(rawValue);
    }

    public String getRawValue() {
        return rawValueIndex >= 0 ? stringPool.get(rawValueIndex) : null;
    }

    public int getRawValueIndex() {
        return rawValueIndex;
    }

    public void setRawValueIndex(int idx) {
        this.rawValueIndex = idx;
    }

    public ValueType getValueType() {
        return valueType;
    }

    public void setValueType(ValueType type) {
        this.valueType = (type != null) ? type : ValueType.STRING;
    }

    public int getData() {
        return data;
    }

    public void setData(int data) {
        this.data = data;
    }

    private void ensureInPool(String str) {
        if (str != null && stringPool.indexOf(str) == -1) {
            stringPool.add(str);
        }
    }

    /**
     * Écrit l'attribut au format binaire AXML.
     */
    public void writeBytes(OutputStream out) throws IOException {
        // Namespace index
        writeInt(out, getNamespaceIndex());
        // Name index
        writeInt(out, getNameIndex());
        // Raw value index
        writeInt(out, getRawValueIndex());
        // Type (high byte) + unused (low 3 bytes)
        writeInt(out, (getValueType().id() << 24));
        // Data
        writeInt(out, getData());
    }

    private void writeInt(OutputStream out, int val) throws IOException {
        out.write(val & 0xFF);
        out.write((val >> 8) & 0xFF);
        out.write((val >> 16) & 0xFF);
        out.write((val >> 24) & 0xFF);
    }

    @Override
    public String toString() {
        String ns = getNamespace();
        String name = getName();
        String val = getRawValue();
        return (ns != null ? ns + ":" : "") + name + "=\"" + val + "\"";
    }
}