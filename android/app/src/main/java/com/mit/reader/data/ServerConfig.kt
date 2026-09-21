package com.mit.reader.data

import android.content.Context
import android.content.SharedPreferences

/** 服务器配置（可改）：地址支持 http/https + 域名/端口；API Key 做接口鉴权。 */
object ServerConfig {
    // 默认指向本机（后端 mit-app-api）。真机/模拟器都在同一局域网时用这个 IP；
    // 换电脑或改端口在「设置」里改。Android 模拟器里 10.0.2.2 = 宿主机。
    const val DEFAULT_URL = "http://192.168.0.90:8020"

    private const val PREFS = "server"
    private const val KEY_URL = "base_url"
    private const val KEY_KEY = "api_key"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var baseUrl: String
        get() = prefs?.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
        set(value) {
            prefs?.edit()?.putString(KEY_URL, value.trim().trimEnd('/'))?.apply()
        }

    /** API Key 兼作账号：同一个 Key 的多台设备互通，不同 Key 互不可见（服务端用其 SHA-256 作 owner）。 */
    var apiKey: String
        get() = prefs?.getString(KEY_KEY, "")?.trim().orEmpty()
        set(value) {
            prefs?.edit()?.putString(KEY_KEY, value.trim())?.apply()
        }
}
