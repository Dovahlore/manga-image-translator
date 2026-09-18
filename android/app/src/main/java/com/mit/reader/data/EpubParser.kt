package com.mit.reader.data

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry

/**
 * 极简 EPUB 解析：只针对"图页型"漫画 EPUB（每页一张图，如 vol.moe / Kmoe 那种）。
 * 做法：读 OPF 的 spine 顺序 → 逐页 XHTML 里取 <img src> / <image xlink:href> → 按序抽图。
 * 普通文字型 EPUB 需要 HTML 渲染，本解析器不覆盖（封面也取第一页图）。
 */
object EpubParser {

    private val IMG_EXT = setOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp")

    fun extract(epub: File, bookDir: File): ParsedBook {
        val pagesDir = File(bookDir, "pages").apply { mkdirs() }
        ZipFile(epub).use { zip ->
            val opfName = zip.entries().asSequence()
                .map { it.name }
                .first { it.lowercase().endsWith(".opf") }
            val opf = zip.readText(zip.getEntry(opfName))
            val opfDir = opfName.substringBeforeLast('/', "")

            val (idToHref, spine) = parseOpf(opf)
            var title = parseTitle(opf) ?: epub.nameWithoutExtension

            val out = mutableListOf<File>()
            var idx = 0
            for (doc in spine) {
                val docPath = if (opfDir.isEmpty()) doc else "$opfDir/$doc"
                val text = zip.entryOrNull(docPath)?.let { zip.readText(it) } ?: continue
                val docDir = doc.substringBeforeLast('/', "")
                for (href in docImageRefs(text)) {
                    val abs = resolve(docDir, href) ?: continue
                    val entry = zip.entryOrNull(abs) ?: continue
                    val ext = abs.substringAfterLast('.', ".jpg").lowercase()
                        .let { if (it in IMG_EXT) it else ".jpg" }
                    idx++
                    val dest = File(pagesDir, "page-${idx.toString().padStart(3, '0')}.$ext")
                    zip.getInputStream(entry).use { it.copyTo(dest.outputStream()) }
                    out += dest
                }
            }
            if (out.isEmpty()) throw IllegalStateException("这个 EPUB 里没找到图页（可能是纯文字书）")
            return ParsedBook(title, out)
        }
    }

    // ---------------------------------------------------------------- OPF

    private fun parseOpf(opf: String): Pair<Map<String, String>, List<String>> {
        val idToHref = LinkedHashMap<String, String>()
        for (tag in tags(opf, "item")) {
            val id = attr(tag, "id")
            val href = attr(tag, "href")
            if (id != null && href != null) idToHref[id] = href
        }
        val spine = tags(opf, "itemref").mapNotNull { idToHref[attr(it, "idref")] }
        return idToHref to spine
    }

    private fun parseTitle(opf: String): String? {
        // 优先 <dc:title>，退而求其次 <meta name="calibre:title">
        Regex("""<dc:title[^>]*>(.*?)</dc:title>""", RegexOption.DOT_MATCHES_ALL)
            .find(opf)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        Regex("""content="([^"]+)""").find(
            Regex("""<meta[^>]*name="calibre:title"[^>]*>""").find(opf)?.value ?: ""
        )?.groupValues?.get(1)?.let { return it }
        return null
    }

    // ---------------------------------------------------------------- 通用

    private fun tags(xml: String, name: String): List<String> =
        Regex("""<$name\s[^>]*?/?>""").findAll(xml).map { it.value }.toList()

    private fun attr(tag: String, name: String): String? =
        Regex("""$name\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1)

    private fun docImageRefs(html: String): List<String> {
        val refs = mutableListOf<String>()
        // <img src="...">（普通 EPUB）
        Regex("""<img\s[^>]*?src\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { refs += it.groupValues[1] }
        // <image xlink:href="...">（SVG 型 EPUB）
        Regex("""<image\s[^>]*?xlink:href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { refs += it.groupValues[1] }
        return refs
    }

    private fun resolve(dir: String, href: String): String? {
        if (href.isBlank() || href.startsWith("data:") || href.startsWith("http")) return null
        val clean = href.substringBefore('#')
        val parts = (if (dir.isEmpty()) listOf() else dir.split('/')) + clean.split('/')
        val stack = ArrayDeque<String>()
        for (p in parts) {
            when (p) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(p)
            }
        }
        return stack.joinToString("/")
    }

    private fun ZipFile.entryOrNull(name: String): ZipEntry? =
        getEntry(name) ?: getEntry(name.replace('/', '\\'))

    private fun ZipFile.readText(entry: ZipEntry): String =
        getInputStream(entry).bufferedReader(Charsets.UTF_8).readText()
}
