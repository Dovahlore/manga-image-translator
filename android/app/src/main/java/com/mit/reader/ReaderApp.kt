package com.mit.reader

import android.app.Application
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

    /** 每下好一页译文图就发一次事件 (bookId, pageIndex)：阅读器按书订阅，免轮询。 */
    private val _pageTranslated = MutableSharedFlow<Pair<String, Int>>(extraBufferCapacity = 64)
    val pageTranslated: SharedFlow<Pair<String, Int>> = _pageTranslated.asSharedFlow()

    override fun onCreate() {
        super.onCreate()
        ServerConfig.init(this)
        library = LibraryRepository(this)
        resumeTranslatingBook()
        startBackgroundSync()
    }

    /** 打开 App + 定时后台同步：给所有书补拉缺失/变化的译文页（同步书别的设备新翻的、非同步书服务端已翻好的）。
     *  只补差异，不全量，避免卡顿。 */
    private fun startBackgroundSync() {
        appScope.launch {
            delay(1000)
            while (true) {
                runCatching { syncAllBooks() }
                delay(5 * 60 * 1000)
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
            if (b != null) runCatching { api.cancelBookJobs(b.serverId) }
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

        // 先查服务端已有页，用真实进度初始化（避免「先闪 0 再跳真实值」）
        val existing = runCatching { api.bookPages(serverId) }.getOrDefault(emptyList()).associateBy { it.pageIndex }
        var lastDone = existing.values.count { it.status == "done" }
        onProgress(lastDone, total)

        if (!prefs.getBoolean(upKey, false)) {
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
            prefs.edit().putBoolean(upKey, true).apply()
        }

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
            if (pages.size >= total && settled >= total) break
            delay(2000)
        }
        return lastDone
    }
}
