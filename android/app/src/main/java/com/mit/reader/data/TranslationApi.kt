package com.mit.reader.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class TranslateResp(val pageId: Int, val jobId: String?, val cached: Boolean)
data class JobStatus(val status: String, val pageId: Int?, val error: String?)
data class ServerPage(val id: Int, val pageIndex: Int, val status: String)
data class ServerBook(
    val id: String,
    val title: String?,
    val pageCount: Int?,
    val donePages: Int,
    val failedPages: Int,
)

/**
 * app_api 客户端。地址 / API Key 每次都从 ServerConfig 现取（改完设置立刻生效）。
 * 鉴权：请求头 X-API-Token（ServerConfig.apiKey 为空则不带，服务端此时不校验）。
 */
class TranslationApi {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val base get() = ServerConfig.baseUrl

    private fun Request.Builder.authed(): Request.Builder =
        apply { ServerConfig.apiKey.takeIf { it.isNotBlank() }?.let { header("X-API-Token", it) } }

    /** 上传一页翻译。async=true 立刻拿 jobId 去轮询；false 同步等结果（慢）。 */
    suspend fun translate(image: File, bookId: String, pageIndex: Int, async: Boolean, force: Boolean = false): TranslateResp =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("image", image.name, image.asRequestBody("image/jpeg".toMediaType()))
                .addFormDataPart("book_id", bookId)
                .addFormDataPart("page_index", pageIndex.toString())
                .apply {
                    if (async) addFormDataPart("async_mode", "true")
                    if (force) addFormDataPart("force", "true")
                }
                .build()
            val req = Request.Builder().url("$base/v1/pages/translate").authed().post(body).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
                val j = JSONObject(text)
                TranslateResp(
                    pageId = j.optInt("page_id", -1),
                    jobId = j.optString("job_id").takeIf { it.isNotEmpty() },
                    cached = j.optBoolean("cached", false),
                )
            }
        }

    suspend fun job(jobId: String): JobStatus = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val req = Request.Builder().url("$base/v1/jobs/$jobId").authed().build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(200)}")
            val j = JSONObject(text)
            JobStatus(
                status = j.optString("status"),
                pageId = if (j.isNull("page_id")) null else j.optInt("page_id"),
                error = j.optString("error").takeIf { it.isNotEmpty() },
            )
        }
    }

    /** 拉服务端全部书的翻译汇总（done/failed 页数），给书库「翻译进度」页用。 */
    suspend fun listBooks(): List<ServerBook> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = Request.Builder().url("$base/v1/books").authed().build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(200)}")
                val j = JSONObject(text)
                val arr = j.optJSONArray("books") ?: JSONArray()
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ServerBook(
                        id = o.getString("id"),
                        title = o.optString("title").takeIf { it.isNotBlank() },
                        pageCount = if (o.isNull("page_count")) null else o.optInt("page_count"),
                        donePages = o.optInt("done_pages", 0),
                        failedPages = o.optInt("failed_pages", 0),
                    )
                }
            }
        }

    /** 查某本书在服务端已有的页（page_id/page_index/status），用于全书翻译时跳过已翻好的页。 */
    suspend fun bookPages(bookId: String): List<ServerPage> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val req = Request.Builder().url("$base/v1/books/$bookId/pages").authed().build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(200)}")
                val j = JSONObject(text)
                val arr = j.optJSONArray("pages") ?: JSONArray()
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ServerPage(
                        id = o.getInt("id"),
                        pageIndex = o.getInt("page_index"),
                        status = o.getString("status"),
                    )
                }
            }
        }

    /** 全书翻译：一次把若干页图传上去，服务端按顺序后台跑（返回后轮询 bookPages 看进度）。 */
    suspend fun translateAll(
        bookId: String,
        title: String?,
        orderDir: String?,
        pageCount: Int,
        pageIndices: List<Int>,
        images: List<File>,
    ) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("book_id", bookId)
            .addFormDataPart("page_count", pageCount.toString())
            .addFormDataPart("page_indices", JSONArray(pageIndices).toString())
        title?.takeIf { it.isNotBlank() }?.let { builder.addFormDataPart("title", it) }
        orderDir?.let { builder.addFormDataPart("order_dir", it) }
        for (img in images) {
            builder.addFormDataPart("images", img.name, img.asRequestBody("image/jpeg".toMediaType()))
        }
        val req = Request.Builder().url("$base/v1/books/translate-all").authed().post(builder.build()).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
        }
    }

    suspend fun deleteBook(bookId: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val req = Request.Builder().url("$base/v1/books/$bookId").authed().delete().build()
        client.newCall(req).execute().use { it.isSuccessful }
    }

    /** 测连通：GET /v1/health，返回状态说明。 */
    suspend fun ping(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$base/v1/health").authed().build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.isSuccessful) "OK: ${text.take(120)}" else "HTTP ${resp.code}: ${text.take(120)}"
            }
        }.getOrElse { "连接失败: ${it.message}" }
    }

    fun translatedUrl(pageId: Int) = "$base/v1/pages/$pageId/image"
    fun originalUrl(pageId: Int) = "$base/v1/pages/$pageId/image?orig=1"

    suspend fun download(url: String, out: File) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val req = Request.Builder().url(url).authed().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            resp.body?.byteStream()?.use { it.copyTo(out.outputStream()) }
                ?: throw IllegalStateException("空响应")
        }
    }
}
