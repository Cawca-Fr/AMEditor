package com.cawcafr.ameditor.axml.arsc.chunk;

import com.cawcafr.ameditor.axml.arsc.base.Block;
import com.cawcafr.ameditor.axml.arsc.base.HeaderBlock;

import java.io.IOException;
import java.io.OutputStream;

/**
 * BaseChunk est la classe parente de toutes les structures binaires
 * (chunks) comme StringPool, ResTable, ResXml...
 */
public abstract class BaseChunk extends Block {

    private final HeaderBlock headerBlock;

    public BaseChunk(HeaderBlock headerBlock) {
        this.headerBlock = headerBlock;
    }

    public BaseChunk() {
        this(new HeaderBlock(ChunkType.NULL));
    }

    public HeaderBlock getHeaderBlock() {
        return headerBlock;
    }

    public int getChunkType() {
        return headerBlock.getChunkType();
    }

    @Override
    public void writeBytes(OutputStream out) throws IOException {
        headerBlock.writeBytes(out);
    }

    @Override
    public String toString() {
        return "Chunk type=" + getChunkType();
    }
}