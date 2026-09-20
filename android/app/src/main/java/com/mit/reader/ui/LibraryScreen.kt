package com.mit.reader.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mit.reader.ReaderApp
import com.mit.reader.data.Book
import com.mit.reader.data.Folder
import com.mit.reader.data.ReadingMode
import com.mit.reader.data.ReadingProgress
import com.mit.reader.data.ServerBook
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ReaderApp
    val activity = context.findActivity()
    var books by remember { mutableStateOf<List<Book>>(emptyList()) }
    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var refreshing by remember { mutableStateOf(0) }
    var currentFolderId by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var serverBooks by remember { mutableStateOf<List<ServerBook>>(emptyList()) }
    var progressErr by remember { mutableStateOf<String?>(null) }
    var moveTarget by remember { mutableStateOf<Book?>(null) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Book?>(null) }
    var confirmDeleteFolder by remember { mutableStateOf<Folder?>(null) }
    var folderMenuTarget by remember { mutableStateOf<Folder?>(null) }
    var renameTarget by remember { mutableStateOf<Folder?>(null) }
    var renameBookTarget by remember { mutableStateOf<Book?>(null) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showExitDialog by remember { mutableStateOf(false) }
    var lastRead by remember { mutableStateOf<Pair<Book, ReadingProgress>?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() { refreshing++ }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { app.library.import(uri) }
                .onSuccess { book ->
                    // 在收藏夹里导入的，直接放进当前收藏夹
                    currentFolderId?.let { fid ->
                        runCatching { app.library.moveBook(book.id, fid) }
                    }
                    reload()
                }
                .onFailure { e -> Toast.makeText(app, "导入失败：${e.message}", Toast.LENGTH_LONG).show() }
        }
    }

    LaunchedEffect(refreshing) {
        val b = app.library.books()
        books = b
        folders = app.library.folders()
        lastRead = app.library.lastRead(b)
    }

    // 每次切到「翻译进度」页都拉一次服务端汇总
    LaunchedEffect(tab) {
        if (tab == 1) {
            runCatching { app.api.listBooks() }
                .onSuccess { serverBooks = it; progressErr = null }
                .onFailure { progressErr = it.message }
        }
    }

    // 书库页按系统返回：先退搜索/文件夹，否则弹确认退出
    BackHandler {
        when {
            searchActive -> { searchActive = false; searchQuery = "" }
            currentFolderId != null -> currentFolderId = null
            else -> showExitDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("搜索书名 / 收藏夹") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("书库")
                    }
                },
                navigationIcon = {
                    when {
                        searchActive -> IconButton(onClick = { searchActive = false; searchQuery = "" }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "退出搜索")
                        }
                        currentFolderId != null -> IconButton(onClick = { currentFolderId = null }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (searchActive) {
                        IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, contentDescription = "清空") }
                    } else {
                        IconButton(onClick = { searchActive = true }) { Icon(Icons.Default.Search, contentDescription = "搜索") }
                        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "设置") }
                    }
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
            if (searchActive) {
                SearchResults(
                    query = searchQuery,
                    books = books,
                    folders = folders,
                    onOpenBook = { id -> searchActive = false; searchQuery = ""; onOpen(id) },
                    onEnterFolder = { id -> searchActive = false; searchQuery = ""; currentFolderId = id },
                )
            } else {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("书库") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("翻译进度") })
                }
                if (tab == 0) {
                    LibraryTab(
                        books = books,
                        folders = folders,
                        currentFolderId = currentFolderId,
                        lastRead = lastRead,
                        onOpen = onOpen,
                        onEnterFolder = { currentFolderId = it },
                        onNewFolder = { showCreateFolder = true },
                        onMoveBook = { moveTarget = it },
                        onMoveToFolder = { book, fid ->
                            scope.launch { app.library.moveBook(book.id, fid); reload() }
                        },
                        onMoveOut = { book ->
                            scope.launch { app.library.moveBook(book.id, null); reload() }
                        },
                        onDelete = { confirmDelete = it },
                        onRenameBook = { renameBookTarget = it },
                        onFolderMenu = { folderMenuTarget = it },
                        onTranslateAll = { app.startTranslateAll(it) },
                        translatingBookId = app.translatingBookId,
                        translatingProgress = app.translatingProgress,
                    )
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
    }

    // ---- 移动对话框 ----
    moveTarget?.let { book ->
        MoveBookDialog(
            book = book,
            folders = folders,
            onMove = { fid -> scope.launch { app.library.moveBook(book.id, fid); reload() }; moveTarget = null },
            onDismiss = { moveTarget = null },
        )
    }

    // ---- 新建收藏夹 ----
    if (showCreateFolder) {
        CreateFolderDialog(
            onCreate = { name -> scope.launch { app.library.createFolder(name); reload() }; showCreateFolder = false },
            onDismiss = { showCreateFolder = false },
        )
    }

    // ---- 删除书 ----
    confirmDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("删除《${book.title}》？") },
            text = { Text("会同时删除本地文件、本地译文缓存，以及服务端该书的所有记录。") },
            confirmButton = {
                TextButton(onClick = {
                    val b = book
                    confirmDelete = null
                    scope.launch {
                        app.api.deleteBook(b.id)
                        app.library.delete(b.id)
                        reload()
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("取消") } },
        )
    }

    // ---- 删除收藏夹 ----
    confirmDeleteFolder?.let { folder ->
        AlertDialog(
            onDismissRequest = { confirmDeleteFolder = null },
            title = { Text("删除收藏夹「${folder.name}」？") },
            text = { Text("里面的书会回到「未分类」，不会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    val f = folder
                    confirmDeleteFolder = null
                    scope.launch { app.library.deleteFolder(f.id); reload() }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteFolder = null }) { Text("取消") } },
        )
    }

    // ---- 收藏夹菜单（重命名 / 删除）----
    folderMenuTarget?.let { folder ->
        FolderMenuDialog(
            folder = folder,
            onRename = { folderMenuTarget = null; renameTarget = folder },
            onDelete = { folderMenuTarget = null; confirmDeleteFolder = folder },
            onDismiss = { folderMenuTarget = null },
        )
    }

    // ---- 重命名收藏夹 ----
    renameTarget?.let { folder ->
        RenameFolderDialog(
            folder = folder,
            onRename = { name -> scope.launch { app.library.renameFolder(folder.id, name); reload() }; renameTarget = null },
            onDismiss = { renameTarget = null },
        )
    }

    // ---- 重命名书 ----
    renameBookTarget?.let { book ->
        RenameBookDialog(
            book = book,
            onRename = { name -> scope.launch { app.library.renameBook(book.id, name); reload() }; renameBookTarget = null },
            onDismiss = { renameBookTarget = null },
        )
    }

    // ---- 退出确认 ----
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("退出应用？") },
            text = { Text("确定要退出吗？") },
            confirmButton = {
                TextButton(onClick = { activity?.finish() }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { showExitDialog = false }) { Text("取消") } },
        )
    }
}

