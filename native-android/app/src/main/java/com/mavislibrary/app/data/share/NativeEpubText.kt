package com.mavislibrary.app.data.share

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

object NativeEpubText {
    private const val MAX_XML_BYTES = 2 * 1024 * 1024
    private const val MAX_TOTAL_TEXT = 3_000_000

    fun read(file: File): List<String> = ZipFile(file).use { zip ->
        if (!validateEpub(file, EPUB_MIME)) throw IOException("Invalid or oversized EPUB")
        fun entry(name: String): ByteArray {
            val item = zip.getEntry(name) ?: throw IOException("Missing EPUB entry")
            if (item.size !in 1..MAX_XML_BYTES.toLong()) throw IOException("EPUB entry too large")
            return zip.getInputStream(item).use { stream ->
                readBounded(stream, MAX_XML_BYTES)
            }
        }
        var opf = ""
        parse(entry("META-INF/container.xml"), object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes) {
                if (localName == "rootfile" && opf.isEmpty()) opf = attrs.getValue("full-path").orEmpty()
            }
        })
        if (opf.isEmpty() || opf.startsWith('/') || opf.split('/').any { it == ".." }) throw IOException("Invalid EPUB package")
        val manifest = mutableMapOf<String, String>()
        val spine = mutableListOf<String>()
        parse(entry(opf), object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes) {
                when (localName) {
                    "item" -> if (attrs.getValue("media-type") == "application/xhtml+xml")
                        manifest[attrs.getValue("id").orEmpty()] = attrs.getValue("href").orEmpty()
                    "itemref" -> spine.add(attrs.getValue("idref").orEmpty())
                }
            }
        })
        var total = 0
        val chapters = spine.take(200).mapNotNull { id ->
            val href = manifest[id] ?: return@mapNotNull null
            val resolved = runCatching { URI(opf.replace(" ", "%20")).resolve(href).normalize().path }.getOrNull()
                ?: return@mapNotNull null
            if (resolved.startsWith('/') || resolved.split('/').any { it == ".." }) return@mapNotNull null
            val text = StringBuilder()
            parse(entry(resolved), object : DefaultHandler() {
                private var hiddenDepth = 0
                override fun startElement(uri: String?, localName: String?, qName: String?, attrs: Attributes) {
                    if (hiddenDepth > 0 || localName in setOf("script", "style")) { hiddenDepth++; return }
                    if (localName in setOf("p", "h1", "h2", "h3", "h4", "br", "li", "div")) text.append('\n')
                }
                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    if (hiddenDepth > 0) hiddenDepth--
                }
                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (hiddenDepth > 0) return
                    if (text.length + length + total > MAX_TOTAL_TEXT) throw IOException("EPUB text too large")
                    text.append(ch, start, length)
                }
            })
            total += text.length
            text.toString().replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim().takeIf { it.isNotBlank() }
        }
        chapters.takeIf { it.isNotEmpty() } ?: throw IOException("No readable chapters")
    }

    internal fun readBounded(stream: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() <= limit) {
            val n = stream.read(buffer, 0, minOf(buffer.size, limit + 1 - out.size()))
            if (n < 0) return out.toByteArray()
            if (n == 0) {
                val one = stream.read()
                if (one < 0) return out.toByteArray()
                out.write(one)
            } else out.write(buffer, 0, n)
        }
        throw IOException("EPUB entry too large")
    }

    private fun parse(bytes: ByteArray, handler: DefaultHandler) {
        // Parser-independent XXE/entity-bomb guard: Android's Expat-based parser rejects the Xerces-only
        // security features below, so never rely on them alone. Reject any DTD/entity declaration outright.
        val head = String(bytes, Charsets.ISO_8859_1)
        if (head.contains("<!DOCTYPE", ignoreCase = true) || head.contains("<!ENTITY", ignoreCase = true)) {
            throw IOException("EPUB XML with a DTD is not supported")
        }
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            // Best effort only: unsupported features throw on Android (SAXNotRecognizedException).
            listOf(
                "http://apache.org/xml/features/disallow-doctype-decl" to true,
                "http://xml.org/sax/features/external-general-entities" to false,
                "http://xml.org/sax/features/external-parameter-entities" to false,
                "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
                XMLConstants.FEATURE_SECURE_PROCESSING to true,
            ).forEach { (name, value) -> runCatching { setFeature(name, value) } }
        }
        factory.newSAXParser().parse(ByteArrayInputStream(bytes), handler)
    }
}
