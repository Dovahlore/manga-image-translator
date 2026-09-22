package com.mit.reader.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.mit.reader.ReaderApp
import com.mit.reader.data.ServerConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as ReaderApp
    val context = LocalContext.current
    var url by remember { mutableStateOf(ServerConfig.baseUrl) }
    var key by remember { mutableStateOf(ServerConfig.apiKey) }
    var result by remember { mutableStateOf("") }
    var usageText by remember { mutableStateOf("") }
    var folderName by remember { mutableStateOf(ServerConfig.libraryFolderName) }
    var scanResult by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // 书库文件夹选择（SAF 树）：拿到权限后持久化，重启不失效
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            ServerConfig.libraryFolderUri = uri.toString()
            val name = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
            ServerConfig.libraryFolderName = name
            folderName = name
            scanResult = if (name != null) "已选择：$name" else "已选择文件夹"
        }
    }

    LaunchedEffect(Unit) {
        val sb = StringBuilder()
        runCatching { app.api.usage() }
            .onSuccess { u ->
                sb.appendLine("用户 ID：${u.userId}")
                sb.appendLine("Token 消耗：${u.tokenUsed}")
                sb.appendLine("翻页次数：${u.pageCount}")
                u.lastActiveAt?.let { sb.appendLine("最后活跃：$it") }
            }
            .onFailure { sb.appendLine("用量获取失败：${it.message}") }
        runCatching { app.api.cloudList() }
            .onSuccess { list ->
                val totalBytes = list.sumOf { it.size ?: 0L }
                sb.appendLine("云端书：${list.size} 本 · 占用 ${formatBytes(totalBytes)}")
            }
            .onFailure { sb.appendLine("云端用量获取失败：${it.message}") }
        usageText = sb.toString().trimEnd()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("服务器设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            Text(
                "服务器地址（局域网填 http://IP:8020，线上填 https://域名）",
                style = MaterialTheme.typography.labelMedium,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("http://192.168.0.90:8020 或 https://mit.example.com") },
            )
            Text(
                "API Key（鉴权 + 云同步账号：与服务端 app.env 的 MIT_API_TOKEN 一致；同一个 Key 多设备互通，不同 Key 互不可见）",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("X-API-Token") },
            )
            Row(Modifier.padding(top = 16.dp)) {
                Button(
                    onClick = {
                        ServerConfig.baseUrl = url
                        ServerConfig.apiKey = key
                        result = "已保存"
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("保存") }
                Button(
                    onClick = {
                        ServerConfig.baseUrl = url
                        ServerConfig.apiKey = key
                        result = "测试中…"
                        scope.launch { result = app.api.ping() }
                    },
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                ) { Text("测试连接") }
            }
            TextButton(
                onClick = {
                    url = ServerConfig.DEFAULT_URL
                    result = "已填回局域网默认地址（保存后生效）"
                },
            ) { Text("恢复局域网默认地址") }
            if (result.isNotBlank()) {
                Text(result, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
            }

            // ---- 账号用量 ----
            Text(
                "账号用量",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                if (usageText.isBlank()) "加载中…" else usageText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            // ---- 书库文件夹（自动扫描导入 epub/mobi）----
            Text(
                "书库文件夹（自动扫描导入）",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
            Text(
                if (folderName.isNullOrBlank()) "未选择：点「选择文件夹」指定一个目录，App 会自动扫描其中的 .epub / .mobi 并导入（按内容去重）。"
                else "当前：$folderName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.padding(top = 8.dp)) {
                Button(onClick = { folderPicker.launch(null) }, modifier = Modifier.weight(1f)) { Text("选择文件夹") }
                Button(
                    onClick = {
                        scanResult = "扫描中…"
                        scope.launch {
                            val n = runCatching { app.library.scanLibraryFolder() }.getOrDefault(-1)
                            scanResult = if (n >= 0) "扫描完成，新导入 $n 本" else "扫描失败（离线或无法访问文件夹）"
                        }
                    },
                    enabled = !ServerConfig.libraryFolderUri.isNullOrBlank(),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                ) { Text("重新扫描") }
            }
            if (scanResult.isNotBlank()) {
                Text(scanResult, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** 字节数 → 可读大小。 */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
