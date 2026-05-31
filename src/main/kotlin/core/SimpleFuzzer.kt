package core

import core.mutation.Mutator
import core.seed.BytecodeEntry
import core.seed.Seed
import infrastructure.jvm.JvmExecutor
import infrastructure.performance.PerformanceAnalyzer
import infrastructure.performance.PerformanceMeasurer
import infrastructure.performance.anomaly.AnomalyRepository
import java.io.File

class SimpleFuzzer(
    private val mutator: Mutator,
    private val performanceMeasurer: PerformanceMeasurer,
    private val performanceAnalyzer: PerformanceAnalyzer,
) {
    companion object {
        private val MUTATIONS_DIR = File("mutations")
    }

    fun fuzz(
        bytecode: ByteArray,
        jvmExecutors: List<JvmExecutor>,
        jvmOptions: List<String>,
        anomalyRepository: AnomalyRepository,
        className: String,
        packageName: String,
        maxMutations: Int = 1
    ) {
        var currentBytecode = bytecode
        var mutationCounter = 0

        println("Запуск простого фаззера для $className (макс. мутаций: $maxMutations)...")

        while (mutationCounter < maxMutations) {
            println("Мутация #${mutationCounter + 1}")

            // Мутация байткода
            val mutatedBytecode = mutator.mutate(currentBytecode, className, packageName)
            saveBytecodeToFile(mutatedBytecode, packageName, className)

            // Измерение производительности
            val metrics = measurePerformance(jvmExecutors, packageName, className, jvmOptions)

            // Анализ результатов
            val analysisResult = performanceAnalyzer.analyzeWithJIT(metrics)

            if (analysisResult.isAnomaliesInteresting) {
                handleAnomalies(
                    mutatedBytecode, className, packageName,
                    analysisResult.anomalies, analysisResult.interestingnessScore,
                    mutationCounter, anomalyRepository
                )
            } else {
                println("Аномалий не обнаружено.")
            }

            currentBytecode = mutatedBytecode
            mutationCounter++
        }

        println("Фаззинг завершен. Выполнено мутаций: $mutationCounter")
    }

    private fun saveBytecodeToFile(bytecode: ByteArray, packageName: String, className: String) {
        val packageDir = File(MUTATIONS_DIR, packageName)
        val outputFile = File(packageDir, "$className.class")
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(bytecode)
    }

    private fun measurePerformance(
        jvmExecutors: List<JvmExecutor>,
        packageName: String,
        className: String,
        jvmOptions: List<String>
    ) = performanceMeasurer.measureAll(
        executors = jvmExecutors,
        classpath = MUTATIONS_DIR,
        packageName = packageName,
        className = className,
        quickMeasurement = true,
        jvmOptionsProvider = { _ -> jvmOptions }
    )

    private fun handleAnomalies(
        bytecode: ByteArray,
        className: String,
        packageName: String,
        anomalies: List<infrastructure.performance.anomaly.PerformanceAnomalyGroup>,
        interestingness: Double,
        mutationCounter: Int,
        anomalyRepository: AnomalyRepository
    ) {
        // Создаем сид с аномалиями
        val seed = Seed(
            bytecodeEntry = BytecodeEntry(bytecode, className, packageName),
            javacode = "",
            mutationHistory = emptyList(),
            energy = 10,
            description = "simple_fuzzer_anomaly_$mutationCounter",
            interestingness = interestingness,
            anomalies = anomalies,
            verified = true
        )

        // Сохраняем сид в репозиторий
        anomalyRepository.saveSeedAnomalies(seed)

        println(
            "Найдены аномалии (${anomalies.size}) с интересностью $interestingness. " +
                    "Сохранены в репозитории как ${seed.description}."
        )
    }
}
