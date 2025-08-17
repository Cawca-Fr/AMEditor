package com.cawcafr.ameditor.axml.arsc.chunk.xml;

import com.cawcafr.ameditor.axml.arsc.base.HeaderBlock;
import com.cawcafr.ameditor.axml.arsc.chunk.BaseChunk;
import com.cawcafr.ameditor.axml.arsc.chunk.ChunkType;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Chunk RES_XML_RESOURCE_MAP (0x0180) qui contient les IDs de ressource
 * associés aux noms d'attributs du StringPool pour un document XML binaire AXML.
 * Facultatif, mais souvent présent dans les AndroidManifest parsés par aapt/aapt2.
 */
public class ResXmlResourceMap extends BaseChunk {

    private final List<Integer> resourceIds = new ArrayList<>();

    public ResXmlResourceMap() {
        super(new HeaderBlock(ChunkType.XML_RESOURCE_MAP));
    }

    /**
     * Ajoute un ID de ressource au map.
     * @param resId entier, typiquement 0x7f0xxxxx ou 0x0101xxxx
     */
    public void addResourceId(int resId) {
        resourceIds.add(resId);
    }

    public int getResourceId(int index) {
        return resourceIds.get(index);
    }

    public int size() {
        return resourceIds.size();
    }

    public List<Integer> getResourceIds() {
        return resourceIds;
    }

    @Override
    public int getSize() {
        // header (8 octets) + 4 octets pour chaque ID
        return 8 + (resourceIds.size() * 4);
    }

    @Override
    public void writeBytes(OutputStream out) throws IOException {
        getHeaderBlock().setChunkSize(getSize());
        getHeaderBlock().writeBytes(out);

        for (int id : resourceIds) {
            writeInt(out, id);
        }
    }

    private void writeInt(OutputStream out, int val) throws IOException {
        out.write(val & 0xFF);
        out.write((val >> 8) & 0xFF);
        out.write((val >> 16) & 0xFF);
        out.write((val >> 24) & 0xFF);
    }

    @Override
    public String toString() {
        return "ResXmlResourceMap (" + size() + " IDs)";
    }
}