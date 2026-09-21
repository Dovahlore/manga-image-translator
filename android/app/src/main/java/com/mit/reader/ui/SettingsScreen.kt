package com.mit.reader.ui

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
import com.mit.reader.ReaderApp
import com.mit.reader.data.ServerConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as ReaderApp
    var url by remember { mutableStateOf(ServerConfig.baseUrl) }
    var key by remember { mutableStateOf(ServerConfig.apiKey) }
    var result by remember { mutableStateOf("") }
    var usageText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

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
