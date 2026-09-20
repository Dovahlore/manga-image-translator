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

    private data class IndexData(val books: List<Book>, val folders: List<Folder>)

    suspend fun books(): List<Book> = withContext(Dispatchers.IO) { readIndexData().books }

    suspend fun folders(): List<Folder> = withContext(Dispatchers.IO) { readIndexData().folders }

    suspend fun book(id: String): Book? = withContext(Dispatchers.IO) { readIndexData().books.find { it.id == id } }

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
        val d = readIndexData()
        writeIndex(d.books + book, d.folders)
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
        val d = readIndexData()
        writeIndex(d.books.filterNot { it.id == id }, d.folders)
    }

    suspend fun setMode(id: String, mode: ReadingMode) = withContext(Dispatchers.IO) {
        val d = readIndexData()
        writeIndex(d.books.map { if (it.id == id) it.copy(mode = mode) else it }, d.folders)
    }

    // ---------------------------------------------------------------- 收藏夹

    suspend fun createFolder(name: String): Folder = withContext(Dispatchers.IO) {
        val d = readIndexData()
        val f = Folder(UUID.randomUUID().toString(), name.trim().ifBlank { "未命名" })
        writeIndex(d.books, d.folders + f)
        f
    }

    suspend fun renameFolder(id: String, name: String) = withContext(Dispatchers.IO) {
        val d = readIndexData()
        writeIndex(d.books, d.folders.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    suspend fun deleteFolder(id: String) = withContext(Dispatchers.IO) {
        val d = readIndexData()
        writeIndex(
            d.books.map { if (it.folderId == id) it.copy(folderId = null) else it },
            d.folders.filterNot { it.id == id },
        )
    }

    /** 把书移进/移出收藏夹。folderId 传 null 表示移到「未分类」。 */
    suspend fun moveBook(bookId: String, folderId: String?) = withContext(Dispatchers.IO) {
        val d = readIndexData()
        writeIndex(d.books.map { if (it.id == bookId) it.copy(folderId = folderId) else it }, d.folders)
    }

    /** 某本书某页的译文缓存文件（本地缓存，服务端 14 天会删，这里留着）。 */
    fun translatedCacheFile(bookId: String, pageIndex: Int): File =
        File(File(translatedRoot, bookId), pageIndex.toString().padStart(3, '0') + ".png")

    fun epubFile(bookId: String): File = File(File(root, bookId), "book.src")

    // ---------------------------------------------------------------- index 持久化

    private fun readIndexData(): IndexData {
        if (!indexFile.exists()) return IndexData(emptyList(), emptyList())
        val text = indexFile.readText()
        // 老版本是纯数组（只有 books），迁移成新格式
        runCatching { JSONArray(text) }.getOrNull()?.let { return IndexData(parseBooks(it), emptyList()) }
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return IndexData(emptyList(), emptyList())
        val books = obj.optJSONArray("books")?.let { parseBooks(it) } ?: emptyList()
        val folders = obj.optJSONArray("folders")?.let { fa ->
            (0 until fa.length()).map { i ->
                val o = fa.getJSONObject(i)
                Folder(o.getString("id"), o.optString("name", "未命名"))
            }
        } ?: emptyList()
        return IndexData(books, folders)
    }

    private fun parseBooks(arr: JSONArray): List<Book> {
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
                folderId = if (o.isNull("folder_id")) null else o.optString("folder_id").takeIf { it.isNotBlank() },
            )
        }
        return out
    }

    private fun writeIndex(books: List<Book>, folders: List<Folder>) {
        val obj = JSONObject()
        val barr = JSONArray()
        books.forEach { b ->
            barr.put(JSONObject().apply {
                put("id", b.id)
                put("title", b.title)
                put("mode", if (b.mode == ReadingMode.NORMAL) "normal" else "manga")
                b.folderId?.let { put("folder_id", it) }
            })
        }
        obj.put("books", barr)
        val farr = JSONArray()
        folders.forEach { f ->
            farr.put(JSONObject().apply { put("id", f.id); put("name", f.name) })
        }
        obj.put("folders", farr)
        indexFile.writeText(obj.toString())
    }
}
