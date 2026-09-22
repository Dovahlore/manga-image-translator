package com.mit.reader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class LibraryRepository(private val context: Context) {
    private val root = File(context.filesDir, "library").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val translatedRoot = File(context.filesDir, "translated").apply { mkdirs() }
    private val cloudCovers = File(context.filesDir, "cloud_covers").apply { mkdirs() }
    private val progressPrefs = context.getSharedPreferences("reading_progress", Context.MODE_PRIVATE)
    private val api = TranslationApi()

    private data class IndexData(val books: List<Book>, val folders: List<Folder>)

    /** 导入结果：book 为最终那本书（重复时是已存在的那本）；duplicate 表示内容已存在、未新建。 */
    data class ImportResult(val book: Book, val duplicate: Boolean)

    suspend fun books(): List<Book> = withContext(Dispatchers.IO) { readIndexData().books }

    suspend fun folders(): List<Folder> = withContext(Dispatchers.IO) { readIndexData().folders }

    suspend fun book(id: String): Book? = withContext(Dispatchers.IO) { readIndexData().books.find { it.id == id } }

    /** 按书名查本地书（WebView 下载前查重，避免重复下载已有书）。 */
    suspend fun bookByTitle(title: String): Book? = withContext(Dispatchers.IO) {
        val t = title.trim()
        if (t.isBlank()) null else readIndexData().books.firstOrNull { it.title == t }
    }

    suspend fun import(uri: Uri): Book = withContext(Dispatchers.IO) {
        val name = displayNameOf(uri)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("无法读取所选文件")
        importFromStream(input, name).book
    }

    /** 从本地文件导入（书库默认文件夹 / WebView 下载后的文件用）。返回是否命中已存在（重复）。 */
    suspend fun importFile(file: File): ImportResult = withContext(Dispatchers.IO) {
        importFromStream(file.inputStream(), file.name)
    }

    private suspend fun importFromStream(input: InputStream, name: String?): ImportResult {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val src = File(dir, "book.src")
        // 书名优先用「文件名」——那才是用户在文件管理器里认得的名字；
        // 书内部元数据的标题常常和文件名对不上（比如 [Kmoe][尼古喵喵]卷01 内部叫「雅尼貓 - 卷01」）
        val fromName = titleFromFileName(name)
        input.use { it.copyTo(src.outputStream()) }

        val r = when (sniffFormat(src)) {
            "mobi" -> MobiParser.extract(src, dir)
            else -> EpubParser.extract(src, dir)
        }
        val hash = sha256(src)
        val fp = fingerprint(r.pages)
        val d = readIndexData()
        // 去重：同样源文件哈希的书已存在 → 直接返回已存在的那本，删掉刚导入的副本
        d.books.firstOrNull { it.hash.isNotEmpty() && it.hash == hash }?.let { existing ->
            dir.deleteRecursively()
            return ImportResult(existing, true)
        }
        // 云端识别：同 hash 的云端书直接挂 cloudId（丢进 BOOKS 的、和云端同 hash 的书 → 识别成云端书）
        val cloudId = runCatching { api.cloudLookup(hash) }.getOrNull()
        val book = Book(
            id = id,
            title = fromName.ifBlank { r.title },
            mode = ReadingMode.MANGA,
            pageCount = r.pages.size,
            coverFile = r.pages.first(),
            pageFiles = r.pages,
            hash = hash,
            fingerprint = fp,
            cloudId = cloudId,
            createdAt = System.currentTimeMillis(),
        )
        writeIndex(d.books + book, d.folders)
        return ImportResult(book, false)
    }

    /** 默认书库文件夹（App 自己的外部存储 books 目录，安装即存在、无需授权）。 */
    fun defaultBooksDir(): File = File(context.getExternalFilesDir(null), "books").apply { mkdirs() }

    /** 下载一个文件到默认书库文件夹（WebView 下载用，带 Cookie/Referer/UA 以便通过站点校验），返回落盘文件。 */
    suspend fun downloadToBooks(
        url: String,
        filename: String,
        cookie: String?,
        referer: String?,
        userAgent: String?,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        val safeName = filename.substringAfterLast('/').ifBlank { "download_${System.currentTimeMillis()}" }
        val f = File(defaultBooksDir(), safeName)
        val headers = mutableMapOf<String, String>()
        cookie?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
        referer?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        userAgent?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        api.download(url, f, onProgress = onProgress, extraHeaders = headers)
        f
    }

    /** 扫描「用户选的书库文件夹」（SAF）里的 epub/mobi，导入新书（按内容 hash 去重）。
     *  默认书库文件夹（App 私有）不扫：WebView 下载后已直接导入，那里只是暂存。 */
    suspend fun scanLibraryFolder(): Int = withContext(Dispatchers.IO) {
        var imported = 0
        val uriStr = ServerConfig.libraryFolderUri
        if (uriStr.isNullOrBlank()) return@withContext 0
        val folder = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) }.getOrNull()
            ?: return@withContext 0
        for (f in folder.listFiles()) {
            if (!f.isFile) continue
            val name = f.name ?: continue
            if (!name.endsWith(".epub", ignoreCase = true) && !name.endsWith(".mobi", ignoreCase = true)) continue
            runCatching { import(f.uri) }
                .onSuccess { book ->
                    imported++
                    recordSourceFile(book.hash, name)
                }
        }
        imported
    }

    private val sourcePrefs get() = context.getSharedPreferences("source_files", Context.MODE_PRIVATE)

    private fun sourceFiles(): MutableMap<String, String> {
        val raw = sourcePrefs.getString("map", null) ?: return mutableMapOf()
        return runCatching {
            val o = JSONObject(raw)
            val m = mutableMapOf<String, String>()
            o.keys().forEach { k -> m[k] = o.optString(k) }
            m
        }.getOrDefault(mutableMapOf())
    }

    private fun persistSourceFiles(map: Map<String, String>) {
        val o = JSONObject()
        map.forEach { (k, v) -> o.put(k, v) }
        sourcePrefs.edit().putString("map", o.toString()).apply()
    }

    private fun recordSourceFile(hash: String, name: String) {
        val m = sourceFiles()
        m[hash] = name
        persistSourceFiles(m)
    }

    private fun removeSourceFile(hash: String) {
        val m = sourceFiles()
        m.remove(hash)
        persistSourceFiles(m)
    }

    private fun sourceFileName(hash: String): String? = sourceFiles()[hash]

    /** 取 ContentResolver 里的原始文件名（例：[Kmoe][尼古喵喵]卷01.epub）。 */
    private fun displayNameOf(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    /** 文件名 → 书名：只去掉扩展名，其余（含 [epub]/[Kmoe] 这类方括号）原样保留，不做任何消除。 */
    fun titleFromFileName(name: String?): String {
        if (name.isNullOrBlank()) return ""
        return name.substringBeforeLast('.').trim()
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

    /** 删书时同步删除源文件（默认书库文件夹 + SAF 文件夹都查，按内容 hash 定位文件名）。 */
    suspend fun deleteSourceFile(hash: String): Boolean = withContext(Dispatchers.IO) {
        if (hash.isBlank()) return@withContext false
        val targetName = sourceFileName(hash) ?: return@withContext false

        // 1) 默认书库文件夹
        val f1 = File(defaultBooksDir(), targetName)
        if (f1.exists()) {
            val ok = f1.delete()
            if (ok) removeSourceFile(hash)
            return@withContext ok
        }

        // 2) SAF 文件夹
        val uriStr = ServerConfig.libraryFolderUri
        if (!uriStr.isNullOrBlank()) {
            val folder = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uriStr)) }.getOrNull()
            if (folder != null) {
                for (f in folder.listFiles()) {
                    if (f.isFile && f.name == targetName) {
                        val ok = runCatching { f.delete() }.getOrDefault(false)
                        if (ok) removeSourceFile(hash)
                        return@withContext ok
                    }
                }
            }
        }
        false
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
        val newName = name.trim()
        writeIndex(d.books, d.folders.map { if (it.id == id) it.copy(name = newName) else it })
        // 已同步书：该夹下所有同步书的云端 folder 名跟着改
        for (b in d.books) {
            if (b.folderId == id && b.cloudId != null) runCatching { api.cloudUpdateFolder(b.cloudId, newName) }
        }
    }

    suspend fun deleteFolder(id: String) = withContext(Dispatchers.IO) {
        val d = readIndexData()
        writeIndex(
            d.books.map { if (it.folderId == id) it.copy(folderId = null) else it },
            d.folders.filterNot { it.id == id },
        )
        // 已同步书：该夹下同步书移出未分类
        for (b in d.books) {
            if (b.folderId == id && b.cloudId != null) runCatching { api.cloudUpdateFolder(b.cloudId, null) }
        }
    }

    /** 把书移进/移出收藏夹。folderId 传 null 表示移到「未分类」。 */
    suspend fun moveBook(bookId: String, folderId: String?) = withContext(Dispatchers.IO) {
        val d = readIndexData()
        writeIndex(d.books.map { if (it.id == bookId) it.copy(folderId = folderId) else it }, d.folders)
        // 已同步的书：收藏夹变化同步到云端
        val book = d.books.find { it.id == bookId } ?: return@withContext
        if (book.cloudId != null) {
            val name = folderId?.let { fid -> d.folders.find { it.id == fid }?.name }
            runCatching { api.cloudUpdateFolder(book.cloudId, name) }
        }
    }

    /** 重命名书名（空白则忽略）。 */
    suspend fun renameBook(bookId: String, title: String) = withContext(Dispatchers.IO) {
        val t = title.trim()
        if (t.isBlank()) return@withContext
        val d = readIndexData()
        writeIndex(d.books.map { if (it.id == bookId) it.copy(title = t) else it }, d.folders)
    }

    // ---------------------------------------------------------------- 云同步

    /** 同步一本本地书：打包 zip → 上传 → 记录 cloudId。返回更新后的书。
     *  onProgress(阶段文案, 0..1 进度；null=不确定)。 */
    suspend fun sync(book: Book, onProgress: ((String, Float?) -> Unit)? = null): Book = withContext(Dispatchers.IO) {
        onProgress?.invoke("打包中…", null)
        val zipFile = File(context.cacheDir, "sync-${book.id}.zip")
        if (zipFile.exists()) zipFile.delete()
        packBook(book, zipFile)
        val folderName = book.folderId?.let { fid -> readIndexData().folders.find { it.id == fid }?.name }
        val resp = api.cloudUpload(
            zip = zipFile,
            title = book.title,
            folder = folderName,
            mode = if (book.mode == ReadingMode.NORMAL) "normal" else "manga",
            hash = book.hash.ifBlank { sha256(epubFile(book.id)) },
            fingerprint = book.fingerprint,
            pageCount = book.pageCount,
            oldBookId = book.id,   // 先翻译后同步：把本地 UUID 下的旧译文迁到 cloudId
            onProgress = { sent, total -> onProgress?.invoke("上传中…", if (total > 0) sent.toFloat() / total else null) },
        )
        zipFile.delete()
        // 缓存封面：删本地后云端 tab 仍能显示封面
        runCatching { book.coverFile.copyTo(cloudCoverFile(resp.bookId), overwrite = true) }
        val d = readIndexData()
        writeIndex(d.books.map { if (it.id == book.id) it.copy(cloudId = resp.bookId) else it }, d.folders)
        readIndexData().books.find { it.id == book.id } ?: book
    }

    /** 取消同步：删云端（含翻译结果），本地保留，清空 cloudId。
     *  云端删除失败（离线/网络）会抛异常，由调用方处理；此时本地 cloudId 不清，书仍算已同步，可稍后重试。 */
    suspend fun cancelSync(book: Book): Book = withContext(Dispatchers.IO) {
        val cloudId = book.cloudId ?: return@withContext book
        api.cloudDelete(cloudId)   // 失败抛异常，不吞掉
        cloudCoverFile(cloudId).delete()
        val d = readIndexData()
        writeIndex(d.books.map { if (it.id == book.id) it.copy(cloudId = null) else it }, d.folders)
        readIndexData().books.find { it.id == book.id } ?: book
    }

    /** 从云端下载一本书并还原到本地（页面 + 译文缓存 + 归属文件夹），cloudId 保持云端 id。
     *  onProgress(阶段文案, 0..1 进度；null=不确定)。 */
    suspend fun downloadCloud(cloud: CloudBook, onProgress: ((String, Float?) -> Unit)? = null): Book = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        val zipFile = File(context.cacheDir, "dl-${cloud.id}.zip")
        if (zipFile.exists()) zipFile.delete()
        api.cloudDownload(cloud.id, zipFile) { got, total ->
            onProgress?.invoke("下载中…", if (total > 0) got.toFloat() / total else null)
        }
        onProgress?.invoke("还原中…", null)

        var title = cloud.title
        var mode = ReadingMode.MANGA
        var folderName: String? = cloud.folder
        val pagesDir = File(dir, "pages").apply { mkdirs() }
        val translatedDir = File(translatedRoot, id).apply { mkdirs() }

        ZipFile(zipFile).use { zip ->
            zip.getEntry("manifest.json")?.let { e ->
                val m = JSONObject(zip.getInputStream(e).bufferedReader(Charsets.UTF_8).readText())
                title = m.optString("title").takeIf { it.isNotBlank() } ?: title
                mode = if (m.optString("mode") == "normal") ReadingMode.NORMAL else ReadingMode.MANGA
                folderName = m.optString("folder").takeIf { it.isNotBlank() } ?: folderName
            }
            for (e in zip.entries()) {
                if (e.isDirectory) continue
                val name = e.name
                when {
                    name == "book.src" ->
                        zip.getInputStream(e).use { it.copyTo(File(dir, "book.src").outputStream()) }
                    name.startsWith("pages/") ->
                        zip.getInputStream(e).use { it.copyTo(File(pagesDir, name.substringAfterLast('/')).outputStream()) }
                    name.startsWith("translated/") ->
                        zip.getInputStream(e).use { it.copyTo(File(translatedDir, name.substringAfterLast('/')).outputStream()) }
                }
            }
        }
        zipFile.delete()

        val pages = pagesDir.listFiles { f -> f.isFile }?.sortedBy { it.name } ?: emptyList()
        if (pages.isEmpty()) throw IllegalStateException("云端书 zip 里没有页面")

        val srcFile = File(dir, "book.src")
        val hash = if (srcFile.exists()) sha256(srcFile) else (cloud.hash ?: "")
        val fp = if (pages.isNotEmpty()) fingerprint(pages) else ""

        val d = readIndexData()
        var folders = d.folders
        var folderId: String? = null
        if (!folderName.isNullOrBlank()) {
            folderId = folders.find { it.name == folderName }?.id
            if (folderId == null) {
                val f = Folder(UUID.randomUUID().toString(), folderName)
                folders = folders + f
                folderId = f.id
            }
        }
        val book = Book(
            id = id,
            title = title ?: "未命名",
            mode = mode,
            pageCount = pages.size,
            coverFile = pages.first(),
            pageFiles = pages,
            folderId = folderId,
            cloudId = cloud.id,
            hash = hash,
            fingerprint = fp,
        )

        // 译文不打进 zip：直接从服务端拉最新结果（云端书永久保留，bookPages 永远查得到）。
        // 这样别的设备新翻/重翻的页，下载到本机时拿到的就是最新的译文。
        runCatching { api.bookPages(cloud.id) }.getOrNull()?.forEach { p ->
            if (p.status == "done" && p.pageIndex in pages.indices) {
                val f = translatedCacheFile(id, p.pageIndex)
                f.parentFile?.mkdirs()
                runCatching { api.download(api.translatedUrl(p.id), f) }
            }
        }

        // 缓存封面
        runCatching { pages.first().copyTo(cloudCoverFile(cloud.id), overwrite = true) }

        writeIndex(d.books + book, folders)
        book
    }

    /** 从服务端拉该书已翻好的页到本地译文缓存（只补差异，不全量）。
     *  overwrite=true 强制覆盖本地（别的设备重翻后同步）；false 只下载「本地缺失」或「服务端指纹变了」的页。
     *  返回本次已就绪的页索引。 */
    suspend fun refreshTranslations(book: Book, overwrite: Boolean): Set<Int> = withContext(Dispatchers.IO) {
        val done = mutableSetOf<Int>()
        val pages = runCatching { api.bookPages(book.serverId) }.getOrNull() ?: return@withContext done
        val metaFile = syncMetaFile(book.id)
        val meta = readSyncMeta(metaFile)
        var metaChanged = false
        for (p in pages) {
            if (p.status != "done" || p.pageIndex !in book.pageFiles.indices) continue
            val f = translatedCacheFile(book.id, p.pageIndex)
            val fp = "${p.configHash}|${p.updatedAt}"
            // 只补差异：本地没有 / 空文件 / 服务端指纹变了（多设备重翻导致）
            val need = overwrite || !f.exists() || f.length() == 0L || meta[p.pageIndex] != fp
            if (!need) { done += p.pageIndex; continue }
            runCatching { downloadTranslatedPage(book.id, p.id, p.pageIndex) }.onSuccess {
                done += p.pageIndex
                meta[p.pageIndex] = fp
                metaChanged = true
            }
        }
        if (metaChanged) writeSyncMeta(metaFile, meta)
        done
    }

    /** 每本书的译文同步指纹（pageIndex → config_hash|updated_at），判断该页要不要重新拉。 */
    private fun syncMetaFile(bookId: String): File = File(File(translatedRoot, bookId), "sync_meta.json")

    private fun readSyncMeta(f: File): MutableMap<Int, String> {
        val m = mutableMapOf<Int, String>()
        if (!f.exists()) return m
        runCatching {
            val o = JSONObject(f.readText())
            o.keys().forEach { k -> k.toIntOrNull()?.let { m[it] = o.optString(k) } }
        }
        return m
    }

    private fun writeSyncMeta(f: File, meta: Map<Int, String>) {
        runCatching {
            val o = JSONObject()
            meta.forEach { (k, v) -> o.put(k.toString(), v) }
            f.parentFile?.mkdirs()
            f.writeText(o.toString())
        }
    }

    /** 打包：book.src + pages 目录 + manifest.json（译文不打进 zip，直接从服务端拉最新）。 */
    private fun packBook(book: Book, zipFile: File) {
        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            fun add(name: String, file: File) {
                if (!file.exists()) return
                zos.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
            val manifest = JSONObject().apply {
                put("title", book.title)
                put("mode", if (book.mode == ReadingMode.NORMAL) "normal" else "manga")
                put("page_count", book.pageCount)
                put("hash", book.hash)
                put("fingerprint", book.fingerprint)
            }
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            add("book.src", epubFile(book.id))
            for (f in book.pageFiles) add("pages/${f.name}", f)
        }
    }

    private fun sha256(f: File): String {
        if (!f.exists()) return ""
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun fingerprint(pages: List<File>): String {
        val cover = pages.firstOrNull() ?: return ""
        val md = MessageDigest.getInstance("SHA-256")
        md.update(pages.size.toString().toByteArray(Charsets.UTF_8))
        cover.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            val n = ins.read(buf)
            if (n > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    // ---------------------------------------------------------------- 阅读进度

    /** 记录某本书读到的页（0-based）。走 SharedPreferences，翻页即存、很快。 */
    fun setReadingProgress(bookId: String, page: Int) {
        progressPrefs.edit()
            .putInt("page_$bookId", page.coerceAtLeast(0))
            .putLong("time_$bookId", System.currentTimeMillis())
            .apply()
    }

    fun readingProgress(bookId: String): ReadingProgress? {
        val t = progressPrefs.getLong("time_$bookId", 0L)
        if (t == 0L) return null
        return ReadingProgress(progressPrefs.getInt("page_$bookId", 0), t)
    }

    /** 最近读的那本书及其进度（书库页「继续阅读」用）。 */
    fun lastRead(books: List<Book>): Pair<Book, ReadingProgress>? {
        var best: Pair<Book, ReadingProgress>? = null
        for (b in books) {
            val p = readingProgress(b.id) ?: continue
            if (best == null || p.lastReadAt > best.second.lastReadAt) best = b to p
        }
        return best
    }

    /** 某本书某页的译文缓存文件（本地缓存，服务端 14 天会删，这里留着）。服务端现发 WebP 无损。 */
    fun translatedCacheFile(bookId: String, pageIndex: Int): File =
        File(File(translatedRoot, bookId), pageIndex.toString().padStart(3, '0') + ".webp")

    /** 原子下载译文页到本地缓存：先写临时文件再改名，避免「后台全书翻译」和「打开阅读器补拉」并发写坏同一文件。 */
    suspend fun downloadTranslatedPage(bookId: String, pageId: Int, pageIndex: Int): File {
        val f = translatedCacheFile(bookId, pageIndex)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.${UUID.randomUUID()}.tmp")
        try {
            api.download(api.translatedUrl(pageId), tmp)
            if (!tmp.renameTo(f)) tmp.copyTo(f, overwrite = true)
        } finally {
            tmp.delete()
        }
        return f
    }

    fun epubFile(bookId: String): File = File(File(root, bookId), "book.src")

    /** 云端书封面缓存文件（删本地后云端 tab 仍能显示封面）。 */
    fun cloudCoverFile(cloudId: String): File = File(cloudCovers, "$cloudId.jpg")

    /** 确保云端书封面已缓存（没有就从服务端拉）。 */
    suspend fun ensureCloudCover(cloudId: String) = withContext(Dispatchers.IO) {
        val f = cloudCoverFile(cloudId)
        if (f.exists() && f.length() > 0L) return@withContext
        f.parentFile?.mkdirs()
        runCatching { api.cloudDownloadCover(cloudId, f) }.onFailure { f.delete() }
    }

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
                cloudId = if (o.isNull("cloud_id")) null else o.optString("cloud_id").takeIf { it.isNotBlank() },
                hash = o.optString("hash", ""),
                fingerprint = o.optString("fingerprint", ""),
                createdAt = o.optLong("created_at", 0L),
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
                b.cloudId?.let { put("cloud_id", it) }
                if (b.hash.isNotEmpty()) put("hash", b.hash)
                if (b.fingerprint.isNotEmpty()) put("fingerprint", b.fingerprint)
                put("created_at", b.createdAt)
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
