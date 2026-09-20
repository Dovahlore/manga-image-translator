package com.mit.reader.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.mit.reader.data.ServerBook
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val app = LocalContext.current.applicationContext as ReaderApp
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var refreshing by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf<Book?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var serverBooks by remember { mutableStateOf<List<ServerBook>>(emptyList()) }
    var progressErr by remember { mutableStateOf<String?>(null) }
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

    LaunchedEffect(refreshing) {
        books = app.library.books()
    }

    // 每次切到「翻译进度」页都拉一次服务端汇总
    LaunchedEffect(tab) {
        if (tab == 1) {
            runCatching { app.api.listBooks() }
                .onSuccess { serverBooks = it; progressErr = null }
                .onFailure { progressErr = it.message }
        }
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
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("书库") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("翻译进度") })
            }
            if (tab == 0) {
                if (books.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("点右下角 + 导入 EPUB / MOBI", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(120.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        gridItems(books, key = { it.id }) { book ->
                            BookCell(
                                book = book,
                                onClick = { onOpen(book.id) },
                                onLongClick = { confirmDelete = book },
                                onTranslateAll = { app.startTranslateAll(book) },
                                isTranslating = app.translatingBookId == book.id,
                                progressText = if (app.translatingBookId == book.id) app.translatingProgress?.let { "${it.first}/${it.second}" } else null,
                            )
                        }
                    }
                }
            } else {
                ProgressList(
                    books = books,
                    serverBooks = serverBooks,
                    error = progressErr,
                    translatingBookId = app.translatingBookId,
                    translatingProgress = app.translatingProgress,
                )
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

/** 翻译进度列表：本地书 + 服务端 done/failed 汇总 + 正在翻译的实时进度。 */
@Composable
private fun ProgressList(
    books: List<Book>,
    serverBooks: List<ServerBook>,
    error: String?,
    translatingBookId: String?,
    translatingProgress: Pair<Int, Int>?,
) {
    if (error != null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("加载进度失败：$error", color = MaterialTheme.colorScheme.error)
        }
        return
    }
    if (books.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无书籍", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val serverMap = serverBooks.associateBy { it.id }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listItems(books, key = { it.id }) { book ->
            val s = serverMap[book.id]
            val isRunning = translatingBookId == book.id
            val done = if (isRunning) (translatingProgress?.first ?: 0) else (s?.donePages ?: 0)
            val failed = s?.failedPages ?: 0
            val total = book.pageCount
            val status = when {
                isRunning -> "进行中"
                done + failed == 0 -> "未开始"
                done >= total && failed == 0 -> "已完成"
                failed > 0 && done + failed >= total -> "部分失败"
                else -> "进行中"
            }
            BookProgressRow(
                title = book.title,
                status = status,
                done = done,
                failed = failed,
                total = total,
                isRunning = isRunning,
            )
        }
    }
}

@Composable
private fun BookProgressRow(
    title: String,
    status: String,
    done: Int,
    failed: Int,
    total: Int,
    isRunning: Boolean,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, maxLines = 1, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(
                status,
                style = MaterialTheme.typography.labelMedium,
                color = when (status) {
                    "已完成" -> MaterialTheme.colorScheme.primary
                    "进行中" -> MaterialTheme.colorScheme.tertiary
                    "部分失败" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(
            buildString {
                append("$done / $total 页")
                if (failed > 0) append(" · 失败 $failed")
                if (isRunning && done < total) append(" · 后台翻译中")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isRunning && total > 0) {
            LinearProgressIndicator(
                progress = { done.toFloat() / total.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
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
