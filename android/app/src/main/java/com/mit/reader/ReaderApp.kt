package com.mit.reader

import android.app.Application
import android.widget.Toast
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.launch

class ReaderApp : Application() {
    lateinit var library: LibraryRepository
        private set
    val api = TranslationApi()

    // 全书翻译跑在 Application 级作用域里：切换页面/进阅读器/回桌面都不会中断
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs by lazy { getSharedPreferences("mit_translation", MODE_PRIVATE) }
    private var translateJob: Job? = null
    private var serverJobId: String? = null

    var translatingBookId by mutableStateOf<String?>(null)
        private set
    var translatingProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    override fun onCreate() {
        super.onCreate()
        ServerConfig.init(this)
        library = LibraryRepository(this)
        resumeTranslatingBook()
        startBackgroundSync()
    }

    /** 启动 + 定时后台同步：给已同步到云端的书补拉缺失的译文页（别的设备新翻的页）。
     *  重翻的页在打开阅读器时用覆盖模式拉最新；这里只补缺失，避免每 5 分钟全量重下。 */
    private fun startBackgroundSync() {
        appScope.launch {
            delay(3000)
            while (true) {
                runCatching { syncSyncedBooks() }
                delay(5 * 60 * 1000)
            }
        }
    }

    private suspend fun syncSyncedBooks() {
        val books = library.books()
        for (b in books) {
            if (b.cloudId == null) continue
            runCatching { library.refreshTranslations(b, overwrite = false) }
        }
    }

    /** 进程被杀后，下次打开时接着收尾上一本没翻完的书（服务端一直在翻，这里补下载）。 */
    private fun resumeTranslatingBook() {
        val id = prefs.getString("book_id", null) ?: return
        appScope.launch {
            val book = library.book(id)
            if (book == null) {
                prefs.edit().remove("book_id").remove("uploaded_$id").remove("job_id").apply()
            } else {
                startTranslateAll(book)
            }
        }
    }

    /** 一键全书翻译（后台执行）。已有一本在翻则忽略本次点击。 */
    fun startTranslateAll(book: Book) {
        if (translatingBookId != null) return
        translatingBookId = book.id
        translatingProgress = 0 to book.pageCount
        prefs.edit().putString("book_id", book.id).apply()
        translateJob = appScope.launch {
            try {
                val done = translateWholeBook(book) { d, t -> translatingProgress = d to t }
                val msg = if (done >= book.pageCount) "《${book.title}》翻译完成"
                else "《${book.title}》翻译完成 $done/${book.pageCount} 页（失败页可在阅读器内重试）"
                Toast.makeText(this@ReaderApp, msg, Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e   // 用户点「停止」的正常取消，不算失败
            } catch (e: Exception) {
                Toast.makeText(this@ReaderApp, "全书翻译失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                translateJob = null
                serverJobId = null
                translatingBookId = null
                translatingProgress = null
                prefs.edit().remove("book_id").remove("uploaded_${book.id}").remove("job_id").apply()
            }
        }
    }

    /** 删除书 / 取消同步前调用：如果删的正是正在翻译的那本，停掉 App 轮询 + 取消服务端后台任务，
     *  避免空转（书被删后服务端 pages 永远凑不满 → 死循环）或把已删的书又翻回来。 */
    fun stopTranslatingIf(bookId: String) {
        if (translatingBookId != bookId) return
        translateJob?.cancel()
        translateJob = null
        // serverJobId 可能在重启恢复后丢失（upKey=true 跳过上传），从 prefs 兜底取
        val jid = serverJobId ?: prefs.getString("job_id", null)
        jid?.let { appScope.launch { runCatching { api.cancelJob(it) } } }
        serverJobId = null
        translatingBookId = null
        translatingProgress = null
        prefs.edit().remove("book_id").remove("uploaded_$bookId").remove("job_id").apply()
    }

    /**
     * 全书翻译：
     * 1) 上传一次没翻过的页（成功后记 uploaded=true，避免重启后再传一遍）；
     * 2) 轮询服务端进度，把翻好的页下载到本地缓存。
     * 服务端已经用后台任务在翻，App 关掉也不影响它，这里只是收结果。
     */
    private suspend fun translateWholeBook(book: Book, onProgress: (Int, Int) -> Unit): Int {
        val total = book.pageCount
        onProgress(0, total)

        val serverId = book.serverId
        val upKey = "uploaded_${book.id}"   // 每本书独立的上传标记，避免 A 书中断影响 B 书
        if (!prefs.getBoolean(upKey, false)) {
            val existing = api.bookPages(serverId).associateBy { it.pageIndex }
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

        var lastDone = -1
        while (true) {
            val pages = api.bookPages(serverId)
            var done = 0
            for (p in pages) {
                if (p.status == "done") {
                    val f = library.translatedCacheFile(book.id, p.pageIndex)
                    if (!f.exists() || f.length() == 0L) {
                        // 容错：下载失败（如服务端文件还没落盘的瞬时 404）不打断整本，下一轮重试
                        runCatching { api.download(api.translatedUrl(p.id), f) }
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
