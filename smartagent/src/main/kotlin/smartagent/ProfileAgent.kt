package smartagent

import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

internal class ProfileAgent(private val session: ChatSession) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun update() {
        val apiKey = Config.apiKey(session.currentModel) ?: return
        val systemPrompt = loadSystemPrompt()
        val lastInputs = session.getLastUserInputs(3)
        if (lastInputs.isEmpty()) return

        val currentProfile = session.loadUserProfile()

        val userContent = buildString {
            if (currentProfile.isNotBlank()) {
                appendLine("Текущий профиль пользователя:")
                appendLine(currentProfile)
                appendLine()
            }
            appendLine("Последние сообщения пользователя:")
            lastInputs.forEachIndexed { i, msg ->
                appendLine("${i + 1}. $msg")
            }
        }.trimEnd()

        val messages = listOf(
            Message("system", systemPrompt),
            Message("user", userContent)
        )
        val requestBody = json.encodeToString(ChatRequest(session.currentModel.apiModelId, messages))

        val request = Request.Builder()
            .url(session.currentModel.url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        runCatching {
            val response = http.newCall(request).execute()
            val body = response.body?.string() ?: ""
            NetworkLogger.log(
                url = session.currentModel.url,
                reqHeaders = request.headers.toMap(),
                reqBody = requestBody,
                statusCode = response.code,
                resHeaders = response.headers.toMap(),
                resBody = body,
                source = "[PROFILE_AGENT]"
            )
            if (response.isSuccessful) {
                val chatResponse = json.decodeFromString<ChatResponse>(body)
                val updatedProfile = chatResponse.choices.firstOrNull()?.message?.content?.trim() ?: return
                if (updatedProfile.isNotBlank()) {
                    session.saveUserProfile(updatedProfile)
                }
            }
        }
    }

    private fun loadSystemPrompt(): String {
        val paths = listOf(
            "smartagent/src/main/kotlin/prompts/profile_system.md",
            "src/main/kotlin/prompts/profile_system.md",
            "prompts/profile/profile_system.md"
        )
        return paths.firstOrNull { File(it).exists() }?.let { File(it).readText() }
            ?: FALLBACK_PROFILE_PROMPT
    }
}

private fun okhttp3.Headers.toMap(): Map<String, String> = buildMap {
    forEach { (name, value) -> put(name, value) }
}

private val FALLBACK_PROFILE_PROMPT = """
Ты — служебный агент для формирования долговременного профиля пользователя.
Извлекай только явно указанные факты и предпочтения. Не делай предположений.
Обновляй профиль инкрементально. Если новое сообщение меняет ранее сохранённый факт — оставляй только актуальное значение.
Возвращай только markdown без пояснений.
""".trimIndent()
