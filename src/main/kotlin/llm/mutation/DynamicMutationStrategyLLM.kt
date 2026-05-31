package llm.mutation

import llm.client.LlmClient
import llm.generate.PromptConstructor

/**
 * Динамическая LLM-стратегия мутации, созданная из [MutationPrompt].
 * Принимает готовый промпт из [prompt.instruction].
 */
class DynamicMutationStrategyLLM(
    val prompt: MutationPrompt,
    promptConstructor: PromptConstructor,
    llmClient: LlmClient,
) : MutationStrategyLLM(promptConstructor, llmClient) {

    /**
     * Инструкция мутации + условия воспроизведения из [MutationPrompt.triggerConditions].
     * Если [triggerConditions] непустой —  "REQUIRED COMPUTATIONAL CONTEXT"
     */
    override val instructionPrompt: String = buildString {
        append(prompt.instruction)
        if (prompt.triggerConditions.isNotBlank()) {
            appendLine()
            appendLine()
            appendLine("REQUIRED COMPUTATIONAL CONTEXT (the mutated code MUST satisfy these conditions):")
            appendLine(prompt.triggerConditions)
        }
    }

    /**
     * Возвращает имя промпта вместо имени класса.
     * Все динамические стратегии имеют одинаковый simpleName,
     * AdaptiveMutator использует это имя как ключ статистики.
     */
    override fun strategyId(): String = prompt.name

    override fun toString(): String = "DynamicStrategy(${prompt.name})"
}
