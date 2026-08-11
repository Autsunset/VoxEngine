package com.voxengine.reader

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

object EpubNovelParser {
    private const val MAX_ENTRY_COUNT = 10_000
    private const val MAX_ENTRY_SIZE = 16 * 1024 * 1024
    private const val MAX_TOTAL_SIZE = 128 * 1024 * 1024
    private val blockTags = setOf(
        "address", "article", "aside", "blockquote", "br", "dd", "div", "dl", "dt",
        "figcaption", "figure", "footer", "h1", "h2", "h3", "h4", "h5", "h6",
        "header", "hr", "li", "main", "nav", "ol", "p", "pre", "section", "table",
        "td", "th", "tr", "ul"
    )

    fun isEpub(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            runCatching { hasContainerEntry(bytes) }.getOrDefault(false)

    fun parse(bytes: ByteArray): List<TxtChapter> {
        val entries = readArchive(bytes)
        val container = entries["META-INF/container.xml"]
            ?: throw IllegalArgumentException("不是有效的 EPUB 文件：缺少 container.xml")
        val containerDoc = parseXml(container)
        val packagePath = containerDoc.selectFirst("rootfile[full-path]")?.attr("full-path")
            ?.let(::decodePath)?.let(::normalizeArchivePath)
            ?: throw IllegalArgumentException("EPUB 未声明内容清单")
        val packageBytes = entries[packagePath]
            ?: throw IllegalArgumentException("EPUB 内容清单不存在")
        val packageDoc = parseXml(packageBytes)
        val packageDir = packagePath.substringBeforeLast('/', "")

        val manifest = packageDoc.select("manifest > item[id][href]").associate { item ->
            item.attr("id") to ManifestItem(
                path = resolvePath(packageDir, item.attr("href")),
                mediaType = item.attr("media-type"),
                properties = item.attr("properties").split(Regex("\\s+")).filter(String::isNotBlank).toSet()
            )
        }
        val spine = packageDoc.select("spine > itemref[idref]")
            .mapNotNull { manifest[it.attr("idref")] }
            .filter { it.mediaType == "application/xhtml+xml" || it.mediaType == "text/html" }
        if (spine.isEmpty()) throw IllegalArgumentException("EPUB 没有可阅读的正文")

        val tocTitles = buildTocTitles(entries, packageDoc, manifest, packageDir)
        val chapters = spine.mapIndexedNotNull { index, item ->
            val content = entries[item.path] ?: return@mapIndexedNotNull null
            val document = parseXml(content, item.path)
            document.select("script, style, svg, nav[epub|type=toc]").remove()
            val text = extractText(document)
            if (text.isBlank()) return@mapIndexedNotNull null
            val title = tocTitles[item.path]
                ?: document.selectFirst("h1, h2, h3, title")?.text()?.trim()?.takeIf(String::isNotBlank)
                ?: "第${index + 1}章"
            TxtChapter(title = title, content = removeRepeatedTitle(text, title))
        }
        if (chapters.isEmpty()) throw IllegalArgumentException("EPUB 正文为空")
        return chapters
    }

    private fun buildTocTitles(
        entries: Map<String, ByteArray>,
        packageDoc: Document,
        manifest: Map<String, ManifestItem>,
        packageDir: String
    ): Map<String, String> {
        val navItem = manifest.values.firstOrNull { "nav" in it.properties }
        if (navItem != null) {
            val navDoc = entries[navItem.path]?.let { parseXml(it, navItem.path) }
            val navTitles = navDoc?.select("nav[epub|type=toc] a[href], nav[type=toc] a[href], nav a[href]")
                ?.associateNotNull { link ->
                    val title = link.text().trim().takeIf(String::isNotBlank) ?: return@associateNotNull null
                    resolvePath(navItem.path.substringBeforeLast('/', ""), link.attr("href")) to title
                }.orEmpty()
            if (navTitles.isNotEmpty()) return navTitles
        }

        val tocId = packageDoc.selectFirst("spine[toc]")?.attr("toc")
        val ncxItem = tocId?.let(manifest::get)
            ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        val ncxDoc = ncxItem?.let { item -> entries[item.path]?.let(::parseXml) }
        return ncxDoc?.select("navPoint")?.associateNotNull { point ->
            val href = point.selectFirst("content[src]")?.attr("src") ?: return@associateNotNull null
            val title = point.selectFirst("navLabel > text")?.text()?.trim()?.takeIf(String::isNotBlank)
                ?: return@associateNotNull null
            resolvePath(ncxItem.path.substringBeforeLast('/', ""), href) to title
        }.orEmpty()
    }

    private fun extractText(document: Document): String {
        val body = document.body().takeIf { it.childrenSize() > 0 } ?: document
        val output = StringBuilder()
        body.traverse { node, depth ->
            when (node) {
                is org.jsoup.nodes.TextNode -> output.append(node.text())
                is Element -> if (node.tagName().lowercase() in blockTags && output.isNotEmpty()) output.append('\n')
            }
        }
        return output.toString()
            .replace('\u00A0', ' ')
            .lineSequence()
            .map { it.trim().replace(Regex("[ \\t]+"), " ") }
            .filter(String::isNotBlank)
            .joinToString("\n")
    }

    private fun removeRepeatedTitle(text: String, title: String): String {
        val lines = text.lines()
        return if (lines.firstOrNull()?.trim() == title.trim()) lines.drop(1).joinToString("\n").trim() else text
    }

    private fun readArchive(bytes: ByteArray): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var totalSize = 0L
        var entryCount = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount += 1
                if (entryCount > MAX_ENTRY_COUNT) throw IllegalArgumentException("EPUB 文件条目过多")
                if (!entry.isDirectory) {
                    val path = normalizeArchivePath(decodePath(entry.name))
                    val content = zip.readBytesLimited(MAX_ENTRY_SIZE)
                    totalSize += content.size
                    if (totalSize > MAX_TOTAL_SIZE) throw IllegalArgumentException("EPUB 解压后内容过大")
                    entries[path] = content
                }
                zip.closeEntry()
            }
        }
        return entries
    }

    private fun hasContainerEntry(bytes: ByteArray): Boolean {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            repeat(MAX_ENTRY_COUNT) {
                val entry = zip.nextEntry ?: return false
                if (normalizeArchivePath(decodePath(entry.name)) == "META-INF/container.xml") return true
                zip.closeEntry()
            }
        }
        return false
    }

    private fun ZipInputStream.readBytesLimited(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IllegalArgumentException("EPUB 单个文件过大")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun parseXml(bytes: ByteArray, baseUri: String = ""): Document =
        Jsoup.parse(ByteArrayInputStream(bytes), null, baseUri, Parser.xmlParser())

    private fun resolvePath(baseDir: String, href: String): String {
        val cleanHref = decodePath(href.substringBefore('#').substringBefore('?'))
        return normalizeArchivePath(listOf(baseDir, cleanHref).filter(String::isNotBlank).joinToString("/"))
    }

    private fun normalizeArchivePath(path: String): String {
        val parts = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex) else throw IllegalArgumentException("EPUB 包含非法路径")
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun decodePath(path: String): String =
        URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8.name())

    private inline fun <T, K, V> Iterable<T>.associateNotNull(transform: (T) -> Pair<K, V>?): Map<K, V> =
        buildMap { for (item in this@associateNotNull) transform(item)?.let { put(it.first, it.second) } }

    private data class ManifestItem(
        val path: String,
        val mediaType: String,
        val properties: Set<String>
    )
}
