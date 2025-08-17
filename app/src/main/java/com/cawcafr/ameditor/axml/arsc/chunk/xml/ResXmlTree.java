package com.cawcafr.ameditor.axml.arsc.chunk.xml;

import com.cawcafr.ameditor.axml.arsc.base.Block;
import com.cawcafr.ameditor.axml.arsc.pool.ResXmlStringPool;
import com.cawcafr.ameditor.axml.arsc.container.BlockList;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Représente un document XML binaire complet (AXML),
 * avec son header, son StringPool et la liste des chunks XML.
 */
public class ResXmlTree extends Block {

    private final ResXmlTreeHeader treeHeader;
    private final ResXmlStringPool stringPool;
    private final BlockList<Block> xmlChunks;

    public ResXmlTree() {
        treeHeader = new ResXmlTreeHeader();
        stringPool = new ResXmlStringPool();
        xmlChunks = new BlockList<>();
    }

    public ResXmlTreeHeader getTreeHeader() {
        return treeHeader;
    }

    public ResXmlStringPool getStringPool() {
        return stringPool;
    }

    public void addXmlChunk(Block chunk) {
        xmlChunks.add(chunk);
    }

    public BlockList<Block> getXmlChunks() {
        return xmlChunks;
    }

    @Override
    public int getSize() {
        return treeHeader.getSize()
                + stringPool.getSize()
                + xmlChunks.getSize();
    }

    @Override
    public void writeBytes(OutputStream out) throws IOException {
        treeHeader.writeBytes(out);
        stringPool.writeBytes(out);
        for (Block chunk : xmlChunks) {
            chunk.onPreWrite();
            chunk.writeBytes(out);
        }
    }
}