/** 从 Context 链上找到宿主 Activity（用于退出应用）。 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 「书库」页：根视图 = 上次阅读 + 收藏夹区 + 未分类书；点进收藏夹 = 只看该文件夹里的书。 */
@Composable
private fun LibraryTab(
    books: List<Book>,
    folders: List<Folder>,
    currentFolderId: String?,
    lastRead: Pair<Book, ReadingProgress>?,
    onOpen: (String) -> Unit,
    onEnterFolder: (String) -> Unit,
    onNewFolder: () -> Unit,
    onMoveBook: (Book) -> Unit,
    onMoveToFolder: (Book, String) -> Unit,
    onMoveOut: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    onRenameBook: (Book) -> Unit,
    onFolderMenu: (Folder) -> Unit,
    onTranslateAll: (Book) -> Unit,
    translatingBookId: String?,
    translatingProgress: Pair<Int, Int>?,
) {
    if (currentFolderId != null) {
        val folder = folders.find { it.id == currentFolderId } ?: return
        val inBooks = books.filter { it.folderId == currentFolderId }
        if (inBooks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("「${folder.name}」是空的", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "回到书库，长按漫画即可移进这里",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return
        }
        BookGrid(
            books = inBooks,
            inFolder = true,
            onOpen = onOpen,
            onMoveBook = onMoveBook,
            onMoveOut = onMoveOut,
            onDelete = onDelete,
            onRenameBook = onRenameBook,
            onTranslateAll = onTranslateAll,
            translatingBookId = translatingBookId,
            translatingProgress = translatingProgress,
        )
        return
    }

    val unFiled = books.filter { it.folderId == null }
    // 完全空（没书也没收藏夹）才显示引导；有收藏夹时即使没书也要把收藏夹展示出来
    if (books.isEmpty() && folders.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("点右下角 + 导入 EPUB / MOBI", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onNewFolder) { Text("新建收藏夹") }
            }
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 上次阅读（整行占满，放在最前面）
        if (lastRead != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                ContinueReadingCard(
                    book = lastRead.first,
                    page = lastRead.second.page,
                    onContinue = { onOpen(lastRead.first.id) },
                )
            }
        }
        // 收藏夹区（整行占满）
        item(span = { GridItemSpan(maxLineSpan) }) {
            FoldersSection(
                folders = folders,
                books = books,
                onEnterFolder = onEnterFolder,
                onNewFolder = onNewFolder,
                onFolderMenu = onFolderMenu,
            )
        }
        if (unFiled.isNotEmpty()) {
            gridItems(unFiled, key = { it.id }) { book ->
                BookCell(
                    book = book,
                    inFolder = false,
                    onOpen = { onOpen(book.id) },
                    onLongPress = { onMoveBook(book) },
                    onTranslateAll = { onTranslateAll(book) },
                    onMove = { onMoveBook(book) },
                    onDelete = { onDelete(book) },
                    onRename = { onRenameBook(book) },
                    isTranslating = translatingBookId == book.id,
                    progressText = if (translatingBookId == book.id) translatingProgress?.let { "${it.first}/${it.second}" } else null,
                )
            }
        } else if (books.isEmpty()) {
            // 没书但已有收藏夹：给个导入提示，收藏夹区仍正常展示
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "点右下角 + 导入 EPUB / MOBI",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun BookGrid(
    books: List<Book>,
    inFolder: Boolean,
    onOpen: (String) -> Unit,
    onMoveBook: (Book) -> Unit,
    onMoveOut: (Book) -> Unit,
    onDelete: (Book) -> Unit,
    onRenameBook: (Book) -> Unit,
    onTranslateAll: (Book) -> Unit,
    translatingBookId: String?,
    translatingProgress: Pair<Int, Int>?,
) {
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
                inFolder = inFolder,
                onOpen = { onOpen(book.id) },
                onLongPress = { onMoveBook(book) },
                onTranslateAll = { onTranslateAll(book) },
                onMove = { onMoveBook(book) },
                onMoveOut = { onMoveOut(book) },
                onDelete = { onDelete(book) },
                onRename = { onRenameBook(book) },
                isTranslating = translatingBookId == book.id,
                progressText = if (translatingBookId == book.id) translatingProgress?.let { "${it.first}/${it.second}" } else null,
            )
        }
    }
}

