package llm.generate

import llm.client.LlmClient
import llm.parsing.BugsStructure

/**
 * Шаг 2 пайплайна.
 */
class JavaProgramGenerator(
    private val llmClient: LlmClient,
    private val promptConstructor: PromptConstructor,
    private val javaVersion: Int = 17,
) {

    /**
     * Генерация Java-программы для одного бага.
     *
     * @param bug           описание JDK-бага
     * @param condensedHint сжатая подсказка
     * @param errorContext  лог ошибок предыдущей попытки (null для первой попытки).
     */
    fun generate(bug: BugsStructure, condensedHint: String, errorContext: String? = null): GeneratedProgram {
        val className = toSafeClassName(bug.systemNumber)

        var prompt : String

        if (errorContext.isNullOrEmpty()){
            prompt = promptConstructor.build(
                role = role,
                instruction = buildInstruction(bug, condensedHint, className),
                programContext = buildProgramContext(condensedHint, null),
                outputRules = outputRules(className),
            )

        }
        else {
            prompt = promptConstructor.build(
                role = role,
                instruction = buildInstruction(bug, condensedHint, className),
                programContext = buildProgramContext(condensedHint, errorContext),
                outputRules = outputRules(className),
            )

        }


        val raw = llmClient.generate(userPrompt = prompt, systemPrompt = null)
        val extracted = extractJavaCode(raw)
        val code = fixClassName(extracted, className)

        return GeneratedProgram(
            bugId = bug.systemNumber,
            className = className,
            code = code,
            valid = isValidJava(code, className),
        )
    }

    private fun buildInstruction(bug: BugsStructure, hint: String, className: String): String = buildString {
        appendLine("Write a SHORT (under 80 lines) self-contained Java program for this JDK performance issue:")
        appendLine("Bug: ${bug.systemNumber} | Component: ${bug.component}")
        appendLine()
        appendLine("What to implement: $hint")
        appendLine()
        appendLine("CRITICAL REQUIREMENTS (violation = failure):")
        appendLine("1. Class name MUST be exactly: $className")
        appendLine("2. MUST have: public static void main(String[] args) throws Exception")
        appendLine("3. MUST have closing braces — do NOT let the code get truncated")
    }

    /**
     * Сборка PROGRAM CONTEXT.
     * Если передан [errorContext] — дополняется подсказкой
     */
    private fun buildProgramContext(condensedHint: String, errorContext: String?): String =
        if (errorContext.isNullOrBlank()) {
            condensedHint
        } else {
            buildString {
                appendLine(condensedHint)
                appendLine()
                appendLine("### PREVIOUS ERRORS (fix these in the new version)")
                appendLine("The previous attempt produced the following errors. Fix ALL of them:")
                appendLine(errorContext)
            }
        }

    /**
     * Нормализация ответа от модели
     */
    private fun extractJavaCode(raw: String): String {
        val fenceRegex = Regex("```[\\w]*\\s*\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
        val match = fenceRegex.find(raw)
        return match?.groupValues?.get(1)?.trim() ?: raw.trim()
    }

    /**
     * Исправление имени класса (если оно было изменено)
     */
    private fun fixClassName(code: String, className: String): String {
        if (code.contains("class $className")) return code
        val actualName = Regex("""public\s+class\s+(\w+)""").find(code)?.groupValues?.get(1)
            ?: return code
        if (actualName == className) return code
        return code.replace(Regex("""\b${Regex.escape(actualName)}\b"""), className)
    }

    private fun isValidJava(code: String, className: String): Boolean =
        code.isNotBlank() &&
                code.contains("class $className") &&
                code.contains("public static void main") &&
                !code.contains("```")

    /**
     * Возвращает валидное  Java-имя
     * Любые не-буквенно-цифровые символы заменяются на `_`.
     */
    private fun toSafeClassName(bugId: String): String {
        val sanitized = bugId.replace(Regex("[^A-Za-z0-9]"), "_")
        return if (sanitized.firstOrNull()?.isLetter() == true) sanitized else "Bug_$sanitized"
    }

    private fun outputRules(className: String) = """
        HARD CONSTRAINTS:
        - Output ONLY raw Java code — NO markdown, NO backticks, NO explanations.
        - Keep the program UNDER 80 LINES — shorter code avoids truncation.
        - Do NOT declare a package statement.
        - Class name: $className (exactly, case-sensitive).
        - Use only JDK standard library.
        - Must terminate within 60 seconds.
        - MUST end with proper closing braces. Truncated code = failure.

        MANDATORY SKELETON (follow exactly):

        public class $className {
            public $className() {}
            public static void main(String[] args) throws Exception {
                // your code here
            }
        }
    """.trimIndent()

    private val role: String = """
        You are a compiler-aware Java performance engineer.
        You write complete, self-contained Java programs that compile with Java $javaVersion.
    """.trimIndent()
}

/**
 * Результат генерации для одного бага.
 */
data class GeneratedProgram(
    val bugId: String,
    val className: String,
    val code: String,
    val valid: Boolean,
)