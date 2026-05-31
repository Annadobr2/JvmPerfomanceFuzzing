package llm.client

typealias LlmClientCreator = (ConfigModel) -> LlmClient

object LlmClientFactory {

    private val registry: MutableMap<LlmProvider, LlmClientCreator> = mutableMapOf()

    init {
        register(LlmProvider.OPENAI) { OpenAiClient(it) }
        register(LlmProvider.OLLAMA) { OllamaClient(it) }
    }

    fun register(provider: LlmProvider, creator: LlmClientCreator) {
        registry[provider] = creator
    }

    fun create(config: ConfigModel): LlmClient {
        val creator = registry[config.provider]
            ?: throw IllegalArgumentException("Нет клиента для провайдера: ${config.provider}")

        return creator(config)
    }
}