package llm.client

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

data class ConfigModel(
    val provider: LlmProvider,
    val baseUrl: String,
    val apiKey: String? = null,
    val model: String,
    val temperature: Double = 0.2,
    val topP: Double = 0.9,
    val maxTokens: Int = 2000,
    val timeoutSeconds: Long = 120
) {
    companion object {
        private val mapper = jacksonObjectMapper()

        // Переменные окружения для чувствительных и деплой-специфичных значений.
        // Остальные параметры (temperature, maxTokens, timeoutSeconds) берутся только из setting.json.
        private const val ENV_API_KEY  = "LLM_API_KEY"
        private const val ENV_BASE_URL = "LLM_BASE_URL"
        private const val ENV_MODEL    = "LLM_MODEL"
        private const val ENV_PROVIDER = "LLM_PROVIDER"

        /**
         * Загружает [ConfigModel] из `/resources/setting.json`.
         *
         * Приоритет значений для provider/baseUrl/apiKey/model:
         *  1. Переменные окружения ОС (`System.getenv`) — используются в Docker и CI.
         *  2. Файл `.env` в рабочей директории — используется при локальном запуске.
         *  3. Значения из `setting.json` — fallback/дефолты.
         *
         * Остальные параметры (temperature, topP, maxTokens, timeoutSeconds) берутся только из файла.
         */
        fun load(): ConfigModel {
            val stream = ConfigModel::class.java.getResourceAsStream("/setting.json")
                ?: throw RuntimeException("Файл настроек не найден в resources")
            val base = stream.use { mapper.readValue<ConfigModel>(it) }

            // Загружаем .env как fallback для локального запуска
            val dotEnv = loadDotEnv()

            return base.copy(
                provider = env(ENV_PROVIDER, dotEnv)?.let { LlmProvider.valueOf(it.uppercase()) } ?: base.provider,
                baseUrl  = env(ENV_BASE_URL, dotEnv) ?: base.baseUrl,
                apiKey   = env(ENV_API_KEY,  dotEnv) ?: base.apiKey,
                model    = env(ENV_MODEL,    dotEnv) ?: base.model,
            ).also { result ->
                if (result.provider != base.provider) println("[ConfigModel] provider ← $ENV_PROVIDER")
                if (result.baseUrl  != base.baseUrl)  println("[ConfigModel] baseUrl  ← $ENV_BASE_URL")
                if (result.apiKey   != base.apiKey)   println("[ConfigModel] apiKey   ← $ENV_API_KEY")
                if (result.model    != base.model)    println("[ConfigModel] model    ← $ENV_MODEL")
            }
        }

        /**
         * Приоритет: системная переменная окружения → .env файл.
         */
        private fun env(name: String, dotEnv: Map<String, String>): String? =
            System.getenv(name)?.takeIf { it.isNotBlank() }
                ?: dotEnv[name]?.takeIf { it.isNotBlank() }

        /**
         * Читает `.env` из рабочей директории. Игнорирует строки-комментарии и пустые строки.
         * Формат: `KEY=VALUE` (кавычки вокруг значения не обязательны, но поддерживаются).
         */
        private fun loadDotEnv(): Map<String, String> {
            val file = File(".env")
            if (!file.exists()) return emptyMap()

            return file.readLines()
                .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx < 1) return@mapNotNull null
                    val key   = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                    key to value
                }
                .toMap()
                .also { if (it.isNotEmpty()) println("[ConfigModel] загружен .env (${it.size} переменных)") }
        }
    }
}
