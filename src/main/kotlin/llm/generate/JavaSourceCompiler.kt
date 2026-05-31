package llm.generate

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.FileObject
import javax.tools.ForwardingJavaFileManager
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.StandardLocation
import javax.tools.ToolProvider

/**
 * Сервис компиляции Java-исходников через javax.tools.
 *
 * Инкапсулирует два режима компиляции, ранее дублировавшихся в
 * [llm.mutation.MutationStrategyLLM] и [GeneratedProgramValidator]:
 *  - [compileToDir]   — компиляция с записью .class-файлов в директорию на диске;
 *  - [compileToBytes] — компиляция в память с возвратом байткода.
 *
 * @param javaVersion целевая версия Java для флага `--release`
 */
class JavaSourceCompiler(val javaVersion: Int = 17) {

    /**
     * Компилирует [sourceCode] класса [className] в директорию [outputDir].
     *
     * @return строка с ошибками компиляции, либо пустая строка при успехе
     */
    fun compileToDir(className: String, sourceCode: String, outputDir: File): String {
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: return "System Java compiler not available (running on JRE-only runtime)"

        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val sourceObject = buildSourceFileObject(className, sourceCode)

        val fileManager = compiler.getStandardFileManager(diagnostics, null, null)
        fileManager.setLocation(StandardLocation.CLASS_OUTPUT, listOf(outputDir))

        val task = compiler.getTask(null, fileManager, diagnostics, releaseOptions(), null, listOf(sourceObject))
        val ok = task.call()
        fileManager.close()

        if (ok) return ""

        return diagnostics.diagnostics
            .filter { it.kind == Diagnostic.Kind.ERROR }
            .joinToString("\n") { d -> "  Line ${d.lineNumber}: ${d.getMessage(null)}" }
    }

    /**
     * Компилирует [sourceCode] класса [className] в память.
     * Если код не содержит объявления пакета — автоматически добавляется `package benchmark`.
     *
     * @return байткод скомпилированного класса, либо null при ошибке компиляции
     */
    fun compileToBytes(className: String, sourceCode: String): ByteArray? {
        val codeWithPackage = if (!sourceCode.trimStart().startsWith("package ")) {
            "package benchmark;\n$sourceCode"
        } else {
            sourceCode
        }

        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: run {
                println("[JavaSourceCompiler] system Java compiler not available (running on JRE-only?)")
                return null
            }

        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val outputs = mutableMapOf<String, ByteArrayOutputStream>()
        val sourceObject = buildSourceFileObject(className, codeWithPackage)

        val fileManager = object : ForwardingJavaFileManager<StandardJavaFileManager>(
            compiler.getStandardFileManager(diagnostics, null, null)
        ) {
            override fun getJavaFileForOutput(
                location: JavaFileManager.Location,
                outputClassName: String,
                kind: JavaFileObject.Kind,
                sibling: FileObject?,
            ): JavaFileObject {
                val stream = outputs.getOrPut(outputClassName) { ByteArrayOutputStream() }
                return object : SimpleJavaFileObject(URI.create("mem://$outputClassName${kind.extension}"), kind) {
                    override fun openOutputStream() = stream
                }
            }
        }

        val task = compiler.getTask(null, fileManager, diagnostics, releaseOptions(), null, listOf(sourceObject))
        val ok = task.call()

        if (!ok) {
            val errors = diagnostics.diagnostics.joinToString("\n") { d ->
                "  ${d.kind} at ${d.source?.name ?: "?"}:${d.lineNumber}: ${d.getMessage(null)}"
            }
            println("[JavaSourceCompiler] compileToBytes failed for '$className':\n$errors")
            return null
        }

        return outputs[className]?.toByteArray()
            ?: outputs.entries.firstOrNull { it.key.endsWith(".$className") || it.key == className }
                ?.value?.toByteArray()
            ?: run {
                println("[JavaSourceCompiler] no .class output found for '$className'. Got: ${outputs.keys}")
                null
            }
    }

    private fun releaseOptions() = listOf("--release", javaVersion.toString())

    private fun buildSourceFileObject(className: String, code: String): JavaFileObject =
        object : SimpleJavaFileObject(
            URI.create("string:///$className${JavaFileObject.Kind.SOURCE.extension}"),
            JavaFileObject.Kind.SOURCE,
        ) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = code
        }
}
