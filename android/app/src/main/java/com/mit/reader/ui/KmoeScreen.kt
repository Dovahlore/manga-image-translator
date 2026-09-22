package com.mit.reader.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.mit.reader.ReaderApp
import kotlinx.coroutines.launch

/** Kmoe 默认地址。 */
const val KMOE_URL = "https://www.koz.moe/"

/** 内嵌 WebView 快速访问站点，下载的 epub/mobi 直接落到默认书库文件夹并扫描导入。 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun KmoeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ReaderApp
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kmoe") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { pad ->
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = WebViewClient()
                    // 拦截下载：只允许 epub/mobi；下载后尝试自动导入，失败就删文件
                    setDownloadListener { url, _, contentDisposition, _, _ ->
                        val name = url.substringAfterLast('/').substringBefore('?')
                            .ifBlank { "download_${System.currentTimeMillis()}" }
                        val lower = name.lowercase()
                        if (!lower.endsWith(".epub") && !lower.endsWith(".mobi")) {
                            Toast.makeText(context, "只支持下载 epub / mobi", Toast.LENGTH_SHORT).show()
                            return@setDownloadListener
                        }
                        scope.launch {
                            runCatching { app.library.downloadToBooks(url, name) }
                                .onSuccess { f ->
                                    runCatching { app.library.importFile(f) }
                                        .onSuccess { book ->
                                            f.delete()   // 导入成功，删暂存文件
                                            Toast.makeText(context, "已下载并导入《${book.title}》", Toast.LENGTH_SHORT).show()
                                        }
                                        .onFailure { e ->
                                            f.delete()   // 导入失败，删掉文件
                                            Toast.makeText(context, "导入失败，已删除：${e.message}", Toast.LENGTH_LONG).show()
                                        }
                                }
                                .onFailure { e ->
                                    Toast.makeText(context, "下载失败：${e.message}", Toast.LENGTH_LONG).show()
                                }
                        }
                    }
                    loadUrl(KMOE_URL)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
