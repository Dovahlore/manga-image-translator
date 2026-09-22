package com.mit.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.mit.reader.ui.AppNav
import com.mit.reader.ui.theme.MangaReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOpenIntent(intent)   // 冷启动：可能是从别的 App「打开」epub/mobi 进来的
        setContent {
            MangaReaderTheme {
                AppNav()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenIntent(intent)   // 已在运行时再次「打开」/「分享」文件
    }

    /** 处理外部「打开方式」/「分享」进来的 epub、mobi：交给 ReaderApp 导入并跳阅读器。 */
    private fun handleOpenIntent(intent: Intent?) {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            else -> null
        } ?: return
        (application as ReaderApp).openDocument(uri)
    }
}
