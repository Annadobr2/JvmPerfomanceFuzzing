package llm.generate

import infrastructure.jvm.JvmExecutor
import java.io.File

/**
 * Валидатор сгенерированных Java-программ.
 *
 * Для каждой программы выполняет два шага:
 *  1. Компиляция через javax.tools во временную директорию.
 *  2. Запуск скомпилированного класса через [jvmExecutor].
 *
 * Используется в [JavaGenerationPipeline] для отсева невалидного кода перед
 * передачей в пул сидов, с возможностью до [MAX_ATTEMPTS] попыток перегенерации.
 */
class GeneratedProgramValidator(
    private val jvmExecutor: JvmExecutor,
    private val javaVersion: Int = 17,
) {
    private val compiler = JavaSourceCompiler(javaVersion)


    companion object {
        const val MAX_ATTEMPTS = 3
        private const val EXECUTION_TIMEOUT_SECONDS = 30L
    }

    /**
     * Результат валидации одной программы.
     *
     * @param success          true — компиляция и запуск прошли без ошибок
     * @param compilationErrors ошибки javac (пусто если компиляция успешна)
     * @param stdout           stdout процесса JVM
     * @param stderr           stderr процесса JVM (включает "timed out" если истёк таймаут)
     */
    data class ValidationResult(
        val success: Boolean,
        val compilationErrors: String,
        val stdout: String,
        val stderr: String,
    ) {
        /**
         * Лог ошибок для передачи в следующую попытку LLM-генерации.
         * Обрезается до 3000 символов
         */
        fun errorSummary(): String = buildString {
            if (compilationErrors.isNotBlank()) {
                appendLine("=== Compilation errors ===")
                appendLine(compilationErrors.take(2000))
            }
            if (stderr.isNotBlank()) {
                appendLine("=== Runtime stderr ===")
                appendLine(stderr.take(1000))
            }
            if (!success && compilationErrors.isBlank() && stderr.isBlank()) {
                appendLine("Program terminated with non-zero exit code and no output.")
            }
        }.trim().take(3000)
    }

    /**
     * Запуск компляции без запуска.
     */
    fun compileOnly(program: GeneratedProgram): ValidationResult {
        val tempDir = createTempDir()
        return try {
            val compilationErrors = compile(program, tempDir)
            if (compilationErrors.isNotBlank()) {
                println("[Validator] Compilation failed for '${program.className}':\n$compilationErrors")
                ValidationResult(
                    success = false,
                    compilationErrors = compilationErrors,
                    stdout = "",
                    stderr = "",
                )
            } else {
                println("[Validator] OK — '${program.className}' compiled successfully")
                ValidationResult(success = true, compilationErrors = "", stdout = "", stderr = "")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Компилирует и запускает [program].
     * Временная директория создаётся и удаляется внутри метода
     */
    fun validate(program: GeneratedProgram): ValidationResult {
        val tempDir = createTempDir()
        return try {
            val compilationErrors = compile(program, tempDir)

            if (compilationErrors.isNotBlank()) {
                println("[Validator] Attempt failed — compilation error for '${program.className}':\n$compilationErrors")
                return ValidationResult(
                    success = false,
                    compilationErrors = compilationErrors,
                    stdout = "",
                    stderr = "",
                )
            }

            val execResult = jvmExecutor.execute(
                classPathDirectory = tempDir,
                classPathString = tempDir.absolutePath,
                mainClass = program.className,
                mainArgs = emptyList(),
                executionTimeOut = EXECUTION_TIMEOUT_SECONDS,
            )

            val runtimeStderr = if (execResult.timedOut)
                "Program timed out after ${EXECUTION_TIMEOUT_SECONDS}s\n${execResult.stderr}"
            else
                execResult.stderr

            val success = execResult.exitCode == 0 && !execResult.timedOut

            if (!success) {
                println(
                    "[Validator] Attempt failed — runtime error for '${program.className}': " +
                    "exit=${execResult.exitCode}, timedOut=${execResult.timedOut}"
                )
                if (runtimeStderr.isNotBlank()) println("[Validator] stderr:\n${runtimeStderr.take(500)}")
            } else {
                println("[Validator] OK — '${program.className}' compiled and ran successfully")
            }

            ValidationResult(
                success = success,
                compilationErrors = "",
                stdout = execResult.stdout,
                stderr = runtimeStderr,
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun compile(program: GeneratedProgram, outputDir: File): String =
        compiler.compileToDir(program.className, program.code, outputDir)

    private fun createTempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "jvm_gen_${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }
}
