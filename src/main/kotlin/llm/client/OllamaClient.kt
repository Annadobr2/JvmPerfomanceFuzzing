package llm.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource

class OllamaClient(
    private val config: ConfigModel
) : LlmClient {

    private val mapper = jacksonObjectMapper()
    private val jsonMediaType = "application/json".toMediaType()

    private val httpClient: OkHttpClient = config.buildHttpClient()

    override fun generate(userPrompt: String, systemPrompt: String?): String {
        val payload = mutableMapOf<String, Any>(
            "model" to config.model,
            "prompt" to userPrompt,
            "stream" to true,
            "options" to mapOf(
                "temperature" to config.temperature,
                "top_p" to config.topP
            )
        )

        if (!systemPrompt.isNullOrBlank()) {
            payload["system"] = systemPrompt
        }

        val bodyJson = mapper.writeValueAsString(payload)

        val request = Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}/api/generate")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body ?: throw IllegalStateException("Пустой ответ от Ollama")

            if (!response.isSuccessful) {
                val err = responseBody.string()
                throw IllegalStateException("Ollama HTTP ${response.code}: ${err.take(1000)}")
            }

            val result = StringBuilder()
            responseBody.source().use { source ->
                readOllamaStream(source, result)
            }

            val text = result.toString().trim()
            if (text.isBlank()) {
                throw IllegalStateException("Ollama вернул пустой ответ")
            }

            return text
        }
    }

    override fun chat(messages: List<ChatMessage>): String {
        val systemPrompt = messages
            .filter { it.role == "system" }
            .joinToString("\n") { it.content }

        val userPrompt = messages
            .filter { it.role != "system" }
            .joinToString("\n") { "${it.role}: ${it.content}" }

        return generate(
            userPrompt = userPrompt,
            systemPrompt = systemPrompt.ifBlank { null }
        )
    }

    private fun readOllamaStream(source: BufferedSource, sb: StringBuilder) {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: continue
            if (line.isBlank()) continue

            val node = try {
                mapper.readTree(line)
            } catch (_: Exception) {
                continue
            }

            val chunk = node["response"]?.asText().orEmpty()
            if (chunk.isNotEmpty()) {
                sb.append(chunk)
            }

            if (node["done"]?.asBoolean(false) == true) {
                break
            }
        }
    }
}