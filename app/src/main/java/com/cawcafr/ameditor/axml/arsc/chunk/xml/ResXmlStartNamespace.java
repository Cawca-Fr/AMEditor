package com.cawcafr.ameditor.axml.arsc.chunk.xml;

import com.cawcafr.ameditor.axml.arsc.base.HeaderBlock;
import com.cawcafr.ameditor.axml.arsc.chunk.BaseChunk;
import com.cawcafr.ameditor.axml.arsc.chunk.ChunkType;
import com.cawcafr.ameditor.axml.arsc.chunk.StringPool;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Chunk de début d'espace de noms XML (ex: xmlns:android="...").
 */
public class ResXmlStartNamespace extends BaseChunk {

    private int lineNumber = 0;
    private int comment = -1;
    private int prefixIndex = -1;
    private int uriIndex = -1;
    private final StringPool stringPool;

    public ResXmlStartNamespace(StringPool pool) {
        super(new HeaderBlock(ChunkType.XML_START_NAMESPACE));
        this.stringPool = pool;
    }

    public void setPrefix(String prefix) {
        if (prefix != null && stringPool.indexOf(prefix) == -1) {
            stringPool.add(prefix);
        }
        this.prefixIndex = stringPool.indexOf(prefix);
    }

    public void setUri(String uri) {
        if (uri != null && stringPool.indexOf(uri) == -1) {
            stringPool.add(uri);
        }
        this.uriIndex = stringPool.indexOf(uri);
    }

    public int getPrefixIndex() {
        return prefixIndex;
    }

    public int getUriIndex() {
        return uriIndex;
    }

    @Override
    public int getSize() {
        // 24 bytes : header(8) + 4*4 ints
        return 24;
    }

    @Override
    public void writeBytes(OutputStream out) throws IOException {
        getHeaderBlock().setChunkSize(getSize());
        getHeaderBlock().writeBytes(out);

        writeInt(out, lineNumber);
        writeInt(out, comment);
        writeInt(out, prefixIndex);
        writeInt(out, uriIndex);
    }

    private void writeInt(OutputStream out, int val) throws IOException {
        out.write(val & 0xFF);
        out.write((val >> 8) & 0xFF);
        out.write((val >> 16) & 0xFF);
        out.write((val >> 24) & 0xFF);
    }
}