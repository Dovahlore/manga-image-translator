package com.mit.reader

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mit.reader.data.Book
import com.mit.reader.data.serverId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

enum class PageStatus { IDLE, RUNNING, DONE, FAILED }

data class PageState(
    val status: PageStatus = PageStatus.IDLE,
    val jobId: String? = null,
    val translatedFile: File? = null,
    val error: String? = null,
)

class ReaderViewModel(private val app: Application) : AndroidViewModel(app) {

    private val readerApp get() = app as ReaderApp

    var book by mutableStateOf<Book?>(null)
        private set
    var currentPage by mutableIntStateOf(0)
        private set
    var showOriginal by mutableStateOf(false)
        private set
    var autoMode by mutableStateOf(false)
        private set

    val pageStates = mutableStateMapOf<Int, PageState>()

    private val pages: List<File> get() = book?.pageFiles ?: emptyList()
    private var watchJob: Job? = null

    fun load(b: Book) {
        book = b
        // 从上次读到的页继续（没有记录就从第 0 页/封面开始）
        currentPage = readerApp.library.readingProgress(b.id)?.page
            ?.coerceIn(0, (b.pageCount - 1).coerceAtLeast(0)) ?: 0
        showOriginal = false
        pageStates.clear()
        // 本地译文缓存还在的页，直接标记 DONE（旋转/重启后不用重翻，也不用再问服务端）
        for (i in b.pageFiles.indices) {
            val f = readerApp.library.translatedCacheFile(b.id, i)
            if (f.exists() && f.length() > 0) {
                pageStates[i] = PageState(status = PageStatus.DONE, translatedFile = f)
            }
        }
        // 打开阅读器时从服务端补拉译文页（所有书都拉：同步书别的设备新翻的、非同步书服务端已翻好的都补齐）。
        // 只补本地缺的/指纹变了的，不全量。
        refreshFromServer()
        // 事件驱动：后台「全书翻译」每下好一页就发事件，这里按书过滤、更新 pageStates（免轮询）
        watchJob?.cancel()
        watchJob = viewModelScope.launch {
            readerApp.pageTranslated.collect { (bookId, pageIndex) ->
                if (bookId == b.id && pageIndex in b.pageFiles.indices) {
                    val f = readerApp.library.translatedCacheFile(b.id, pageIndex)
                    pageStates[pageIndex] = PageState(status = PageStatus.DONE, translatedFile = f)
                }
            }
        }
    }

    /** 从服务端补拉译文页到本地（只补缺失/指纹变化的页），再标 DONE。 */
    private fun refreshFromServer() {
        val b = book ?: return
        viewModelScope.launch {
            val doneIdx = readerApp.library.refreshTranslations(b, overwrite = false)
            for (i in doneIdx) {
                pageStates[i] = PageState(
                    status = PageStatus.DONE,
                    translatedFile = readerApp.library.translatedCacheFile(b.id, i),
                )
            }
        }
    }

    fun setPage(i: Int) {
        if (i < 0 || i >= pages.size) return
        currentPage = i
        // 翻页后默认展示译文（若该页已翻好），用户点「看原图」才临时切回原文
        showOriginal = false
        if (autoMode) prefetchAfter(i)
    }

    fun setAuto(on: Boolean) {
        autoMode = on
        if (on) prefetchAfter(currentPage)
    }

    fun toggleOriginal() {
        if (pageStates[currentPage]?.status == PageStatus.DONE) {
            showOriginal = !showOriginal
        }
    }

    /** 当前页手动翻译；已翻过则强制重翻（force=true）。 */
    fun translateCurrent() {
        val done = pageStates[currentPage]?.status == PageStatus.DONE
        translatePage(currentPage, force = done)
    }

    /** 自动预翻：当前页及其后 3 页（含当前页没翻过的）。 */
    fun prefetchAfter(fromIndex: Int) {
        for (i in fromIndex until minOf(fromIndex + 4, pages.size)) {
            translatePage(i)
        }
    }

    private fun translatePage(index: Int, force: Boolean = false) {
        val b = book ?: return
        if (index !in pages.indices) return
        if (pageStates[index]?.status == PageStatus.RUNNING) return          // 已在跑
        if (!force && pageStates[index]?.status == PageStatus.DONE) return   // 已翻过
        pageStates[index] = PageState(status = PageStatus.RUNNING)

        viewModelScope.launch {
            try {
                // 已同步的书：服务端从云端 zip / orig 自取图，不上传图片
                val jobId = if (b.cloudId != null) {
                    readerApp.api.translateFromServer(b.serverId, index, force)
                } else {
                    readerApp.api.translate(
                        image = pages[index],
                        bookId = b.serverId,
                        pageIndex = index,
                        async = true,
                        force = force,
                    ).jobId ?: throw IllegalStateException("无 job_id")
                }
                // 轮询 job 直到 done / failed（最多 2 分钟）
                var finished = false
                var pid = -1
                var err: String? = null
                var tries = 0
                while (!finished && tries < 80) {
                    val st = readerApp.api.job(jobId)
                    when (st.status) {
                        "done" -> { pid = st.pageId ?: -1; finished = true }
                        "failed" -> { err = st.error ?: "翻译失败"; finished = true }
                    }
                    if (!finished) {
                        delay(1500)
                        tries++
                    }
                }
                if (!finished) err = "超时：翻译未在 2 分钟内完成"
                if (err != null) throw IllegalStateException(err)
                val pageId = pid

                // 下载译文图 → 本地缓存
                val out = readerApp.library.translatedCacheFile(b.id, index)
                out.parentFile?.mkdirs()
                readerApp.api.download(readerApp.api.translatedUrl(pageId), out)
                pageStates[index] = PageState(status = PageStatus.DONE, translatedFile = out)
            } catch (e: Exception) {
                pageStates[index] = PageState(status = PageStatus.FAILED, error = e.message)
            }
        }
    }
}
