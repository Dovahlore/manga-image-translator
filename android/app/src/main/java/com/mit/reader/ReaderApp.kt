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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    var translatingBookId by mutableStateOf<String?>(null)
        private set
    var translatingProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    override fun onCreate() {
        super.onCreate()
        ServerConfig.init(this)
        library = LibraryRepository(this)
        resumeTranslatingBook()
    }

    /** 进程被杀后，下次打开时接着收尾上一本没翻完的书（服务端一直在翻，这里补下载）。 */
    private fun resumeTranslatingBook() {
        val id = prefs.getString("book_id", null) ?: return
        appScope.launch {
            val book = library.book(id)
            if (book == null) {
                prefs.edit().remove("book_id").remove("uploaded").apply()
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
        appScope.launch {
            try {
                val done = translateWholeBook(book) { d, t -> translatingProgress = d to t }
                val msg = if (done >= book.pageCount) "《${book.title}》翻译完成"
                else "《${book.title}》翻译完成 $done/${book.pageCount} 页（失败页可在阅读器内重试）"
                Toast.makeText(this@ReaderApp, msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@ReaderApp, "全书翻译失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                translatingBookId = null
                translatingProgress = null
                prefs.edit().remove("book_id").remove("uploaded").apply()
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
        onProgress(0, total)

        if (!prefs.getBoolean("uploaded", false)) {
            val existing = api.bookPages(book.id).associateBy { it.pageIndex }
            val pending = (0 until total).filter { existing[it]?.status != "done" }
            if (pending.isNotEmpty()) {
                val orderDir = if (book.mode == ReadingMode.MANGA) "rtl" else "ltr"
                api.translateAll(book.id, book.title, orderDir, total, pending, pending.map { book.pageFiles[it] })
            }
            prefs.edit().putBoolean("uploaded", true).apply()
        }

        var lastDone = -1
        while (true) {
            val pages = api.bookPages(book.id)
            var done = 0
            for (p in pages) {
                if (p.status == "done") {
                    val f = library.translatedCacheFile(book.id, p.pageIndex)
                    if (!f.exists() || f.length() == 0L) {
                        api.download(api.translatedUrl(p.id), f)
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
