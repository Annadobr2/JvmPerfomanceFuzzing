package llm.generate

import llm.parsing.BugsStructure
import java.io.File

/**
 * Двухшаговый пайплайн генерации Java-программ из JSON-описаний багов.
 *
 * Шаг 1 [BugPromptAnalyzer]         — сжатие описание и оценить feasibility
 * Шаг 2 [JavaProgramGenerator]      — генерация Java-код по подсказке
 * Шаг 3 [GeneratedProgramValidator] — попытка компиляции и запуска  (до [MAX_GENERATION_ATTEMPTS] попыток)
 *
 * Если [validator]  null — пропуск
 */
class JavaGenerationPipeline(
    private val analyzer: BugPromptAnalyzer,
    private val generator: JavaProgramGenerator,
    private val validator: GeneratedProgramValidator? = null,
    private val runAfterCompile: Boolean = true,
) {
    companion object {
        /** Максимальное число попыток генерации*/
        const val MAX_GENERATION_ATTEMPTS = GeneratedProgramValidator.MAX_ATTEMPTS
    }

    /**
     * Обрабатывает баг. Возвращает детализацию для [PipelineResult]
     * При наличии [validator] после каждой генерации выполняется компиляция и запуск.
     * Возникаемые ошибки передаются в следующую попытку генерации.
     * После [MAX_GENERATION_ATTEMPTS] неудачных попыток возвращается [PipelineStatus.VALIDATION_FAILED].
     */
    fun process(bug: BugsStructure): PipelineResult {
        // ── Шаг 1: анализ ──
        val analysis = try {
            analyzer.analyze(bug)
        } catch (e: Exception) {
            return PipelineResult.analysisError(bug.systemNumber, e.message.orEmpty())
        }

        if (!analysis.feasible) {
            return PipelineResult.skipped(bug.systemNumber, analysis.reason)
        }

        // ── Шаг 2+3: генерация  ──
        var lastError: String? = null

        repeat(MAX_GENERATION_ATTEMPTS) { attempt ->
            val attemptNumber = attempt + 1
            println("[Pipeline] Bug ${bug.systemNumber} — попытка $attemptNumber/$MAX_GENERATION_ATTEMPTS")

            // Генерация
            val program = try {
                generator.generate(bug, analysis.condensedHint!!, lastError)
            } catch (e: Exception) {
                println("[Pipeline] Attempt $attemptNumber: LLM call failed for ${bug.systemNumber} — ${e.message}")
                return PipelineResult.generationError(bug.systemNumber, analysis, e.message.orEmpty())
            }

            // Статическая проверка: структура кода
            if (!program.valid) {
                val missing = buildList {
                    if (!program.code.contains("class ${program.className}")) add("class must be named exactly '${program.className}'")
                    if (!program.code.contains("public static void main")) add("method 'public static void main(String[] args) throws Exception' is missing")
                    if (program.code.contains("```")) add("response contains markdown backticks — output raw Java only")
                }
                lastError = "Fix these issues in the next attempt: ${missing.joinToString("; ")}."
                println("[Pipeline] Attempt $attemptNumber: static validation failed — $lastError")
                return@repeat
            }

            if (validator == null) {
                return PipelineResult.success(bug.systemNumber, analysis, program)
            }

            // Компиляция
            val validation = if (runAfterCompile) validator.validate(program)
                             else validator.compileOnly(program)
            if (validation.success) {
                return PipelineResult.success(bug.systemNumber, analysis, program)
            }

            lastError = validation.errorSummary()
            println(
                "[Pipeline] Attempt $attemptNumber: validation failed for ${bug.systemNumber}. " +
                "Error passed to next attempt."
            )
        }

        // Все попытки исчерпаны
        println("[Pipeline] Bug ${bug.systemNumber} — all $MAX_GENERATION_ATTEMPTS attempts failed")
        return PipelineResult.validationFailed(
            id       = bug.systemNumber,
            analysis = analysis,
            msg      = "All $MAX_GENERATION_ATTEMPTS generation attempts failed.\n${lastError.orEmpty()}"
        )
    }

    /**
     * Обработка списка багов, сохранение Java-файлы в [outputDir].
     * Файлы с ошибками валидации попадают в `invalid/`.
     */
    fun processAndSave(bugs: List<BugsStructure>, outputDir: File): BatchReport {
        outputDir.mkdirs()
        val invalidDir = File(outputDir, "invalid").also { it.mkdirs() }

        val results = bugs.map { process(it) }

        results.forEach { result ->
            val program = result.program ?: return@forEach
            val dir = if (result.status == PipelineStatus.SUCCESS) outputDir else invalidDir
            File(dir, "${program.className}.java").writeText(program.code)
        }

        return BatchReport(
            total    = results.size,
            skipped  = results.count { it.status == PipelineStatus.SKIPPED },
            generated = results.count { it.status == PipelineStatus.SUCCESS },
            invalid  = results.count { it.status == PipelineStatus.VALIDATION_FAILED },
            errors   = results.count { it.status in listOf(PipelineStatus.ANALYSIS_ERROR, PipelineStatus.GENERATION_ERROR) },
            results  = results,
        )
    }
}

/**
 * Перечисление статусов генерации
 */
enum class PipelineStatus {
    SUCCESS,
    SKIPPED,
    ANALYSIS_ERROR,
    GENERATION_ERROR,
    VALIDATION_FAILED,
}

/**
 * Возвращает состояние пайплайна
 */
data class PipelineResult(
    val bugId: String,
    val status: PipelineStatus,
    val analysis: PromptAnalysisResult?,
    val program: GeneratedProgram?,
    val errorMessage: String?,
) {
    companion object {
        fun success(id: String, analysis: PromptAnalysisResult, program: GeneratedProgram) =
            PipelineResult(id, PipelineStatus.SUCCESS, analysis, program, null)

        fun skipped(id: String, reason: String) =
            PipelineResult(id, PipelineStatus.SKIPPED, null, null, reason)

        fun analysisError(id: String, msg: String) =
            PipelineResult(id, PipelineStatus.ANALYSIS_ERROR, null, null, msg)

        fun generationError(id: String, analysis: PromptAnalysisResult, msg: String) =
            PipelineResult(id, PipelineStatus.GENERATION_ERROR, analysis, null, msg)

        fun validationFailed(id: String, analysis: PromptAnalysisResult, msg: String) =
            PipelineResult(id, PipelineStatus.VALIDATION_FAILED, analysis, null, msg)
    }
}

/**
 * Отчет по сгенерированным программам
 */
data class BatchReport(
    val total: Int,
    val skipped: Int,
    val generated: Int,
    val invalid: Int,
    val errors: Int,
    val results: List<PipelineResult> = emptyList(),
) {
    override fun toString(): String = buildString {
        appendLine("=== Generation Report ===")
        appendLine("Total bugs processed   : $total")
        appendLine("Skipped (not feasible) : $skipped")
        appendLine("Generated (valid)      : $generated")
        appendLine("Validation failed      : $invalid")
        appendLine("Errors                 : $errors")
    }
}
