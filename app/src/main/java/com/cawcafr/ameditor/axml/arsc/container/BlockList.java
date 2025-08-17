package com.cawcafr.ameditor.axml.arsc.container;

import com.cawcafr.ameditor.axml.arsc.base.Block;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Conteneur générique de plusieurs Blocks.
 * Utilisé pour stocker un ensemble de sous-éléments de type Block.
 */
public class BlockList<T extends Block> extends Block implements Iterable<T> {

    private final List<T> childBlocks = new ArrayList<>();

    public void add(T block) {
        if (block != null) {
            childBlocks.add(block);
        }
    }

    public T get(int i) {
        if (i < 0 || i >= childBlocks.size()) return null;
        return childBlocks.get(i);
    }

    public int size() {
        return childBlocks.size();
    }

    public void clear() {
        childBlocks.clear();
    }

    @Override
    public int getSize() {
        int result = 0;
        for (T blk : childBlocks) {
            result += blk.getSize();
        }
        return result;
    }

    @Override
    public void writeBytes(OutputStream out) throws IOException {
        for (T blk : childBlocks) {
            blk.onPreWrite();
            blk.writeBytes(out);
        }
    }

    @Override
    public Iterator<T> iterator() {
        return childBlocks.iterator();
    }
}