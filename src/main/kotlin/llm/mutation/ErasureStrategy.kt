package llm.mutation

import core.mutation.strategy.common.MutationResult
import llm.client.LlmClient
import llm.generate.PromptConstructor
import kotlin.random.Random

/**
 * Заменяет случайные строки маркером [SKIPPED_MARKER],
 * затем отправляет код в LLM для восстановления пропусков.
 * Количество замен: от 1 до max(1, totalChars / 2000).
 */
class ErasureStrategy(
    promptConstructor: PromptConstructor,
    llmClient: LlmClient,
) : MutationStrategyLLM(promptConstructor, llmClient) {

    // instructionPrompt не используется — apply полностью переопределён
    override val instructionPrompt: String = ""

    override fun apply(javaCode: String, className: String, packageName: String): MutationResult {
        val erased = eraseRandomLines(javaCode)

        return try {
            val instruction = """
                You are given a Java program where some lines have been replaced by the marker: $SKIPPED_MARKER
                Your task: fill in each $SKIPPED_MARKER with valid Java code so that the program:
                  - compiles without errors,
                  - runs and terminates within 10 seconds,
                  - preserves the original structure of the surrounding code.
                Keep ALL unchanged lines exactly as they are. Only replace the $SKIPPED_MARKER markers.
            """.trimIndent()

            val prompt = promptConstructor.build(
                role = rolePrompt,
                instruction = instruction,
                programContext = "=== CODE WITH GAPS ===\n$erased",
                outputRules = outputRulesPrompt(className, packageName),
            )

            val raw = llmClient.generate(userPrompt = prompt, systemPrompt = null)
            val cleaned = stripFences(raw)

            if (isValidJava(cleaned, className)) {
                MutationResult(cleaned, hadError = false)
            } else {
                println("[ErasureStrategy] Invalid LLM output — keeping original")
                MutationResult(javaCode, hadError = true)
            }
        } catch (e: Exception) {
            println("[ErasureStrategy] Failed: ${e.message}")
            MutationResult(javaCode, hadError = true)
        }
    }

    /**
     * Заменяет случайные непустые строки маркером [SKIPPED_MARKER].
     * Количество стираний: random(1 .. max(1, totalChars / 2000)).
     */
    private fun eraseRandomLines(code: String): String {
        val lines = code.lines().toMutableList()

        val maxErasures = maxOf(1, code.length / 2000)
        val count = Random.nextInt(1, maxErasures + 1)

        // только непустые строки
        val candidates = lines.indices.filter { lines[it].isNotBlank() }
        val toErase = candidates.shuffled().take(count)

        toErase.forEach { idx -> lines[idx] = SKIPPED_MARKER }

        println("[ErasureStrategy] Erased $count line(s) out of ${lines.size} (maxErasures=$maxErasures)")
        return lines.joinToString("\n")
    }

    companion object {
        private const val SKIPPED_MARKER = "/* SKIPPED */"
    }
}
