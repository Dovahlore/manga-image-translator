package com.mit.reader

import android.app.Application
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mit.reader.data.Book
import com.mit.reader.data.LibraryRepository
import com.mit.reader.data.ReadingMode
import com.mit.reader.data.ServerConfig
import com.mit.reader.data.TranslationApi
import com.mit.reader.data.serverId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** WebView 后台下载任务状态。 */
enum class DownloadStatus { DOWNLOADING, PAUSED, IMPORTING, DONE, DUPLICATE, FAILED, CANCELED }

/** 一条后台下载任务（进度页「下载」区展示）。 */
data class DownloadTask(
    val id: String,
    val url: String,
    val name: String,
    val title: String,
    val cookie: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
    val written: Long = 0L,
    val total: Long = 0L,
    val status: DownloadStatus = DownloadStatus.DOWNLOADING,
    val message: String = "",
)

class ReaderApp : Application() {
    lateinit var library: LibraryRepository
        private set
    val api = TranslationApi()

    // 全书翻译跑在 Application 级作用域里：切换页面/进阅读器/回桌面都不会中断
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { getSharedPreferences("mit_translation", MODE_PRIVATE) }

    // 全书翻译队列：FIFO，队头=正在翻，其余=排队中。多本书点「全书翻译」按顺序一本本翻。
    private val translateQueue = mutableStateListOf<String>()
    private var queueWorker: Job? = null
    private var serverJobId: String? = null
    @Volatile private var stopRequested = false

    /** 正在翻的那本（队头）。 */
    val translatingBookId: String? get() = translateQueue.firstOrNull()
    /** 排队中的书（不含正在翻的队头）。 */
    val queuedBookIds: List<String> get() = translateQueue.drop(1)
    var translatingProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    /** 每本书最后一次已知的翻译进度 (done, total)：停止/完成后保留，进度页用，避免停止后进度消失或回跳。 */
    val progressHistory = mutableStateMapOf<String, Pair<Int, Int>>()
    /** 后台同步云端译文到本地时置 true（书库主页显示小转圈）。 */
    var syncingTranslations by mutableStateOf(false)
        private set
    /** 服务器是否可达（书库/进度页顶栏显示绿点=在线 / 红点=离线）。 */
    var serverOnline by mutableStateOf(true)
        private set
    /** 书库内容版本号：后台导入（下载完成 / 扫描文件夹）新增书后自增，书库页据此自动刷新。 */
    var libraryRevision by mutableStateOf(0)
        private set

    /** 通知书库内容变了（后台新增/删除书后调用）。 */
    fun bumpLibrary() { libraryRevision++ }

    /** 外部程序「打开」epub/mobi 后待跳转的书 id；AppNav 消费后进阅读器。 */
    var pendingOpenBookId by mutableStateOf<String?>(null)
        private set

    fun consumePendingOpen() { pendingOpenBookId = null }

