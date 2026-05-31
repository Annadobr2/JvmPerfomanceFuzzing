package llm.mutation

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Сгенерированный мутационный промпт
 *
 * @param name               короткое уникальное имя стратегии
 * @param instruction        инструкция для LLM-мутатора: что именно изменить в коде
 * @param triggerConditions  вычислительный контекст при котором паттерн из [instruction]
 *                           вызывает расхождение между JVM, если такой требуется
 */
data class MutationPrompt(
    @JsonProperty("name")               val name: String,
    @JsonProperty("instruction")        val instruction: String,
    @JsonProperty("triggerConditions")  val triggerConditions: String = "",
)
