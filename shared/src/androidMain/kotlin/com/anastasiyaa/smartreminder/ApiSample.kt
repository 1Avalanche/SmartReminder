package com.anastasiyaa.smartreminder

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject

data class Post(
    val userId: Int,
    val id: Int,
    val title: String,
    val body: String,
)

object ApiSample {
    private const val TAG = "ApiSample"
    private const val OKHTTP_TAG = "OkHttp"
    private const val BASE_URL = "https://jsonplaceholder.typicode.com"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            HttpLoggingInterceptor { message -> Log.d(OKHTTP_TAG, message) }
                .apply { level = HttpLoggingInterceptor.Level.BODY }
        )
        .build()

    suspend fun fetchPost(id: Int = 1): Result<Post> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$BASE_URL/posts/$id")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("HTTP ${response.code}: ${response.message}")
                }
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                Post(
                    userId = json.getInt("userId"),
                    id = json.getInt("id"),
                    title = json.getString("title"),
                    body = json.getString("body"),
                )
            }
        }.onSuccess { post ->
            Log.i(TAG, "fetchPost($id) ok: id=${post.id} userId=${post.userId} title=\"${post.title}\"")
        }.onFailure { e ->
            Log.w(TAG, "fetchPost($id) failed: ${e.message}", e)
        }
    }
}
