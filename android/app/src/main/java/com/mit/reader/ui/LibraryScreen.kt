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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val app = LocalContext.current.applicationContext as ReaderApp
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var refreshing by remember { mutableStateOf(0) }
    var confirmDelete by remember { mutableStateOf<Book?>(null) }
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
                        onTranslateAll = { app.startTranslateAll(book) },
                        isTranslating = app.translatingBookId == book.id,
                        progressText = if (app.translatingBookId == book.id) app.translatingProgress?.let { "${it.first}/${it.second}" } else null,
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
