package llm.mutation

import core.mutation.strategy.common.CommonMutationStrategy
import core.mutation.strategy.common.MutationResult
import llm.client.LlmClient
import llm.generate.JavaSourceCompiler
import llm.generate.PromptConstructor

/**
 * Базовый класс для LLM-мутационных стратегий.
 *
 * Подкласс задаёт [instructionPrompt]
 * [apply] Применяет мутацию
 * Для всех стратегий заданы жёсткие правила:
 *  - результат — единый Java-файл, без markdown,
 *  - класс публичный, имя совпадает с [className],
 *  - программа в пакете [packageName],
 *  - есть `public static void main`.
 */
abstract class MutationStrategyLLM(
    val promptConstructor: PromptConstructor,
    val llmClient: LlmClient,
) : CommonMutationStrategy {

    private val javaCompiler = JavaSourceCompiler()


    /** Роль */
    protected open val rolePrompt: String = """
        You are a compiler-aware Java code mutator focused on JVM stress testing.
        You receive an existing Java program and modify it according to the given instruction.
        The result must be a single-file, self-contained Java program that compiles and runs on Java 17.
    """.trimIndent()

    /** Инструкция */
    protected abstract val instructionPrompt: String

    /**
     * Уникальный идентификатор стратегии для трекинга статистики в [AdaptiveMutator].
     * По умолчанию — простое имя класса. [DynamicMutationStrategyLLM] переопределяет
     * это на имя из [MutationPrompt]
     */
    open fun strategyId(): String = this::class.simpleName ?: "UnknownLLMStrategy"

    /** Правила вывода */
    protected open fun outputRulesPrompt(className: String, packageName: String): String = """
        Hard constraints:
        - Output ONLY raw Java code — no markdown, no backticks, no explanations, no prose.
        - Keep the program under 120 lines total — do NOT generate long programs, truncated code will NOT compile.
        - REQUIRED — the class MUST be named exactly: $className
        - REQUIRED — the class MUST contain: public static void main(String[] args) throws Exception
        - REQUIRED — the class MUST have a no-arg constructor: public $className() {}
        - Use only the JDK standard library (no external dependencies).
        - Static nested classes are allowed; anonymous classes are NOT allowed.
        - The program MUST terminate within 10 seconds on any input.

        The output MUST follow this exact structure (do not deviate):

        package $packageName;

        public class $className {

            public $className() {}

            public static void main(String[] args) throws Exception {
                // your code here
            }
        }
    """.trimIndent()

    /**
     * Шаблонный метод. Подкласс задаёт [instructionPrompt]
     *
     * @param javaCode исходный код родительского сида
     */
    open fun apply(javaCode: String, className: String, packageName: String): MutationResult {
        return try {
            val prompt = promptConstructor.build(
                role = rolePrompt,
                instruction = instructionPrompt,
                programContext = "=== ORIGINAL JAVA CODE TO MODIFY ===\n$javaCode",
                outputRules = outputRulesPrompt(className, packageName)
            )

            val raw = llmClient.generate(userPrompt = prompt, systemPrompt = null)
            val cleaned = stripFences(raw)
            val fixed = fixClassName(cleaned, className)

            if (isValidJava(fixed, className)) {
                MutationResult(fixed, hadError = false)
            } else {
                println("${this::class.simpleName}: invalid LLM output, keeping original javaCode")
                MutationResult(javaCode, hadError = true)
            }
        } catch (e: Exception) {
            println("${this::class.simpleName} failed: ${e.message}")
            MutationResult(javaCode, hadError = true)
        }
    }

    /**
     * Проверка и исправление [className] в java-программе
     */
    protected fun fixClassName(code: String, className: String): String {
        if (code.contains("class $className")) return code  // уже правильное имя

        val actualName = Regex("""public\s+class\s+(\w+)""").find(code)?.groupValues?.get(1)
            ?: return code

        if (actualName == className) return code

        println("  [fixClassName] переименовываем '$actualName' → '$className'")
        return code.replace(Regex("""\b${Regex.escape(actualName)}\b"""), className)
    }

    /** Обрезка обертки */
    protected fun stripFences(raw: String): String {
        val fence = Regex("```[\\w]*\\s*\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
        val match = fence.find(raw)
        return (match?.groupValues?.get(1) ?: raw).trim()
    }

    protected fun isValidJava(code: String, className: String): Boolean {
        val valid = code.isNotBlank() &&
                code.contains("class $className") &&
                code.contains("public static void main") &&
                !code.contains("```")
        if (!valid) {
            val reasons = buildList {
                if (code.isBlank()) add("пустой код")
                if (!code.contains("class $className")) add("нет 'class $className'")
                if (!code.contains("public static void main")) add("нет main()")
                if (code.contains("```")) add("остались бэктики")
            }
            println("  [invalid LLM output] причина: ${reasons.joinToString(", ")}")
        }
        return valid
    }

    /**
     * Компилирует Java-исходник в память и возвращает байткод класса [className].
     * Делегирует в [JavaSourceCompiler.compileToBytes].
     */
    fun compileJavaCode(className: String, code: String): ByteArray? =
        javaCompiler.compileToBytes(className, code)
}