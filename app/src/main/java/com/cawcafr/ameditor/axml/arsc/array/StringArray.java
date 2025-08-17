package com.cawcafr.ameditor.axml.arsc.array;

import com.cawcafr.ameditor.axml.arsc.item.StringItem;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Tableau d'éléments StringItem dans un StringPool.
 */
public class StringArray {

    private final List<StringItem> list = new ArrayList<>();

    public int size() {
        return list.size();
    }

    public StringItem get(int index) {
        if (index < 0 || index >= list.size()) return null;
        return list.get(index);
    }

    public int indexOf(String str) {
        if (str == null) return -1;
        for (int i = 0; i < list.size(); i++) {
            if (str.equals(list.get(i).get())) return i;
        }
        return -1;
    }

    public void add(String str) {
        StringItem item = new StringItem();
        item.set(str);
        list.add(item);
    }

    public void refresh() {
        // éventuellement recalculer des index ou trier, si besoin
    }

    public void writeBytes(OutputStream out) throws IOException {
        for (StringItem item : list) {
            item.writeBytes(out);
        }
    }
}