package com.cawcafr.ameditor.axml.arsc.chunk.xml;

import com.cawcafr.ameditor.axml.arsc.base.HeaderBlock;
import com.cawcafr.ameditor.axml.arsc.chunk.BaseChunk;
import com.cawcafr.ameditor.axml.arsc.chunk.ChunkType;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Chunk de fin d'espace de noms XML correspondant à un ResXmlStartNamespace.
 */
public class ResXmlEndNamespace extends BaseChunk {

    private int lineNumber = 0;
    private int comment = -1;
    private int prefixIndex = -1;
    private int uriIndex = -1;

    public ResXmlEndNamespace() {
        super(new HeaderBlock(ChunkType.XML_END_NAMESPACE));
    }

    public void setPrefixIndex(int prefixIndex) {
        this.prefixIndex = prefixIndex;
    }

    public void setUriIndex(int uriIndex) {
        this.uriIndex = uriIndex;
    }

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