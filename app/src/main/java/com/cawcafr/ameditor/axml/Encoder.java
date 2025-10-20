package com.cawcafr.ameditor.axml;

import com.cawcafr.ameditor.axml.chunks.Chunk;
import com.cawcafr.ameditor.axml.chunks.StringPoolChunk;
import com.cawcafr.ameditor.axml.chunks.TagChunk;
import com.cawcafr.ameditor.axml.chunks.XmlChunk;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.*;

/**
 * Created by Roy on 15-10-4.
 */
public class Encoder {

    public static class Config{
        public static StringPoolChunk.Encoding encoding= StringPoolChunk.Encoding.UNICODE;
        public static int defaultReferenceRadix=16;
    }

    public byte[] encodeFile(ReferenceResolver resolver,String filename) throws XmlPullParserException, IOException {
        XmlPullParserFactory f=XmlPullParserFactory.newInstance();
        f.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);
        XmlPullParser p=f.newPullParser();
        p.setInput(new FileInputStream(filename),"UTF-8");
        return encode(resolver,p);
    }

    public byte[] encodeString(ReferenceResolver resolver,String xml) throws XmlPullParserException, IOException {
        XmlPullParserFactory f=XmlPullParserFactory.newInstance();
        f.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES,true);
        XmlPullParser p=f.newPullParser();
        p.setInput(new StringReader(xml));
        return encode(resolver,p);
    }

    public byte[] encode(ReferenceResolver resolver,XmlPullParser p) throws XmlPullParserException, IOException {
        if (resolver==null) resolver = new DefaultReferenceResolver();
        XmlChunk chunk=new XmlChunk(resolver);
        //HashSet<String> strings=new HashSet<String>();
        TagChunk current=null;
        for (int i=p.getEventType();i!=XmlPullParser.END_DOCUMENT;i=p.next()){
            switch (i){
                case XmlPullParser.START_DOCUMENT:
                    break;
                case XmlPullParser.START_TAG:
                   /* strings.add(p.getName());
                    strings.add(p.getPrefix());
                    strings.add(p.getNamespace());
                    int ac=p.getAttributeCount();
                    for (int j=0;j<ac;++j){
                        strings.add(p.getAttributeName(j));
                        strings.add(p.getAttributePrefix(j));
                        strings.add(p.getAttributeValue(j));
                    }
                    ac=p.getNamespaceCount(p.getDepth());
                    for (int j=p.getNamespaceCount(p.getDepth()-1);j<ac;++j){
                        strings.add(p.getNamespacePrefix(j));
                        strings.add(p.getNamespaceUri(j));
                    }*/
                    current=new TagChunk(current==null?chunk:current,p);
                    break;
                case XmlPullParser.END_TAG:
                    Chunk c=current.getParent();
                    current=c instanceof TagChunk?(TagChunk)c:null;
                    break;
                case XmlPullParser.TEXT:
                    //strings.add(p.getText());
                    break;
                default:
                    break;

            }
        }
        //for (String s:strings) if (s!=null) chunk.stringPool.addString(s);
        ByteArrayOutputStream os=new ByteArrayOutputStream();
        IntWriter w=new IntWriter(os);
        chunk.write(w);
        w.close();
        return os.toByteArray();
    }
}
