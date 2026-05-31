package llm.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAiClient(
    private val config: ConfigModel
) : LlmClient {

    private val objectMapper = jacksonObjectMapper()
    private val jsonMediaType = "application/json".toMediaType()

    private val httpClient: OkHttpClient = config.buildHttpClient()

    override fun generate(userPrompt: String, systemPrompt: String?): String {
        val messages = buildList {
            if (!systemPrompt.isNullOrBlank()) {
                add(ChatMessage(role = "system", content = systemPrompt))
            }
            add(ChatMessage(role = "user", content = userPrompt))
        }

        return chat(messages)
    }

    override fun chat(messages: List<ChatMessage>): String {
        val requestBody = mapOf(
            "model" to config.model,
            "messages" to messages,
            "temperature" to config.temperature,
            "top_p" to config.topP,
            "max_tokens" to config.maxTokens,
            "stream" to false
        )

        val bodyJson = try {
            objectMapper.writeValueAsString(requestBody)
        } catch (e: Exception) {
            throw IllegalStateException("Не удалось сериализовать запрос к OpenAI-compatible API", e)
        }

        val apiKey = config.apiKey?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Не указан apiKey")

        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "OpenAI-compatible API HTTP ${response.code}: $responseText"
                )
            }

            val root = try {
                objectMapper.readTree(responseText)
            } catch (e: Exception) {
                throw IllegalStateException("Не удалось распарсить ответ API: $responseText", e)
            }

            val text = root["choices"]
                ?.firstOrNull()
                ?.get("message")
                ?.get("content")
                ?.asText()
                ?.trim()
                .orEmpty()

            if (text.isBlank()) {
                throw IllegalStateException("Модель вернула пустой ответ: $responseText")
            }

            return text
        }
    }
}