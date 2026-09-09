package moe.antimony.hoshi.features.news

import java.io.StringReader
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * Reduces an extracted article fragment to the small XHTML vocabulary the reader needs.
 *
 * The WebView extractor already serializes the article container as XML, but a page can still
 * carry inline styles, event handlers, tracking pixels or elements the reader has no use for.
 * Everything not on the allow-list is unwrapped (its children survive) or dropped, attributes are
 * reduced to `src`/`alt` on images, and the result is re-serialized as
 * a well-formed fragment. When the input is not parseable XML at all the caller falls back to
 * [paragraphsFromText].
 */
object NewsArticleXhtml {
    private const val XHTML_NS = "http://www.w3.org/1999/xhtml"

    /** Elements kept as-is. Anything else is unwrapped, except [DROPPED], whose subtree is removed. */
    private val ALLOWED = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li", "blockquote", "figure", "figcaption",
        "ruby", "rt", "rp", "rb", "br", "img", "strong", "em", "b", "i", "table", "thead", "tbody", "tr", "td", "th", "div",
    )
    private val DROPPED = setOf(
        "script", "style", "nav", "header", "footer", "aside", "iframe", "form", "button", "input", "select",
        "textarea", "svg", "noscript", "video", "audio", "canvas", "object", "embed", "template", "head", "link", "meta",
    )

    /** Returns the sanitized fragment, or null when [xhtml] is not well-formed XML. */
    fun sanitize(xhtml: String): String? {
        val document = parse(xhtml) ?: return null
        val output = newDocument()
        val root = output.createElementNS(XHTML_NS, "div")
        output.appendChild(root)
        copyChildren(document.documentElement, root, output)
        dropEmptyBlocks(root)
        if (root.textContent.isNullOrBlank() && root.getElementsByTagName("img").length == 0) return null
        return serializeChildren(root)
    }

    /** Escapes [text] into one `<p>` per non-blank line for the plain-text fallback. */
    fun paragraphsFromText(text: String): String =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n") { "<p>${escape(it)}</p>" }

    fun escape(text: String): String = buildString(text.length) {
        for (char in text) {
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(char)
            }
        }
    }

    private fun copyChildren(from: Node, to: Element, output: Document) {
        var child = from.firstChild
        while (child != null) {
            when (child.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> to.appendChild(output.createTextNode(child.nodeValue.orEmpty()))
                Node.ELEMENT_NODE -> {
                    val element = child as Element
                    val name = element.localName?.lowercase() ?: element.tagName.substringAfter(':').lowercase()
                    when {
                        name in DROPPED -> Unit
                        name in ALLOWED -> {
                            val copy = output.createElementNS(XHTML_NS, name)
                            if (name == "img") {
                                val src = element.getAttribute("src").trim()
                                if (src.isEmpty() || src.startsWith("data:", ignoreCase = true) && src.length > MAX_DATA_URI) {
                                    child = child.nextSibling
                                    continue
                                }
                                copy.setAttribute("src", src)
                                element.getAttribute("alt").takeIf { it.isNotBlank() }?.let { copy.setAttribute("alt", it) }
                            }
                            to.appendChild(copy)
                            copyChildren(element, copy, output)
                        }
                        // Unknown inline/structural wrappers (span, a, section, main, article...) are unwrapped.
                        else -> copyChildren(element, to, output)
                    }
                }
                else -> Unit
            }
            child = child.nextSibling
        }
    }

    /** Removes block elements that ended up with no text and no image, e.g. emptied wrappers. */
    private fun dropEmptyBlocks(root: Element) {
        val blocks = listOf("p", "div", "li", "ul", "ol", "blockquote", "figure", "h1", "h2", "h3", "h4", "h5", "h6")
        var removed = true
        while (removed) {
            removed = false
            for (tag in blocks) {
                val nodes = root.getElementsByTagName(tag)
                val toRemove = (0 until nodes.length).map { nodes.item(it) as Element }
                    .filter { it.textContent.isNullOrBlank() && it.getElementsByTagName("img").length == 0 && it.getElementsByTagName("br").length == 0 }
                toRemove.forEach { it.parentNode?.removeChild(it); removed = true }
            }
        }
    }

    private fun parse(xml: String): Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = factory.newDocumentBuilder()
        builder.setErrorHandler(null)
        builder.parse(InputSource(StringReader(xml)))
    }.getOrNull()

    private fun newDocument(): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().newDocument()

    private fun serializeChildren(root: Element): String {
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.METHOD, "xml")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }
        val out = StringBuilder()
        var child = root.firstChild
        while (child != null) {
            val writer = StringWriter()
            transformer.transform(DOMSource(child), StreamResult(writer))
            out.append(writer.toString())
            child = child.nextSibling
        }
        // Each top-level element was serialized on its own, so the namespace declaration repeats;
        // the wrapper document declares it once instead.
        return out.toString().replace(" xmlns=\"$XHTML_NS\"", "").trim()
    }

    private const val MAX_DATA_URI = 200_000
}
