package com.mit.reader.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mit.reader.ReaderApp
import com.mit.reader.data.Book
import com.mit.reader.data.ReadingMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val app = LocalContext.current.applicationContext as ReaderApp
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var refreshing by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf<Book?>(null) }
    var translatingId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { app.library.import(uri) }
                .onSuccess { refreshing++ }
                .onFailure { e -> Toast.makeText(app, "导入失败：${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    fun startTranslateAll(book: Book) {
        scope.launch {
            translatingId = book.id
            progress = 0 to book.pageCount
            try {
                val done = translateWholeBook(app, book) { d, t -> progress = d to t }
                val msg = if (done >= book.pageCount) "翻译完成"
                else "翻译完成 $done/${book.pageCount} 页（失败的页可在阅读器内重试）"
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(app, "全书翻译失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                translatingId = null
                progress = null
            }
        }
    }

    LaunchedEffect(refreshing) {
        books = app.library.books()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("书库") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "设置") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { importLauncher.launch("*/*") }) {
                Icon(Icons.Default.Add, contentDescription = "导入 EPUB / MOBI")
            }
        },
    ) { pad ->
        if (books.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Text("点右下角 + 导入 EPUB / MOBI", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                modifier = Modifier.fillMaxSize().padding(pad),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(books, key = { it.id }) { book ->
                    BookCell(
                        book = book,
                        onClick = { onOpen(book.id) },
                        onLongClick = { confirmDelete = book },
                        onTranslateAll = { startTranslateAll(book) },
                        isTranslating = translatingId == book.id,
                        progressText = if (translatingId == book.id) progress?.let { "${it.first}/${it.second}" } else null,
                    )
                }
            }
        }
    }

    confirmDelete?.let { book ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除《${book.title}》？") },
            text = { Text("会同时删除本地文件、本地译文缓存，以及服务端该书的所有记录。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    val b = book
                    confirmDelete = null
                    scope.launch {
                        app.api.deleteBook(b.id)
                        app.library.delete(b.id)
                        refreshing++
                    }
                }) { Text("删除") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = null }) { Text("取消") }
            },
        )
    }
}

/** 全书翻译：跳过服务端已翻好的页，上传其余页，轮询进度并把译文图下载到本地缓存。返回最终 done 页数。 */
private suspend fun translateWholeBook(
    app: ReaderApp,
    book: Book,
    onProgress: (Int, Int) -> Unit,
): Int {
    val total = book.pageCount
    onProgress(0, total)

    val existing = app.api.bookPages(book.id).associateBy { it.pageIndex }
    val pending = (0 until total).filter { existing[it]?.status != "done" }
    if (pending.isNotEmpty()) {
        val orderDir = if (book.mode == ReadingMode.MANGA) "rtl" else "ltr"
        app.api.translateAll(book.id, book.title, orderDir, total, pending, pending.map { book.pageFiles[it] })
    }

    var lastDone = -1
    while (true) {
        val pages = app.api.bookPages(book.id)
        var done = 0
        for (p in pages) {
            if (p.status == "done") {
                val f = app.library.translatedCacheFile(book.id, p.pageIndex)
                if (!f.exists() || f.length() == 0L) {
                    app.api.download(app.api.translatedUrl(p.id), f)
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookCell(
    book: Book,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTranslateAll: () -> Unit,
    isTranslating: Boolean,
    progressText: String?,
) {
    Column(
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f)) {
            AsyncImage(
                model = book.coverFile,
                contentDescription = book.title,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = "${book.pageCount} 页 · ${if (book.mode == ReadingMode.MANGA) "日漫" else "普通"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = onTranslateAll,
            enabled = !isTranslating,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = progressText?.let { "翻译中 $it" } ?: "翻译全书",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
