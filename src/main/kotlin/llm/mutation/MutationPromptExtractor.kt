package llm.mutation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import llm.client.LlmClient
import llm.parsing.BugsStructure
import java.io.File

/**
 * Генерирует до [TARGET_COUNT] мутационных промптов на основе списка JDK-багов.
 * Результат кэшируется в [promptsFile]. При повторном запуске загружается из кэша.
 * При отсутствии валидных промптов возвращает пустой список.
 */
class MutationPromptExtractor(
    private val llmClient: LlmClient,
    private val promptsFile: File = File("data/mutation_prompts.json"),
) {
    companion object {
        const val TARGET_COUNT = 40
        private const val MAX_BUG_SUMMARY_CHARS = 8_000
        private val SYSTEM_PROMPT = """
            You are a JVM performance testing expert specializing in differential fuzzing.
            You generate mutation strategy descriptions for a bytecode-level JVM fuzzer.
            Always respond with valid JSON only — no prose, no markdown.
        """.trimIndent()
    }

    private val mapper = jacksonObjectMapper()


    /**
     * Возвращает список мутационных промптов.
     */
    fun extractOrLoad(bugs: List<BugsStructure>): List<MutationPrompt> {
        if (promptsFile.exists() && promptsFile.length() > 0) {
            println("[MutationPromptExtractor] Loading cached prompts from ${promptsFile.path}")
            return load()
        }
        return extract(bugs)
    }

    /**
     * Загружает промпты только из кэша.
     */
    fun loadOnly(): List<MutationPrompt> {
        if (!promptsFile.exists() || promptsFile.length() == 0L) {
            println("[MutationPromptExtractor] Cache file not found: ${promptsFile.path} — returning empty list")
            return emptyList()
        }
        return load()
    }

    /**
     * Принудительно перегенерация промптов
     */
    fun extract(bugs: List<BugsStructure>): List<MutationPrompt> {
        println("[MutationPromptExtractor] Generating $TARGET_COUNT mutation prompts from ${bugs.size} bugs...")

        val prompts = try {
            val raw = llmClient.generate(
                userPrompt = buildExtractionPrompt(bugs),
                systemPrompt = SYSTEM_PROMPT,
            )
            parsePrompts(raw)
        } catch (e: Exception) {
            println("[MutationPromptExtractor] LLM call failed: ${e.message}")
            emptyList()
        }

        if (prompts.isEmpty()) {
            println("[MutationPromptExtractor] No valid prompts generated — returning empty list")
            return emptyList()
        }

        println("[MutationPromptExtractor] Generated ${prompts.size} prompts. Saving to ${promptsFile.path}")
        save(prompts)
        return prompts
    }

    private fun load(): List<MutationPrompt> = try {
        mapper.readValue<List<MutationPrompt>>(promptsFile).also {
            println("[MutationPromptExtractor] Loaded ${it.size} prompts")
        }
    } catch (e: Exception) {
        println("[MutationPromptExtractor] Failed to load cache: ${e.message}. Will regenerate.")
        emptyList()
    }

    private fun save(prompts: List<MutationPrompt>) {
        try {
            promptsFile.parentFile?.mkdirs()
            mapper.writerWithDefaultPrettyPrinter().writeValue(promptsFile, prompts)
        } catch (e: Exception) {
            println("[MutationPromptExtractor] Failed to save prompts: ${e.message}")
        }
    }

    private fun buildExtractionPrompt(bugs: List<BugsStructure>): String = buildString {
        appendLine("You are given a list of JDK performance bug summaries.")
        appendLine("Analyze them and generate exactly $TARGET_COUNT DIVERSE Java code mutation instructions.")
        appendLine()
        appendLine("Each instruction describes HOW to MODIFY an existing Java program to expose JVM performance differences.")
        appendLine("The LLM mutator will receive an existing Java program and must change it according to the instruction.")
        appendLine("Cover many different JVM areas: GC, JIT compilation, boxing/unboxing, threading,")
        appendLine("memory allocation, bytecode patterns, exception handling, array operations, etc.")
        appendLine()
        appendLine("Return ONLY a valid JSON array — no markdown, no backticks, no explanation.")
        appendLine("""
            Format:
            [
              {
                "name": "ShortCamelCaseName",
                "instruction": "Modify the provided Java code to...",
                "triggerConditions": "This pattern exposes JVM differences when: ..."
              },
              ...
            ]
        """.trimIndent())
        appendLine()
        appendLine("Rules:")
        appendLine("- name: CamelCase, 1-3 words, unique across all 40 entries")
        appendLine("- instruction: 2-4 sentences, specific and actionable. MUST start with 'Modify the provided Java code to...'")
        appendLine("- The instruction tells the LLM how to CHANGE the given code, NOT how to generate new code from scratch")
        appendLine("- triggerConditions: 2-4 sentences describing the COMPUTATIONAL CONTEXT required for this mutation to")
        appendLine("  actually expose JVM differences. Include: minimum loop iteration count to reach JIT hot threshold,")
        appendLine("  required data types (primitives vs objects), allocation patterns, call depth, any specific JVM")
        appendLine("  optimization that must be active (escape analysis, inlining, boxing elimination, etc.).")
        appendLine("  Example: 'The boxing pattern must occur inside a loop with at least 50000 iterations so that")
        appendLine("  C2/Graal can reach tier-4 compilation and attempt EliminateAutoBox. Use Integer/Long wrappers")
        appendLine("  in a hot method. Avoid returning boxed values to prevent escape.'")
        appendLine("- All $TARGET_COUNT strategies MUST be different — different JVM aspects and patterns")
        appendLine("- If you genuinely cannot reach $TARGET_COUNT unique strategies, return as many as possible")
        appendLine("- Do NOT repeat the same idea with different wording")
        appendLine()
        appendLine("Bug summaries for context:")

        // Обрека суммарного контекста модели
        var chars = 0
        bugs.forEachIndexed { i, bug ->
            val line = "  ${i + 1}. [${bug.component}] ${bug.systemNumber}: ${bug.text.take(300).replace('\n', ' ')}"
            if (chars + line.length > MAX_BUG_SUMMARY_CHARS) {
                appendLine("  ... (${bugs.size - i} more bugs truncated)")
                return@forEachIndexed
            }
            appendLine(line)
            chars += line.length
        }
    }

    private fun parsePrompts(raw: String): List<MutationPrompt> {
        val json = extractJsonArray(raw)
        if (json.isBlank()) {
            println("[MutationPromptExtractor] Could not find JSON array in LLM response")
            return emptyList()
        }

        return try {
            val parsed = mapper.readValue<List<MutationPrompt>>(json)
            parsed
                .filter { it.name.isNotBlank() && it.instruction.isNotBlank() }
                .distinctBy { it.name }
                .take(TARGET_COUNT)
                .also { println("[MutationPromptExtractor] Parsed ${it.size} valid prompts") }
        } catch (e: Exception) {
            println("[MutationPromptExtractor] JSON parse error: ${e.message}")
            emptyList()
        }
    }

    /** Вырезает первый [...] блок из текста  */
    private fun extractJsonArray(text: String): String {
        // убрать ```json ... ```
        val fenced = Regex("```(?:json)?\\s*\\n?(\\[.*?\\])\\s*\\n?```", RegexOption.DOT_MATCHES_ALL)
        fenced.find(text)?.groupValues?.get(1)?.let { return it }
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        return if (start != -1 && end > start) text.substring(start, end + 1) else ""
    }

}
