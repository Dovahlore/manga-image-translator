package com.mit.reader.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class LibraryRepository(private val context: Context) {
    private val root = File(context.filesDir, "library").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val translatedRoot = File(context.filesDir, "translated").apply { mkdirs() }

    suspend fun books(): List<Book> = withContext(Dispatchers.IO) { readIndex() }

    suspend fun book(id: String): Book? = withContext(Dispatchers.IO) { readIndex().find { it.id == id } }

    suspend fun import(uri: Uri): Book = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val src = File(dir, "book.src")
        context.contentResolver.openInputStream(uri)?.use { it.copyTo(src.outputStream()) }
            ?: throw IllegalStateException("无法读取所选文件")

        val r = when (sniffFormat(src)) {
            "mobi" -> MobiParser.extract(src, dir)
            else -> EpubParser.extract(src, dir)
        }
        val book = Book(id, r.title, ReadingMode.MANGA, r.pages.size, r.pages.first(), r.pages)
        val all = readIndex().toMutableList().apply { add(book) }
        writeIndex(all)
        book
    }

    /** 按文件头嗅探格式：MOBI 的 PDB 头 60..68 字节是 "BOOK"+"MOBI"，EPUB 是 "PK\x03\x04"。 */
    private fun sniffFormat(f: File): String {
        f.inputStream().use { ins ->
            val head = ByteArray(68)
            val n = ins.read(head)
            if (n >= 68) {
                if (String(head, 60, 4, Charsets.US_ASCII) == "BOOK" &&
                    String(head, 64, 4, Charsets.US_ASCII) == "MOBI"
                ) return "mobi"
            }
            if (n >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
                head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
            ) return "epub"
        }
        // 退路：按扩展名
        return when (f.extension.lowercase()) {
            "mobi", "azw", "azw3", "prc" -> "mobi"
            else -> "epub"
        }
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(root, id).deleteRecursively()
        File(translatedRoot, id).deleteRecursively()
        writeIndex(readIndex().filterNot { it.id == id })
    }

    suspend fun setMode(id: String, mode: ReadingMode) = withContext(Dispatchers.IO) {
        val all = readIndex().map { if (it.id == id) it.copy(mode = mode) else it }
        writeIndex(all)
    }

    /** 某本书某页的译文缓存文件（本地缓存，服务端 14 天会删，这里留着）。 */
    fun translatedCacheFile(bookId: String, pageIndex: Int): File =
        File(File(translatedRoot, bookId), pageIndex.toString().padStart(3, '0') + ".png")

    fun epubFile(bookId: String): File = File(File(root, bookId), "book.src")

    // ---------------------------------------------------------------- index 持久化

    private fun readIndex(): List<Book> {
        if (!indexFile.exists()) return emptyList()
        val arr = runCatching { JSONArray(indexFile.readText()) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Book>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val pagesDir = File(root, "$id/pages")
            val pages = pagesDir.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
            if (pages.isEmpty()) continue
            out += Book(
                id = id,
                title = o.optString("title", "未命名"),
                mode = if (o.optString("mode") == "normal") ReadingMode.NORMAL else ReadingMode.MANGA,
                pageCount = pages.size,
                coverFile = pages.first(),
                pageFiles = pages,
            )
        }
        return out
    }

    private fun writeIndex(books: List<Book>) {
        val arr = JSONArray()
        books.forEach { b ->
            arr.put(JSONObject().apply {
                put("id", b.id)
                put("title", b.title)
                put("mode", if (b.mode == ReadingMode.NORMAL) "normal" else "manga")
            })
        }
        indexFile.writeText(arr.toString())
    }
}
