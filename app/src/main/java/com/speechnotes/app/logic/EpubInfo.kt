package com.speechnotes.app.logic

import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream

/** One table-of-contents row, resolved to a ZIP entry path. */
data class EpubTocEntry(val label: String, val href: String)

/**
 * What the library list + reader need to know about an EPUB without ever
 * rendering it: metadata, spine (reading order), TOC and cover location.
 * Ported from EpubInfo.swift (speechnotes-ios) — parsed with the platform
 * XML parser only; rendering is epub.js's job.
 */
data class EpubInfo(
    val title: String?,
    val creator: String?,
    val language: String?,
    val spine: List<String>,
    val toc: List<EpubTocEntry>,
    val coverPath: String?,
)

object EpubParser {

    class EpubError(message: String) : Exception(message)

    fun parse(archive: ZipReader): EpubInfo {
        val containerData = archive.text("META-INF/container.xml") ?: throw EpubError("missingContainer")
        val opfPath = ContainerDelegate.run(containerData) ?: throw EpubError("missingContainer")
        val opfData = archive.text(opfPath) ?: throw EpubError("missingOpf: $opfPath")
        val opf = PackageDelegate.run(opfData)

        val opfDir = directory(ofPath = opfPath)
        val manifestByID = opf.manifest
        val spine = opf.spineItemRefs.mapNotNull { id ->
            manifestByID[id]?.href?.let { resolve(it, relativeToDir = opfDir) }
        }

        var coverPath: String? = null
        val coverItem = opf.manifest.values.firstOrNull { "cover-image" in it.properties }
        if (coverItem != null) coverPath = resolve(coverItem.href, relativeToDir = opfDir)
        else if (opf.coverMetaID != null) {
            manifestByID[opf.coverMetaID]?.let { coverPath = resolve(it.href, relativeToDir = opfDir) }
        }

        val toc = toc(opf = opf, archive = archive, opfDir = opfDir)
        return EpubInfo(opf.title, opf.creator, opf.language, spine, toc, coverPath)
    }

    private fun toc(opf: Package, archive: ZipReader, opfDir: String): List<EpubTocEntry> {
        val navItem = opf.manifest.values.firstOrNull { "nav" in it.properties }
        if (navItem != null) {
            val navPath = resolve(navItem.href, relativeToDir = opfDir)
            val navData = archive.text(navPath)
            if (navData != null) {
                val entries = NavDelegate.run(navData)
                if (entries.isNotEmpty()) {
                    val navDir = directory(ofPath = navPath)
                    return entries.map { EpubTocEntry(it.label, resolve(it.href, relativeToDir = navDir)) }
                }
            }
        }
        val ncxHref = opf.spineTocID?.let { opf.manifest[it]?.href }
            ?: opf.manifest.values.firstOrNull { "application/x-dtbncx+xml" in (it.mediaType ?: "") }?.href
        if (ncxHref != null) {
            val ncxPath = resolve(ncxHref, relativeToDir = opfDir)
            val ncxData = archive.text(ncxPath)
            if (ncxData != null) {
                val ncxDir = directory(ofPath = ncxPath)
                return NcxDelegate.run(ncxData).map { EpubTocEntry(it.label, resolve(it.href, relativeToDir = ncxDir)) }
            }
        }
        return emptyList()
    }

    fun directory(ofPath: String): String {
        val slash = ofPath.lastIndexOf('/')
        return if (slash < 0) "" else ofPath.substring(0, slash + 1)
    }

