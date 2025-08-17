package com.cawcafr.ameditor.axml.arsc.chunk.xml;

import com.cawcafr.ameditor.axml.arsc.base.HeaderBlock;
import com.cawcafr.ameditor.axml.arsc.chunk.BaseChunk;
import com.cawcafr.ameditor.axml.arsc.chunk.ChunkType;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Représente un chunk de fermeture de balise (</tag>) dans un document binaire AXML.
 */
public class ResXmlEndElement extends BaseChunk {

    private int lineNumber = 0;
    private int comment = -1;
    private int nsIndex = -1;
    private int nameIndex = -1;

    public ResXmlEndElement() {
        super(new HeaderBlock(ChunkType.XML_END_ELEMENT));
    }

    public void setNamespaceIndex(int ns) { this.nsIndex = ns; }
    public void setNameIndex(int name) { this.nameIndex = name; }

    @Override
    public int getSize() {
        return 24;
    }

    @Override
    public void writeBytes(OutputStream out) throws IOException {
        getHeaderBlock().setChunkSize(getSize());
        getHeaderBlock().writeBytes(out);

        writeInt(out, lineNumber);
        writeInt(out, comment);
        writeInt(out, nsIndex);
        writeInt(out, nameIndex);
    }

    private void writeInt(OutputStream out, int val) throws IOException {
        out.write(val & 0xFF);
        out.write((val >> 8) & 0xFF);
        out.write((val >> 16) & 0xFF);
        out.write((val >> 24) & 0xFF);
    }
}