package llm.client

class OpenAiConnectionTest(private val config: ConfigModel) {

    fun run(): Boolean {
        println("=== OpenAI Connection Test ===")
        println("URL  : ${config.baseUrl}")
        println("Model: ${config.model}")


        val client = OpenAiClient(config)

        print("Sending test request... ")
        val result = runCatching {
            client.generate(userPrompt = "Reply with exactly: OK")
        }

        return result.fold(
            onSuccess = { text ->
                println("SUCCESS")
                println("Response: $text")
                true
            },
            onFailure = { e ->
                println("FAILED")
                println("Error: ${e.message}")
                printHint(e.message.orEmpty())
                false
            }
        )
    }

    private fun printHint(message: String) {
        println()
        when {
            "401" in message -> {
                println("→ Неверный API ключ.")
            }
            "403" in message -> println("→ Нет доступа. Ключ не имеет прав или аккаунт заблокирован.")
            "429" in message -> {
                println("→ Rate limit или недостаточно средств.")
            }
            "timeout" in message.lowercase() -> println("→ Таймаут. Проверьте интернет или увеличьте timeoutSeconds.")
            "connect" in message.lowercase() -> println("→ Не удалось подключиться")
            else -> println("→ Неизвестная ошибка.")
        }
    }
}