    /** Resolves [href] against [relativeToDir], percent-decoding and normalizing `..`. */
    fun resolve(href: String, relativeToDir: String): String {
        val decoded = try {
            java.net.URLDecoder.decode(href, "UTF-8")
        } catch (e: Exception) {
            href
        }
        val combined = if (relativeToDir.isEmpty()) decoded else relativeToDir + decoded
        val parts = ArrayList<String>()
        for (segment in combined.split("/")) {
            when (segment) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }

    // MARK: - XML delegates

    private fun parseXml(data: String): org.w3c.dom.Document? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            factory.isExpandEntityReferences = false
            val builder = factory.newDocumentBuilder()
            builder.setErrorHandler(null)
            builder.parse(InputSource(ByteArrayInputStream(data.toByteArray(Charsets.UTF_8))))
        } catch (e: Exception) {
            null
        }
    }

    private fun localName(node: Node): String {
        val name = node.nodeName ?: return ""
        return name.substringAfterLast(':')
    }

    private fun textContent(node: Node): String {
        val sb = StringBuilder()
        // Recursive: <dc:title> may wrap <meta> or carry mixed content, and a
        // flat text-node-only walk returned "" for those.
        fun walk(n: Node) {
            val children = n.childNodes
            for (i in 0 until children.length) {
                val child = children.item(i)
                when (child.nodeType) {
                    Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sb.append(child.nodeValue ?: "")
                    Node.ELEMENT_NODE -> walk(child)
                }
            }
        }
        walk(node)
        return sb.toString().trim()
    }

    private fun attr(element: Element, name: String): String? =
        element.getAttribute(name).takeIf { it.isNotEmpty() }

    /** First descendant of [parent] whose LOCAL name is [local], or its text. */
    private fun firstChildText(parent: Node, local: String): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && localName(child) == local) {
                return textContent(child)
            }
        }
        return null
    }

    object ContainerDelegate {
        fun run(data: String): String? {
            val doc = parseXml(data) ?: return null
            val rootFiles = doc.getElementsByTagName("rootfile")
            if (rootFiles.length > 0) {
                val el = rootFiles.item(0) as? Element ?: return null
                return attr(el, "full-path")
            }
            return null
        }
    }

    class ManifestItem(val id: String?, val href: String, val mediaType: String?, val properties: Set<String>)

    class Package(
        var title: String? = null,
        var creator: String? = null,
        var language: String? = null,
        val manifest: MutableMap<String, ManifestItem> = HashMap(),
        val spineItemRefs: MutableList<String> = ArrayList(),
        var spineTocID: String? = null,
        var coverMetaID: String? = null,
    )

    object PackageDelegate {
        fun run(data: String): Package {
            val pkg = Package()
            val doc = parseXml(data) ?: return pkg

            // The metadata elements are namespace-prefixed (dc:title,
            // opf:role). With namespaceAware = FALSE a prefixed element is
            // stored under its FULL node name, so getElementsByTagName("title")
            // matched nothing and every book imported with a null title and
            // null author. Look up by local name, prefix-agnostic.
            val metadata = doc.getElementsByTagName("metadata")
            if (metadata.length > 0) {
                val md = metadata.item(0) as? Element ?: return pkg
                pkg.title = firstChildText(md, "title")?.takeIf { it.isNotEmpty() }
                pkg.creator = firstChildText(md, "creator")?.takeIf { it.isNotEmpty() }
                pkg.language = firstChildText(md, "language")?.takeIf { it.isNotEmpty() }
                // EPUB2 cover: <meta name="cover" content="id">
                val metas = md.getElementsByTagName("meta")
                for (i in 0 until metas.length) {
                    val meta = metas.item(i) as? Element ?: continue
                    if (attr(meta, "name") == "cover") pkg.coverMetaID = attr(meta, "content")
                }
            }

            val items = doc.getElementsByTagName("item")
            for (i in 0 until items.length) {
                val item = items.item(i) as? Element ?: continue
                val id = attr(item, "id") ?: continue
                val href = attr(item, "href") ?: continue
                val mediaType = attr(item, "media-type")
                val props = attr(item, "properties")?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
                pkg.manifest[id] = ManifestItem(id, href, mediaType, props)
            }

            val spines = doc.getElementsByTagName("spine")
            if (spines.length > 0) {
                val spine = spines.item(0) as? Element
                pkg.spineTocID = spine?.let { attr(it, "toc") }
                val refs = spine?.getElementsByTagName("itemref")
                if (refs != null) {
                    for (i in 0 until refs.length) {
                        val ref = refs.item(i) as? Element ?: continue
                        attr(ref, "idref")?.let { pkg.spineItemRefs.add(it) }
                    }
                }
            }
            return pkg
        }
    }

    data class NavEntry(val label: String, val href: String)

    object NavDelegate {
        fun run(data: String): List<NavEntry> {
            val doc = parseXml(data) ?: return emptyList()
            val result = ArrayList<NavEntry>()
            val anchors = doc.getElementsByTagName("a")
            for (i in 0 until anchors.length) {
                val a = anchors.item(i) as? Element ?: continue
                val href = attr(a, "href") ?: continue
                result.add(NavEntry(textContent(a), href))
            }
            return result
        }
    }

    object NcxDelegate {
        fun run(data: String): List<NavEntry> {
            val doc = parseXml(data) ?: return emptyList()
            val result = ArrayList<NavEntry>()
            val points = doc.getElementsByTagName("navPoint")
            for (i in 0 until points.length) {
                val point = points.item(i) as? Element ?: continue
                val labelEls = point.getElementsByTagName("text")
                val label = if (labelEls.length > 0) textContent(labelEls.item(0)) else ""
                val contentEls = point.getElementsByTagName("content")
                val src = if (contentEls.length > 0) (contentEls.item(0) as? Element)?.let { attr(it, "src") } else null
                if (src != null) result.add(NavEntry(label, src))
            }
            return result
        }
    }
}