    /** 外部「打开方式」进来：导入（按内容 hash 去重）并请求打开阅读器。 */
    fun openDocument(uri: Uri) {
        appScope.launch {
            Toast.makeText(this@ReaderApp, "正在导入…", Toast.LENGTH_SHORT).show()
            val book = runCatching { library.import(uri) }.getOrElse { e ->
                Toast.makeText(this@ReaderApp, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            bumpLibrary()
            pendingOpenBookId = book.id
        }
    }

    /** 每下好一页译文图就发一次事件 (bookId, pageIndex)：阅读器按书订阅，免轮询。 */
    private val _pageTranslated = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 64)
    val pageTranslated: SharedFlow<Pair<String, Int>> = _pageTranslated.asSharedFlow()

    // ---- WebView 后台下载队列（进度页「下载」区查看全部进度，支持暂停/继续/取消/断点续传）----

    /** 全部后台下载任务（含进行中/暂停/失败），Compose 可直接观察。 */
    val downloadTasks = mutableStateListOf<DownloadTask>()
    private val downloadStops = ConcurrentHashMap<String, AtomicBoolean>()
    private val downloadPersistAt = ConcurrentHashMap<String, Long>()
    private val downloadsPrefKey = "downloads"

    /** 加入后台下载：创建任务后立即开始。 */
    fun enqueueDownload(url: String, name: String, cookie: String?, referer: String?, userAgent: String?) {
        val title = library.titleFromFileName(name)
        val task = DownloadTask(UUID.randomUUID().toString(), url, name, title, cookie, referer, userAgent)
        downloadTasks.add(0, task)   // 新的在最上面
        downloadStops[task.id] = AtomicBoolean(false)
        persistDownloads()
        runDownload(task.id)
    }

    fun pauseDownload(id: String) {
        downloadStops.getOrPut(id) { AtomicBoolean(false) }.set(true)
        // 状态等 worker 抛 CancellationException 后置为 PAUSED
    }

    fun resumeDownload(id: String) {
        downloadStops.remove(id)
        updateDownload(id) { it.copy(status = DownloadStatus.DOWNLOADING, message = "") }
        persistDownloads()
        runDownload(id)
    }

    fun cancelDownload(id: String) {
        downloadStops.getOrPut(id) { AtomicBoolean(false) }.set(true)
        downloadTasks.removeAll { it.id == id }
        partFile(id).delete()
        persistDownloads()
    }

    /** 清除已结束（完成/重复）的下载记录。 */
    fun clearFinishedDownloads() {
        downloadTasks.removeAll { it.status == DownloadStatus.DONE || it.status == DownloadStatus.DUPLICATE || it.status == DownloadStatus.CANCELED }
    }

    private fun partFile(id: String): File =
        File(File(getExternalFilesDir(null), "downloads").apply { mkdirs() }, "$id.part")

    private fun runDownload(taskId: String) {
        appScope.launch {
            try {
                val idx = downloadTasks.indexOfFirst { it.id == taskId }
                if (idx < 0) return@launch
                val task = downloadTasks[idx]
                val part = partFile(taskId)

                val finalWritten = api.downloadResumable(
                    url = task.url,
                    out = part,
                    resumeFrom = task.written.coerceAtLeast(0),
                    extraHeaders = mapOf(
                        "Cookie" to (task.cookie ?: ""),
                        "Referer" to (task.referer ?: ""),
                        "User-Agent" to (task.userAgent ?: ""),
                    ),
                    shouldStop = { downloadStops[taskId]?.get() == true },
                    onProgress = { w, t ->
                        // 进度回调在 IO 线程：所有 Compose 状态写回必须切回主线程，否则快照竞态会闪退
                        val now = System.currentTimeMillis()
                        if (now - (downloadPersistAt[taskId] ?: 0L) > 200) {
                            downloadPersistAt[taskId] = now
                            appScope.launch {
                                updateDownload(taskId) { it.copy(written = w, total = t) }
                                persistDownloads()
                            }
                        }
                    },
                )

                // 下载完成：落盘到最终文件名 → 导入 → 删暂存（本协程在主线程，直接更新状态）
                updateDownload(taskId) { it.copy(written = finalWritten, status = DownloadStatus.IMPORTING) }
                persistDownloads()
                val finalFile = File(library.defaultBooksDir(), task.name)
                if (finalFile.exists()) finalFile.delete()
                if (!part.renameTo(finalFile)) { part.copyTo(finalFile, overwrite = true); part.delete() }
                val result = library.importFile(finalFile)
                finalFile.delete()
                val (st, msg) = if (result.duplicate) DownloadStatus.DUPLICATE to "本地已有《${result.book.title}》"
                                else DownloadStatus.DONE to "已导入《${result.book.title}》"
                updateDownload(taskId) { it.copy(status = st, message = msg) }
                persistDownloads()
                if (!result.duplicate) {
                    bumpLibrary()   // 新书入库：书库页立即刷新（不依赖扫描/定时刷新）
                    Toast.makeText(this@ReaderApp, "已导入《${result.book.title}》", Toast.LENGTH_SHORT).show()
                }
                // 成功/重复保留 10 秒让进度页看到，再自动清掉
                delay(10_000)
                downloadTasks.removeAll { it.id == taskId }
                persistDownloads()
            } catch (e: CancellationException) {
                // 暂停（任务还在）或取消（任务已删）
                if (downloadTasks.any { it.id == taskId }) {
                    // 用 .part 实际字节数作断点（已 flush 的干净边界），续传不再重下尾巴
                    updateDownload(taskId) {
                        it.copy(status = DownloadStatus.PAUSED, written = partFile(taskId).length(), message = "已暂停")
                    }
                    persistDownloads()
                }
            } catch (e: Exception) {
                if (downloadTasks.any { it.id == taskId }) {
                    updateDownload(taskId) { it.copy(status = DownloadStatus.FAILED, message = e.message ?: "下载失败") }
                    persistDownloads()
                }
            } finally {
                downloadStops.remove(taskId)
                downloadPersistAt.remove(taskId)
            }
        }
    }

    private fun updateDownload(id: String, transform: (DownloadTask) -> DownloadTask) {
        val idx = downloadTasks.indexOfFirst { it.id == id }
        if (idx >= 0) downloadTasks[idx] = transform(downloadTasks[idx])
    }

    private fun persistDownloads() {
        val arr = JSONArray()
        for (t in downloadTasks) {
            if (t.status == DownloadStatus.DOWNLOADING || t.status == DownloadStatus.PAUSED || t.status == DownloadStatus.FAILED) {
                arr.put(JSONObject().apply {
                    put("id", t.id)
                    put("url", t.url)
                    put("name", t.name)
                    put("title", t.title)
                    t.cookie?.let { put("cookie", it) }
                    t.referer?.let { put("referer", it) }
                    t.userAgent?.let { put("user_agent", it) }
                    put("total", t.total)
                    put("written", t.written)
                    put("status", t.status.name)
                    put("message", t.message)
                })
            }
        }
        prefs.edit().putString(downloadsPrefKey, arr.toString()).apply()
    }

    /** 进程重启后恢复未完成的下载（进行中→暂停，保留 .part 断点）。 */
    private fun loadDownloads() {
        val raw = prefs.getString(downloadsPrefKey, null) ?: return
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank()) continue
            val status = runCatching { DownloadStatus.valueOf(o.optString("status")) }.getOrNull() ?: DownloadStatus.FAILED
            downloadTasks.add(DownloadTask(
                id = id,
                url = o.optString("url"),
                name = o.optString("name"),
                title = o.optString("title"),
                cookie = o.optString("cookie").takeIf { it.isNotBlank() },
                referer = o.optString("referer").takeIf { it.isNotBlank() },
                userAgent = o.optString("user_agent").takeIf { it.isNotBlank() },
                written = o.optLong("written", 0),
                total = o.optLong("total", 0),
                status = if (status == DownloadStatus.DOWNLOADING) DownloadStatus.PAUSED else status,
                message = if (status == DownloadStatus.DOWNLOADING) "上次未完成，可继续下载" else o.optString("message"),
            ))
        }
    }

    override fun onCreate() {
        super.onCreate()
        ServerConfig.init(this)
        library = LibraryRepository(this)
        loadDownloads()
        resumeTranslatingBook()
        startBackgroundSync()
        startConnectivityMonitor()
    }

    /** 每 30 秒 ping 一次服务器，更新在线状态。 */
    private fun startConnectivityMonitor() {
        appScope.launch {
            while (true) {
                serverOnline = api.ping().startsWith("OK")
                delay(30 * 1000)
            }
        }
    }

    /** 立即 ping 并更新绿点，返回结果文案（设置页「测试连接」用）。 */
    suspend fun pingAndUpdate(): String {
        val r = api.ping()
        serverOnline = r.startsWith("OK")
        return r
    }

    /** 异步刷新在线状态（保存配置后让绿点即时反映新地址连通性）。 */
    fun refreshServerStatus() {
        appScope.launch { serverOnline = api.ping().startsWith("OK") }
    }

    /** 打开 App + 定时后台同步：给所有书补拉缺失/变化的译文页（同步书别的设备新翻的、非同步书服务端已翻好的）。
     *  只补差异，不全量，避免卡顿。 */
    private fun startBackgroundSync() {
        appScope.launch {
            delay(1500)
            // 打开时：先扫书库文件夹（识别云端书），再同步（把识别成云端书后缺的远程译文拉下来）
            val scanned = runCatching { library.scanLibraryFolder() }.getOrDefault(0)
            if (scanned > 0) bumpLibrary()   // 扫到新书：书库页刷新
            runCatching { drainPendingDeletes() }
            runCatching { drainPendingCancels() }
            runCatching { library.drainFolderSyncs() }   // 离线期间移动/重命名/删除收藏夹的云端 folder 补同步
            runCatching { syncAllBooks() }
            // 之后每 5 分钟只同步（不再扫文件夹）
            while (true) {
                delay(5 * 60 * 1000)
                runCatching { drainPendingDeletes() }
                runCatching { drainPendingCancels() }
                runCatching { library.drainFolderSyncs() }
                runCatching { syncAllBooks() }
            }
        }
    }

    private suspend fun syncAllBooks() {
        val books = library.books()
        if (books.isEmpty()) return
        syncingTranslations = true
        try {
            for (b in books) {
                // 正在全书翻译的由 translateWholeBook 下载，跳过避免并发写同一文件
                if (b.id in translateQueue) continue
                runCatching { library.refreshTranslations(b, overwrite = false) }
            }
        } finally {
            syncingTranslations = false
        }
    }

    // ---- 离线删书补删：删本地时服务端删除失败（离线/网络抖），记下 book id，下次在线补删，避免 DB 残留 ----

    private fun pendingDeletes(): MutableList<String> {
        val raw = prefs.getString("pending_deletes", null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun persistPendingDeletes(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("pending_deletes", arr.toString()).apply()
    }

    fun recordPendingDelete(serverBookId: String) {
        val list = pendingDeletes()
        if (serverBookId !in list) list.add(serverBookId)
        persistPendingDeletes(list)
    }

    private suspend fun drainPendingDeletes() {
        val ids = pendingDeletes()
        if (ids.isEmpty()) return
        val remaining = mutableListOf<String>()
        for (id in ids) {
            val ok = runCatching { api.deleteBook(id) }.getOrDefault(false)
            if (!ok) remaining.add(id)
        }
        persistPendingDeletes(remaining)
    }

    // ---- 离线停止补取消：点停止时服务端取消失败（离线），记下 book id，下次在线补取消，避免服务端继续翻 ----

    private fun pendingCancels(): MutableList<String> {
        val raw = prefs.getString("pending_cancels", null) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun persistPendingCancels(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString("pending_cancels", arr.toString()).apply()
    }

    fun recordPendingCancel(serverBookId: String) {
        val list = pendingCancels()
        if (serverBookId !in list) list.add(serverBookId)
        persistPendingCancels(list)
    }

    private suspend fun drainPendingCancels() {
        val ids = pendingCancels()
        if (ids.isEmpty()) return
        val remaining = mutableListOf<String>()
        for (id in ids) {
            val ok = runCatching { api.cancelBookJobs(id) }.getOrDefault(false)
            if (!ok) remaining.add(id)
        }
        persistPendingCancels(remaining)
    }

    /** 进程被杀后，下次打开时接着收尾队列里的书（服务端一直在翻，这里补下载）。 */
    private fun resumeTranslatingBook() {
        val ids = prefs.getString("translate_queue", null)?.let { raw ->
            runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { arr.getString(it) }
            }.getOrNull()
        } ?: return
        appScope.launch {
            translateQueue.clear()
            ids.forEach { id -> if (library.book(id) != null) translateQueue.add(id) }
            if (translateQueue.isNotEmpty()) {
                persistQueue()
                ensureWorker()
            } else {
                prefs.edit().remove("translate_queue").apply()
            }
        }
    }

    /** 一键全书翻译（后台排队执行）。已在队列/正在翻则忽略；多本书按点击顺序一本本翻。 */
    fun startTranslateAll(book: Book) {
        if (book.id in translateQueue) return
        translateQueue.add(book.id)
        persistQueue()
        ensureWorker()
    }

    private fun persistQueue() {
        val arr = JSONArray()
        translateQueue.forEach { arr.put(it) }
        prefs.edit().putString("translate_queue", arr.toString()).apply()
    }

    private fun ensureWorker() {
        if (queueWorker?.isActive == true) return
        queueWorker = appScope.launch {
            try {
                while (translateQueue.isNotEmpty()) runOne(translateQueue.first())
            } finally {
                queueWorker = null
            }
        }
    }

    /** 翻队列里的队头那一本。 */
    private suspend fun runOne(bookId: String) {
        val book = library.book(bookId)
        if (book == null) {
            translateQueue.removeAt(0)
            persistQueue()
            return
        }
        stopRequested = false
        translatingProgress = null   // 真实进度由 translateWholeBook 查完服务端后设置，不闪 0
        try {
            val done = translateWholeBook(book) { d, t ->
                translatingProgress = d to t
                progressHistory[book.id] = d to t
            }
            val msg = if (done >= book.pageCount) "《${book.title}》翻译完成"
            else "《${book.title}》翻译完成 $done/${book.pageCount} 页（失败页可在阅读器内重试）"
            Toast.makeText(this@ReaderApp, msg, Toast.LENGTH_LONG).show()
        } catch (e: CancellationException) {
            if (!stopRequested) throw e   // 应用退出（非用户停止）→ 重新抛出取消整个队列
        } catch (e: Exception) {
            if (!stopRequested) {
                Toast.makeText(this@ReaderApp, "《${book.title}》翻译失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        } finally {
            if (translateQueue.firstOrNull() == bookId) translateQueue.removeAt(0)
            serverJobId = null
            translatingProgress = null
            prefs.edit().remove("uploaded_$bookId").remove("job_id").apply()
            persistQueue()
        }
    }

    /** 删除书 / 取消同步 / 点「停止」前调用：把书移出队列；并取消服务端该书的全部后台任务。 */
    fun stopTranslatingIf(bookId: String) {
        val idx = translateQueue.indexOf(bookId)
        val wasRunning = idx == 0
        if (idx >= 0) translateQueue.removeAt(idx)
        if (wasRunning) {
            // 正在翻：请求停止（轮询循环看到 stopRequested 就退出）
            stopRequested = true
            serverJobId = null
            translatingProgress = null
        }
        prefs.edit().remove("uploaded_$bookId").remove("job_id").apply()
        if (idx >= 0) persistQueue()
        // 按书取消服务端所有 queued/running 任务：覆盖进程被杀后遗留的重复 job（孤儿任务）
        appScope.launch {
            val b = library.book(bookId)
            if (b != null) {
                val ok = runCatching { api.cancelBookJobs(b.serverId) }.getOrDefault(false)
                if (!ok) recordPendingCancel(b.serverId)   // 离线/失败：记下，下次在线补取消
            }
        }
    }

    /**
     * 全书翻译：
     * 1) 上传一次没翻过的页（成功后记 uploaded=true，避免重启后再传一遍）；
     * 2) 轮询服务端进度，把翻好的页下载到本地缓存。
     * 服务端已经用后台任务在翻，App 关掉也不影响它，这里只是收结果。
     */
    private suspend fun translateWholeBook(book: Book, onProgress: (Int, Int) -> Unit): Int {
        val total = book.pageCount
        val serverId = book.serverId
        val upKey = "uploaded_${book.id}"   // 每本书独立的上传标记，避免 A 书中断影响 B 书

        var lastDone = -1
        var uploaded = prefs.getBoolean(upKey, false)

        // 外层重试：网络错误（离线/服务端不可用）不退出、不把书移出队列，等几秒重试
        while (true) {
            if (stopRequested) throw CancellationException("用户停止")
            try {
                val existing = api.bookPages(serverId).associateBy { it.pageIndex }
                lastDone = existing.values.count { it.status == "done" }
                onProgress(lastDone, total)

                if (!uploaded) {
                    val pending = (0 until total).filter { existing[it]?.status != "done" }
                    if (pending.isNotEmpty()) {
                        val orderDir = if (book.mode == ReadingMode.MANGA) "rtl" else "ltr"
                        serverJobId = if (book.cloudId != null) {
                            // 已同步：服务端从云端 zip 自取图，不上传页图
                            api.translateAllFromZip(serverId, book.title, orderDir, pending)
                        } else {
                            api.translateAll(serverId, book.title, orderDir, total, pending, pending.map { book.pageFiles[it] })
                        }
                        // 持久化 job_id，重启恢复后「停止」仍能取消服务端任务
                        serverJobId?.let { prefs.edit().putString("job_id", it).apply() }
                    }
                    uploaded = true
                    prefs.edit().putBoolean(upKey, true).apply()
                }

                // 轮询服务端进度，把翻好的页下载到本地缓存
                while (true) {
                    if (stopRequested) throw CancellationException("用户停止")
                    val pages = api.bookPages(serverId)
                    var done = 0
                    for (p in pages) {
                        if (p.status == "done") {
                            val f = library.translatedCacheFile(book.id, p.pageIndex)
                            if (!f.exists() || f.length() == 0L) {
                                // 容错：下载失败（如服务端文件还没落盘的瞬时 404）不打断整本，下一轮重试
                                runCatching { library.downloadTranslatedPage(book.id, p.id, p.pageIndex) }
                                    .onSuccess { _pageTranslated.tryEmit(book.id to p.pageIndex) }
                            }
                            done++
                        }
                    }
                    if (done != lastDone) {
                        lastDone = done
                        onProgress(done, total)
                    }
                    val settled = pages.count { it.status == "done" || it.status == "failed" }
                    if (pages.size >= total && settled >= total) return lastDone
                    delay(2000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                // 网络错误/离线：等 5 秒重试，不退出、不把书移出队列（否则离线启动会把队列清空）
                delay(5000)
            } catch (e: Exception) {
                // 其它错误（HTTP 4xx/5xx 等）：向上抛，让 runOne 显示失败
                throw e
            }
        }
    }
}