/** 收藏夹区：横向一排收藏夹卡片 + 「新建」。 */
@Composable
private fun FoldersSection(
    folders: List<Folder>,
    books: List<Book>,
    onEnterFolder: (String) -> Unit,
    onNewFolder: () -> Unit,
    onFolderMenu: (Folder) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("收藏夹", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onNewFolder) { Text("＋ 新建") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listItems(folders, key = { it.id }) { folder ->
                val count = books.count { it.folderId == folder.id }
                val cover = books.firstOrNull { it.folderId == folder.id }?.coverFile
                FolderCell(
                    folder = folder,
                    count = count,
                    coverFile = cover,
                    onClick = { onEnterFolder(folder.id) },
                    onLongClick = { onFolderMenu(folder) },
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FolderCell(
    folder: Folder,
    count: Int,
    coverFile: File?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(Modifier.width(92.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.72f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (coverFile != null) {
                AsyncImage(model = coverFile, contentDescription = folder.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Text(
                "$count",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .clip(CircleShape).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Text(folder.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
    }
}

/** 书卡片：点封面打开；长按→移动；右下角 ⋮ 打开菜单。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookCell(
    book: Book,
    inFolder: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onTranslateAll: () -> Unit,
    onMove: () -> Unit,
    onMoveOut: () -> Unit = {},
    onDelete: () -> Unit,
    onRename: () -> Unit = {},
    isTranslating: Boolean,
    progressText: String?,
) {
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(0.72f)) {
            AsyncImage(
                model = book.coverFile,
                contentDescription = book.title,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize().combinedClickable(onClick = onOpen, onLongClick = onLongPress),
            )
            // 右下角 ⋮ 菜单
            var menuOpen by remember { mutableStateOf(false) }
            Box(Modifier.align(Alignment.BottomEnd).padding(2.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "菜单",
                    tint = Color.White,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .clickable { menuOpen = true }
                        .padding(4.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (isTranslating) "翻译中…" else "全书翻译") },
                        onClick = { menuOpen = false; onTranslateAll() },
                        enabled = !isTranslating,
                    )
                    if (inFolder) {
                        DropdownMenuItem(text = { Text("移出文件夹") }, onClick = { menuOpen = false; onMoveOut() })
                    } else {
                        DropdownMenuItem(text = { Text("移动到文件夹…") }, onClick = { menuOpen = false; onMove() })
                    }
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menuOpen = false; onRename() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menuOpen = false; onDelete() })
                }
            }
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
        if (isTranslating) {
            val total = book.pageCount
            val done = progressText?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
            LinearProgressIndicator(
                progress = { if (total > 0) done.toFloat() / total else 0f },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
            Text(
                "翻译中 $progressText",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** 移动书到收藏夹（或移出）。 */
@Composable
private fun MoveBookDialog(
    book: Book,
    folders: List<Folder>,
    onMove: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动《${book.title}》到…") },
        text = {
            Column {
                if (folders.isEmpty()) {
                    Text("还没有收藏夹，先去书库页「新建」一个。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    if (book.folderId != null) {
                        TextButton(onClick = { onMove(null) }) { Text("移出（未分类）") }
                    }
                    folders.forEach { f ->
                        TextButton(onClick = { onMove(f.id) }) { Text(f.name) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 新建收藏夹。 */
@Composable
private fun CreateFolderDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建收藏夹") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name) }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 收藏夹菜单：重命名 / 删除。 */
@Composable
private fun FolderMenuDialog(
    folder: Folder,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("「${folder.name}」") },
        text = {
            Column {
                TextButton(onClick = onRename) { Text("重命名") }
                TextButton(onClick = onDelete) { Text("删除收藏夹") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 重命名收藏夹。 */
@Composable
private fun RenameFolderDialog(
    folder: Folder,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(folder.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名收藏夹") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 重命名书（导入时用的是文件名，可在这里改成自己喜欢的名字）。 */
@Composable
private fun RenameBookDialog(
    book: Book,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(book.title) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名书") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("书名") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
    // 只显示翻过/正在翻的书，不把没翻过的也铺出来
    val visibleBooks = books.filter { book ->
        translatingBookId == book.id ||
            (serverMap[book.id]?.let { it.donePages + it.failedPages > 0 } == true)
    }
    if (visibleBooks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("还没有翻译过的书", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listItems(visibleBooks, key = { it.id }) { book ->
            val s = serverMap[book.id]
            val isRunning = translatingBookId == book.id
            val done = if (isRunning) (translatingProgress?.first ?: 0) else (s?.donePages ?: 0)
            val failed = s?.failedPages ?: 0
            val total = book.pageCount
            val status = when {
                isRunning -> "进行中"
                done >= total && failed == 0 -> "已完成"
                failed > 0 && done + failed >= total -> "部分失败"
                else -> "进行中"
            }
            BookProgressRow(book.title, status, done, failed, total, isRunning)
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

/** 搜索：同时匹配收藏夹名和书名（含文件夹里的书）。点书打开、点文件夹跳进去。 */
@Composable
private fun SearchResults(
    query: String,
    books: List<Book>,
    folders: List<Folder>,
    onOpenBook: (String) -> Unit,
    onEnterFolder: (String) -> Unit,
) {
    val q = query.trim()
    if (q.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("输入书名或收藏夹名", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val folderById = folders.associateBy { it.id }
    val matchedFolders = folders.filter { it.name.contains(q, ignoreCase = true) }
    val matchedBooks = books.filter { it.title.contains(q, ignoreCase = true) }
    if (matchedFolders.isEmpty() && matchedBooks.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("没有找到「$q」", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (matchedFolders.isNotEmpty()) {
            item {
                Text(
                    "收藏夹",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            listItems(matchedFolders, key = { it.id }) { folder ->
                SearchRow(
                    title = folder.name,
                    subtitle = "${books.count { it.folderId == folder.id }} 本",
                    onClick = { onEnterFolder(folder.id) },
                )
            }
        }
        if (matchedBooks.isNotEmpty()) {
            item {
                Text(
                    "漫画",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            listItems(matchedBooks, key = { it.id }) { book ->
                val folderName = book.folderId?.let { folderById[it]?.name }
                SearchRow(
                    title = book.title,
                    subtitle = if (folderName != null) "在「$folderName」" else "未分类",
                    onClick = { onOpenBook(book.id) },
                )
            }
        }
    }
}

@Composable
private fun SearchRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 「继续阅读」卡片：显示上次读的书和进度，点它跳到上次那页。 */
@Composable
private fun ContinueReadingCard(book: Book, page: Int, onContinue: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onContinue),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = book.coverFile,
                contentDescription = book.title,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.size(width = 44.dp, height = 60.dp).clip(MaterialTheme.shapes.small),
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text("继续阅读", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(book.title, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "读到第 ${(page + 1).coerceAtMost(book.pageCount)} / ${book.pageCount} 页",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
