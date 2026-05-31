package core

import core.seed.BytecodeEntry
import infrastructure.jvm.JvmExecutor

interface Fuzzer {
    /**
     * Запускает весь цикл мутаций, начиная с генерации байт-кода и заканчивая сохранением аномалий.
     *
     * @param initialEntries Исходный список байт-кодов для мутаций.
     * @param jvmExecutors Список JVM, на которых будет тестироваться байт-код.
     * @param jvmOptions Параметры JVM для тестирования.
     */
    fun fuzz(
        initialEntries: List<BytecodeEntry>,
        javaCodeList: List<String>,
        jvmExecutors: List<JvmExecutor>,
        jvmOptions: List<String> = emptyList()
    )

}
