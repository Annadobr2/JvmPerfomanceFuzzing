package llm.generate

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import llm.client.LlmClient
import llm.parsing.BugsStructure

/**
 * Анализатор бага, отправляет запрос в LLM и получает ответ:
 * - флаг (feasible) - можно ли по багу написать Java-программу
 * - сжатую подсказку для генерации (condensedHint)
 */
class BugPromptAnalyzer(private val llmClient: LlmClient) {

    private val mapper = jacksonObjectMapper()

    fun analyze(bug: BugsStructure): PromptAnalysisResult {
        val prompt = buildAnalysisPrompt(bug)

        val raw = llmClient.generate(
            userPrompt = prompt,
            systemPrompt = SYSTEM_PROMPT,
        )

        return parseResponse(raw)
    }

    // ---- prompt ----
    private fun buildAnalysisPrompt(bug: BugsStructure): String {
        return buildString {
            appendLine("Analyze the following JDK bug report and decide whether a self-contained Java program can be written to reproduce or demonstrate its performance behavior.")
            appendLine()
            appendLine("Bug ID: ${bug.systemNumber}")
            appendLine("Type: ${bug.type}")
            appendLine("Status: ${bug.status}")
            appendLine("Component: ${bug.component}")
            if (bug.subcomponent.isNotBlank()) appendLine("Subcomponent: ${bug.subcomponent}")
            appendLine("Resolution: ${bug.resolution}")
            appendLine("Fix version: ${bug.fixVersion}")
            appendLine()
            appendLine("Description:")
            appendLine(bug.text.take(MAX_DESCRIPTION_CHARS))
            appendLine()
            appendLine("Respond strictly in this JSON format (no markdown, no backticks):")
            appendLine("""
                {
                  "feasible": true,
                  "reason": "one sentence why yes or no",
                  "condensedHint": "compact English description of what the Java program should do, max 5 sentences"
                }
            """.trimIndent())
            appendLine("If feasible is false, set condensedHint to null.")
        }
    }

    private fun parseResponse(raw: String): PromptAnalysisResult {
        // вырезать JSON
        val json = extractJson(raw)

        return try {
            val node = mapper.readTree(json)
            PromptAnalysisResult(
                feasible = node["feasible"]?.asBoolean() ?: false,
                reason = node["reason"]?.asText().orEmpty(),
                condensedHint = node["condensedHint"]?.takeIf { !it.isNull }?.asText(),
            )
        } catch (e: Exception) {
            // Модель не вернула валидный JSON
            PromptAnalysisResult(
                feasible = false,
                reason = "Failed to parse LLM response: ${e.message}",
                condensedHint = null,
            )
        }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start != -1 && end > start) text.substring(start, end + 1) else text
    }

    companion object {
        private const val MAX_DESCRIPTION_CHARS = 6_000

        private val SYSTEM_PROMPT = """
            You are a Java performance engineer. 
            You analyze JDK bug reports and assess whether a self-contained Java program 
            can reproduce or demonstrate the described performance issue.
            A program is feasible if:
            - it requires only JDK standard library (no native code, no OS-specific tuning)
            - it can run and terminate in under 60 seconds
            - the performance behavior is observable from pure Java
            - it does NOT require special JVM flags or command-line options to manifest the issue (must work with default JVM settings, or at least be meaningful without custom flags)
            - it is platform-independent: no OS-specific APIs, paths, commands, or behavior assumptions (must run identically on Linux, Windows, and macOS)
            Always respond with valid JSON only.
        """.trimIndent()
    }
}