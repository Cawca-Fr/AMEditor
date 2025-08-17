package com.cawcafr.ameditor.axml.arsc.chunk.xml;

import com.cawcafr.ameditor.axml.arsc.base.HeaderBlock;
import com.cawcafr.ameditor.axml.arsc.chunk.BaseChunk;
import com.cawcafr.ameditor.axml.arsc.chunk.ChunkType;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Représente l’en-tête (« header ») du fichier XML binaire Android (AXML).
 * Il se trouve toujours en haut du fichier avant les autres chunks.
 */
public class ResXmlTreeHeader extends BaseChunk {

    public ResXmlTreeHeader() {
        super(new HeaderBlock(ChunkType.XML));
        // headerSize est souvent fixe à 8
        getHeaderBlock().setHeaderSize(8);
    }

    @Override
    public int getSize() {
        return getHeaderBlock().getHeaderSize();
    }

    @Override
    public void writeBytes(OutputStream out) throws IOException {
        // On ne fait qu'écrire le header pur : Type + Size
        getHeaderBlock().setChunkSize(getSize());
        getHeaderBlock().writeBytes(out);
    }
}