package llm.client

import okhttp3.OkHttpClient
import java.time.Duration

enum class LlmProvider {
    OPENAI,
    OLLAMA
}

data class ChatMessage(
    val role: String,
    val content: String
)

interface LlmClient {
    fun generate(userPrompt: String, systemPrompt: String? = null): String
    fun chat(messages: List<ChatMessage>): String
}

/**
 * Создаёт [OkHttpClient] с таймаутами из [ConfigModel.timeoutSeconds].
 * Используется всеми LLM-клиентами для единообразной конфигурации HTTP.
 */
fun ConfigModel.buildHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .readTimeout(Duration.ofSeconds(timeoutSeconds))
        .writeTimeout(Duration.ofSeconds(timeoutSeconds))
        .callTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()