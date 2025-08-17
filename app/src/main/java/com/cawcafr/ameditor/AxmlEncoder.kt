package com.cawcafr.ameditor

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.*
import java.nio.charset.StandardCharsets

object AxmlEncoder {

    @JvmStatic
    fun encodeXmlToAxml(xmlPath: String): ByteArray {
        val input = File(xmlPath).inputStream()
        val output = ByteArrayOutputStream()
        reencodeXml(input, output)
        return output.toByteArray()
    }

    private fun reencodeXml(input: InputStream, output: OutputStream) {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(InputStreamReader(input, StandardCharsets.UTF_8))

        val serializer = factory.newSerializer()
        serializer.setOutput(OutputStreamWriter(output, StandardCharsets.UTF_8))
        serializer.startDocument("utf-8", true)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    serializer.startTag(parser.namespace, parser.name)
                    for (i in 0 until parser.attributeCount) {
                        serializer.attribute(
                            parser.getAttributeNamespace(i),
                            parser.getAttributeName(i),
                            parser.getAttributeValue(i)
                        )
                    }
                }
                XmlPullParser.TEXT -> serializer.text(parser.text)
                XmlPullParser.END_TAG -> serializer.endTag(parser.namespace, parser.name)
            }
            event = parser.next()
        }

        serializer.endDocument()
        serializer.flush()
    }
}