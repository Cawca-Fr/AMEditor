package com.cawcafr.ameditor.axml.arsc.container;

import com.cawcafr.ameditor.axml.arsc.base.Block;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Conteneur fixe de Blocks générique : permet de stocker un ensemble
 * dont le nombre et l'ordre sont fixes.
 */
public class FixedBlockContainer<T extends Block> extends Block {

    private final List<T> blocks = new ArrayList<>();

    public FixedBlockContainer() {
    }

    public FixedBlockContainer<T> addBlock(T block) {
        blocks.add(block);
        return this;
    }

    public T get(int index) {
        if (index < 0 || index >= blocks.size()) return null;
        return blocks.get(index);
    }

    public int count() {
        return blocks.size();
    }

    @Override
    public int getSize() {
        int sz = 0;
        for (T b : blocks) {
            sz += b.getSize();
        }
        return sz;
    }

    @Override
    public void writeBytes(OutputStream out) throws IOException {
        for (T b : blocks) {
            b.onPreWrite();
            b.writeBytes(out);
        }
    }
}