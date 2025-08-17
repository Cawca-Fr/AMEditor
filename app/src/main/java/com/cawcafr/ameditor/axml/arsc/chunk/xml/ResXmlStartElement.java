package com.cawcafr.ameditor.axml.arsc.chunk.xml;

import com.cawcafr.ameditor.axml.arsc.base.HeaderBlock;
import com.cawcafr.ameditor.axml.arsc.chunk.BaseChunk;
import com.cawcafr.ameditor.axml.arsc.chunk.ChunkType;
import com.cawcafr.ameditor.axml.arsc.chunk.StringPool;
import com.cawcafr.ameditor.axml.arsc.item.ResXmlAttribute;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Représente un chunk d'ouverture de balise (<tag ...>) dans le fichier AXML binaire.
 */
public class ResXmlStartElement extends BaseChunk {

    private int lineNumber = 0;
    private int comment = -1;
    private int namespaceIndex = -1;
    private int nameIndex = -1;
    private int attributeSize = 20; // taille structure attrib. fixe
    private int attributeCount = 0;
    private int idIndex = -1;
    private int classIndex = -1;
    private int styleIndex = -1;

    private final List<ResXmlAttribute> attributes = new ArrayList<>();
    private final StringPool stringPool; // référence pour retrouver le texte

    public ResXmlStartElement(StringPool pool) {
        super(new HeaderBlock(ChunkType.XML_START_ELEMENT));
        this.stringPool = pool;
    }

    public void setNamespaceIndex(int idx) {
        this.namespaceIndex = idx;
    }
    public int getNamespaceIndex() {
        return namespaceIndex;
    }

    public void setNameIndex(int idx) {
        this.nameIndex = idx;
    }

    public void setName(String name) {
        if (name != null) {
            if (stringPool.indexOf(name) == -1) {
                stringPool.add(name);
            }
            this.nameIndex = stringPool.indexOf(name);
        } else {
            this.nameIndex = -1;
        }
    }
    public int getNameIndex() {
        return nameIndex;
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    public void setComment(int commentIndex) {
        this.comment = commentIndex;
    }

    public void addAttribute(ResXmlAttribute attr) {
        attributes.add(attr);
        attributeCount = attributes.size();
    }

    public List<ResXmlAttribute> getAttributes() {
        return attributes;
    }

    /**
     * Retourne le nom complet de la balise à partir du StringPool.
     */
    public String getName() {
        return nameIndex >= 0 && stringPool != null ? stringPool.get(nameIndex) : null;
    }

    /**
     * Retourne le namespace complet de la balise à partir du StringPool.
     */
    public String getNamespace() {
        return namespaceIndex >= 0 && stringPool != null ? stringPool.get(namespaceIndex) : null;
    }

    @Override
    public int getSize() {
        // 36 octets d'entête fixe + 20 octets par attribut
        return 36 + (attributes.size() * 20);
    }

    @Override
    public void writeBytes(OutputStream out) throws IOException {
        getHeaderBlock().setChunkSize(getSize());
        getHeaderBlock().writeBytes(out);

        writeInt(out, lineNumber);
        writeInt(out, comment);
        writeInt(out, namespaceIndex);
        writeInt(out, nameIndex);
        writeInt(out, attributeSize);
        writeInt(out, attributeCount);
        writeInt(out, idIndex);      // index id attribute (sinon -1)
        writeInt(out, classIndex);   // index class attribute (sinon -1)
        writeInt(out, styleIndex);   // index style attribute (sinon -1)

        // Écriture de tous les attributs
        for (ResXmlAttribute attr : attributes) {
            attr.writeBytes(out);
        }
    }

    private void writeInt(OutputStream out, int val) throws IOException {
        out.write(val & 0xFF);
        out.write((val >> 8) & 0xFF);
        out.write((val >> 16) & 0xFF);
        out.write((val >> 24) & 0xFF);
    }
}