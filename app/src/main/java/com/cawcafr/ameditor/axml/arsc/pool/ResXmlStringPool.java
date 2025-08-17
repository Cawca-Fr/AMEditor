package com.cawcafr.ameditor.axml.arsc.pool;

import com.cawcafr.ameditor.axml.arsc.chunk.StringPool;
import com.cawcafr.ameditor.axml.arsc.chunk.xml.ResXmlStartElement;
import com.cawcafr.ameditor.axml.arsc.chunk.xml.ResXmlTreeHeader;
import com.cawcafr.ameditor.axml.arsc.container.BlockList;
import com.cawcafr.ameditor.axml.arsc.container.FixedBlockContainer;

/**
 * StringPool spécialisé pour l'AXML, lié à ResXmlTree.
 * Il gère toutes les chaînes utilisées dans les balises XML, namespaces, et attributs.
 */
public class ResXmlStringPool extends StringPool {

    private final FixedBlockContainer<ResXmlTreeHeader> treeHeader;
    private final BlockList<ResXmlStartElement> startElements;

    public ResXmlStringPool() {
        super();
        this.treeHeader = new FixedBlockContainer<>();
        this.startElements = new BlockList<>();
    }

    public FixedBlockContainer<ResXmlTreeHeader> getTreeHeader() {
        return treeHeader;
    }

    public BlockList<ResXmlStartElement> getStartElements() {
        return startElements;
    }

    /**
     * Ajoute toutes les chaînes présentes dans un élément XML (tag + attributs) au pool.
     */
    public void attachElement(ResXmlStartElement element) {
        if (element == null) return;

        // Ajout du namespace si présent
        if (element.getNamespace() != null && indexOf(element.getNamespace()) == -1) {
            add(element.getNamespace());
        }

        // Ajout du nom de balise
        if (element.getName() != null && indexOf(element.getName()) == -1) {
            add(element.getName());
        }

        // Tous les attributs associés
        element.getAttributes().forEach(attr -> {
            if (attr.getNamespace() != null && indexOf(attr.getNamespace()) == -1) {
                add(attr.getNamespace());
            }
            if (attr.getName() != null && indexOf(attr.getName()) == -1) {
                add(attr.getName());
            }
            if (attr.getRawValue() != null && indexOf(attr.getRawValue()) == -1) {
                add(attr.getRawValue());
            }
        });

        // Référence dans la liste pour suivi
        startElements.add(element);
    }
}