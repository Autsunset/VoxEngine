package com.voxengine.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubNovelParserTest {
    @Test
    fun parseEpub3UsesNavigationTitlesAndSpineOrder() {
        val epub = epub(
            "META-INF/container.xml" to containerXml("OEBPS/content.opf"),
            "OEBPS/content.opf" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                  <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="c1" href="text/chapter%201.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="text/chapter2.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="c2"/><itemref idref="c1"/></spine>
                </package>
            """.trimIndent(),
            "OEBPS/nav.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <body><nav epub:type="toc"><ol>
                    <li><a href="text/chapter%201.xhtml#start">第一章 导航标题</a></li>
                    <li><a href="text/chapter2.xhtml">第二章 导航标题</a></li>
                  </ol></nav></body>
                </html>
            """.trimIndent(),
            "OEBPS/text/chapter 1.xhtml" to xhtml("正文标题一", "第一章正文。", "第二段。"),
            "OEBPS/text/chapter2.xhtml" to xhtml("正文标题二", "第二章正文。")
        )

        val chapters = EpubNovelParser.parse(epub)

        assertTrue(EpubNovelParser.isEpub(epub))
        assertEquals(listOf("第二章 导航标题", "第一章 导航标题"), chapters.map { it.title })
        assertTrue(chapters[0].content.contains("第二章正文"))
        assertTrue(chapters[1].content.contains("第一章正文"))
        assertTrue(chapters[1].content.contains("第二段"))
    }

    @Test
    fun parseEpub2UsesNcxTitlesAndRemovesRepeatedHeading() {
        val epub = epub(
            "META-INF/container.xml" to containerXml("OPS/book.opf"),
            "OPS/book.opf" to """
                <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                  <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine toc="ncx"><itemref idref="chapter"/></spine>
                </package>
            """.trimIndent(),
            "OPS/toc.ncx" to """
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
                  <navMap><navPoint><navLabel><text>第一章</text></navLabel><content src="chapter.xhtml"/></navPoint></navMap>
                </ncx>
            """.trimIndent(),
            "OPS/chapter.xhtml" to xhtml("第一章", "正文内容。")
        )

        val chapters = NovelParser.parse(epub, "sample.epub")

        assertEquals(1, chapters.size)
        assertEquals("第一章", chapters.single().title)
        assertEquals("正文内容。", chapters.single().content)
    }

    @Test
    fun plainTextStillUsesTxtParser() {
        val chapters = NovelParser.parse("普通文本内容".toByteArray(), "sample.txt")

        assertFalse(EpubNovelParser.isEpub("普通文本内容".toByteArray()))
        assertEquals("第1章", chapters.single().title)
        assertEquals("普通文本内容", chapters.single().content)
    }

    private fun epub(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/epub+zip".toByteArray())
            zip.closeEntry()
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun xhtml(title: String, vararg paragraphs: String): String = """
        <html xmlns="http://www.w3.org/1999/xhtml">
          <head><title>$title</title></head>
          <body><h1>$title</h1>${paragraphs.joinToString("") { "<p>$it</p>" }}</body>
        </html>
    """.trimIndent()

    private fun containerXml(packagePath: String) = """
        <?xml version="1.0"?>
        <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
          <rootfiles><rootfile full-path="$packagePath" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent()
}
