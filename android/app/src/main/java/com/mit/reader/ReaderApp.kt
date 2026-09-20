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

    var translatingBookId by mutableStateOf<String?>(null)
        private set
    var translatingProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    override fun onCreate() {
        super.onCreate()
        ServerConfig.init(this)
        library = LibraryRepository(this)
    }

    /** 一键全书翻译（后台执行）。已有一本在翻则忽略本次点击。 */
    fun startTranslateAll(book: Book) {
        if (translatingBookId != null) return
        translatingBookId = book.id
        translatingProgress = 0 to book.pageCount
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
            }
        }
    }

    /** 全书翻译：跳过服务端已翻好的页，上传其余页，轮询进度并把译文图下载到本地缓存。返回最终 done 页数。 */
    private suspend fun translateWholeBook(book: Book, onProgress: (Int, Int) -> Unit): Int {
        val total = book.pageCount
        onProgress(0, total)

        val existing = api.bookPages(book.id).associateBy { it.pageIndex }
        val pending = (0 until total).filter { existing[it]?.status != "done" }
        if (pending.isNotEmpty()) {
            val orderDir = if (book.mode == ReadingMode.MANGA) "rtl" else "ltr"
            api.translateAll(book.id, book.title, orderDir, total, pending, pending.map { book.pageFiles[it] })
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
