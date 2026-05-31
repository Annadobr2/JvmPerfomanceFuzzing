package core.mutation.strategy.common

import infrastructure.translator.JimpleTranslator
import org.objectweb.asm.ClassReader
import org.objectweb.asm.util.CheckClassAdapter
import soot.Body
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Базовый класс для всех стратегий мутации байткода.
 * Обеспечивает шаблонный метод для применения мутаций, выбор метода для мутации и проверку валидности.
 */
abstract class MutationStrategy(private val jimpleTranslator: JimpleTranslator) : CommonMutationStrategy {

    /**
     * Применяет стратегию мутации к Jimple-коду.
     */

    fun apply(jimpleCode: String, className: String, packageName: String): MutationResult {
        return try {
            val sootClass = jimpleTranslator.parseSootClass(jimpleCode, className, packageName)

            val method = sootClass.methods
                .filter { it.hasActiveBody() && !it.name.startsWith("<") }
                .randomOrNull() ?: return MutationResult(jimpleCode, true)

            applyMutation(method.activeBody)

            val bytecode = jimpleTranslator.toBytecode(sootClass)
            if (!validateBytecode(bytecode)) {
                return MutationResult(jimpleCode, true)
            }

            MutationResult(
                jimpleTranslator.toJimple(bytecode, sootClass.shortName).data,
                false
            )
        } catch (e: Exception) {
            MutationResult(jimpleCode, true)
        }
    }

    /**
     * Реализуется в конкретных стратегиях для применения специфичных мутаций.
     */
    protected abstract fun applyMutation(body: Body)

    private fun validateBytecode(bytecode: ByteArray): Boolean {
        val errorCollector = StringWriter()
        CheckClassAdapter.verify(
            ClassReader(bytecode),
            false,
            PrintWriter(errorCollector)
        )
        return errorCollector.toString().isEmpty()
    }
}