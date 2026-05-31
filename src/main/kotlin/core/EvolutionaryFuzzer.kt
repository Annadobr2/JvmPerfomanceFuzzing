package core

import core.mutation.AdaptiveMutator
import core.mutation.Mutator
import core.seed.BytecodeEntry
import core.seed.JavaCode
import core.seed.Seed
import core.seed.SeedManager
import infrastructure.bytecode.JavaByteCodeProvider
import infrastructure.jvm.JITLoggingOptionsProvider
import infrastructure.jvm.JvmExecutor
import infrastructure.performance.PerformanceAnalyzer
import infrastructure.performance.PerformanceMeasurer
import infrastructure.performance.anomaly.AnomalyGroupType
import infrastructure.performance.entity.ExtendedAnalysisResult
import infrastructure.performance.entity.PerformanceMetrics
import infrastructure.performance.entity.SignificanceLevel
import infrastructure.performance.verify.AnomalyVerifier
import infrastructure.performance.verify.VerificationPurpose
import java.io.File
import kotlin.random.Random

/**
 * Основной движок эволюционного фаззинга, координирующий процесс мутации,
 * измерения и анализа производительности для выявления аномалий между JVM.
 */
class EvolutionaryFuzzer(
    private val mutator: Mutator,
    private val performanceMeasurer: PerformanceMeasurer,
    private val performanceAnalyzer: PerformanceAnalyzer,
    private val seedManager: SeedManager,
    private val anomalyVerifier: AnomalyVerifier,
    private val jitOptionsProvider: JITLoggingOptionsProvider? = null,
    private val maxIterations: Int = 100_000,
    private val stagnationThreshold: Int = 1000,
    private val initialEnergy: Int = 10,
    // Вероятность выбора LLM-пути мутации: 5/(5+3) ≈ 0.625
    // Остаток (3/8) идёт на классический байткод-путь (Jimple/ASM)
    private val llmMutationProbability: Double = 5.0 / 8.0
) : Fuzzer {

    override fun fuzz(initialEntries: List<BytecodeEntry>, javaCodeList: List<String>, jvmExecutors: List<JvmExecutor>, jvmOptions: List<String>) {


        val initialSeeds = createInitialSeeds(initialEntries, javaCodeList)
        val addedCount = seedManager.addInitialSeeds(initialSeeds)

        var iterations = 0
        var iterationsWithoutNewSeeds = 0
        var totalAnomaliesFound = 0

        println("Начинаем фаззинг с ${jvmExecutors.size} JVM и $addedCount начальными сидами...")

        while (iterations < maxIterations && iterationsWithoutNewSeeds < stagnationThreshold) {
            anomalyVerifier.onNewIteration()
            //
            if (anomalyVerifier.shouldPerformDetailedCheck()) {
                val confirmedCount = anomalyVerifier.performDetailedCheck(jvmExecutors, jvmOptions)
                totalAnomaliesFound += confirmedCount
            }

            val selectedSeed = seedManager.selectSeedForMutation() ?: break
            println("Итерация #${iterations + 1} | Используем сид: ${selectedSeed.description} | " +
                    "Энергия: ${selectedSeed.energy} | Интересность: ${selectedSeed.interestingness}")

            // Мутация байткода
            val (mutatedBytecode, wasMutated, mutatedJavaCode) = mutateBytecode(selectedSeed)
            if (!wasMutated) {
                iterations++
                continue
            }
            seedManager.decrementSeedEnergy(selectedSeed)

            // Измерение производительности
            val bytecodeEntry = selectedSeed.bytecodeEntry
            val metrics = measurePerformance(
                bytecodeEntry.className,
                bytecodeEntry.packageName,
                jvmExecutors,
                jvmOptions
            )

            // Анализ результатов
            val analysisResult = performanceAnalyzer.analyzeWithJIT(metrics)

            if (analysisResult.isAnomaliesInteresting) {
                // Обработка найденных аномалий
                val newSeed = createSeedFromMutation(
                    selectedSeed,
                    mutatedJavaCode,
                    mutatedBytecode,
                    bytecodeEntry.className,
                    bytecodeEntry.packageName,
                    analysisResult,
                    iterations
                )

                if (processSeed(newSeed, analysisResult, jvmExecutors, jvmOptions)) {
                    iterationsWithoutNewSeeds = 0
                } else {
                    iterationsWithoutNewSeeds++
                }
            } else {
                println("Аномалий не обнаружено")
                iterationsWithoutNewSeeds++
            }

            iterations++
        }

        println("Фаззинг завершен. Всего итераций: $iterations, найдено аномалий: $totalAnomaliesFound")
        println("Финальный размер пула сидов: ${seedManager.getSeedCount()}")
    }

    private fun createInitialSeeds(
        entries: List<BytecodeEntry>,
        javaCodeList: List<String>
        ): List<Seed> =
        entries.zip(javaCodeList).mapIndexed { index, (entry, code) ->
            Seed(
                bytecodeEntry = entry,
                javacode = code,
                mutationHistory = mutableListOf(),
                energy = initialEnergy,
                description = entry.description.ifEmpty { "initial_$index" },
                initial = true
            )
        }

    private fun mutateBytecode(seed: Seed): Triple<ByteArray, Boolean, String> {

        val originalBytecode = seed.bytecodeEntry.bytecode
        val originalJavaCode = seed.javacode
        val className = seed.bytecodeEntry.className
        val packageName = seed.bytecodeEntry.packageName

        val mutatedBytecode: ByteArray
        val mutatedJavaCode: String

        if (Random.nextDouble() < llmMutationProbability) {
            // LLM-путь (5/8): стратегия генерирует Java-исходник и компилирует его в байткод.
            // null байткод → модель вернула невалидный код или javac упал — считаем итерацию пустой.
            println("Мутация через LLM")
            val (newBytecode, newJavaCode) = mutator.mutate(originalJavaCode, className, packageName)
            mutatedBytecode = newBytecode ?: originalBytecode
            mutatedJavaCode = newJavaCode
        } else {
            // Байткод-путь (3/8): классические Jimple/ASM стратегии.
            println("Мутация через Bytecode (Jimple/ASM)")
            mutatedBytecode = mutator.mutate(originalBytecode, className, packageName)
            mutatedJavaCode = originalJavaCode
        }

        val wasMutated = !mutatedBytecode.contentEquals(originalBytecode)

        if (!wasMutated) {
            println("Мутация не произвела изменений, пропускаем")
        } else {
            saveBytecode(mutatedBytecode, packageName, className)
        }

        return Triple(mutatedBytecode, wasMutated, mutatedJavaCode)
    }

    private fun saveBytecode(bytecode: ByteArray, packageName: String, className: String) {
        val classpath = File("mutations")
        val packageDir = File(classpath, packageName)
        val outputFile = File(packageDir, "$className.class")

        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(bytecode)
    }

    private fun measurePerformance(
        className: String,
        packageName: String,
        jvmExecutors: List<JvmExecutor>,
        jvmOptions: List<String>
    ): List<Pair<JvmExecutor, PerformanceMetrics>> = performanceMeasurer.measureAll(
        executors = jvmExecutors,
        classpath = File("mutations"),
        packageName = packageName,
        className = className,
        quickMeasurement = true,
        jvmOptionsProvider = { executor ->
            jitOptionsProvider?.let {
                jvmOptions + it.getJITLoggingOptions(executor)
            } ?: jvmOptions
        }
    )

    private fun createSeedFromMutation(
        parentSeed: Seed,
        mutatedJavaCode: String,
        mutatedBytecode: ByteArray,
        className: String,
        packageName: String,
        analysisResult: ExtendedAnalysisResult,
        iteration: Int
    ): Seed {
        val mutationRecord = (mutator as? AdaptiveMutator)?.getLastMutationRecord()?.copy(
            parentSeedDescription = parentSeed.description
        )

        val newMutationHistory = if (mutationRecord != null) {
            parentSeed.mutationHistory + mutationRecord
        } else {
            parentSeed.mutationHistory
        }

        return Seed(
            bytecodeEntry = BytecodeEntry(mutatedBytecode, className, packageName),
            javacode = mutatedJavaCode,
            mutationHistory = newMutationHistory,
            anomalies = analysisResult.anomalies,
            iteration = iteration,
            interestingness = analysisResult.interestingnessScore
        )
    }

    private fun processSeed(
        seed: Seed,
        analysisResult: ExtendedAnalysisResult,
        jvmExecutors: List<JvmExecutor>,
        jvmOptions: List<String>
    ): Boolean {
        if (!seedManager.addSeed(seed)) {
            println("Найдены аномалии, но сид не был добавлен (возможно дубликат)")
            (mutator as? AdaptiveMutator)?.notifySeedRejected()
            return false
        }

        logAnomaliesFound(seed, analysisResult)
        anomalyVerifier.addSeedForVerification(seed)
        (mutator as? AdaptiveMutator)?.notifyNewSeedGenerated()

        if (analysisResult.significanceLevel == SignificanceLevel.REPORTING) {
            anomalyVerifier.confirmAnomaliesAndUpdateSeed(
                seed, jvmExecutors, jvmOptions, VerificationPurpose.REPORTING
            )
        }

        return true
    }

    private fun logAnomaliesFound(
        seed: Seed,
        analysisResult: ExtendedAnalysisResult
    ) {
        val anomalies = seed.anomalies
        val jitAnomaliesCount = anomalies.count { it.anomalyType == AnomalyGroupType.JIT }
        val performanceAnomaliesCount = anomalies.size - jitAnomaliesCount

        val hasPerformanceAnomalies = performanceAnomaliesCount > 0
        val hasJitAnomalies = jitAnomaliesCount > 0

        val perfInfo = if (hasPerformanceAnomalies) {
            val timeAnomalies = anomalies.count { it.anomalyType == AnomalyGroupType.TIME }
            val memoryAnomalies = anomalies.count { it.anomalyType == AnomalyGroupType.MEMORY }
            val timeoutAnomalies = anomalies.count { it.anomalyType == AnomalyGroupType.TIMEOUT }
            val errorAnomalies = anomalies.count { it.anomalyType == AnomalyGroupType.ERROR }

            "производительность (всего: $performanceAnomaliesCount, " +
                    "время: $timeAnomalies, память: $memoryAnomalies, " +
                    "таймауты: $timeoutAnomalies, ошибки: $errorAnomalies)"
        } else ""

        val jitInfo = if (hasJitAnomalies) "JIT-аномалии: $jitAnomaliesCount" else ""
        val separator = if (hasPerformanceAnomalies && hasJitAnomalies) ", " else ""
        val levelInfo = "уровень: ${analysisResult.significanceLevel}"

        println(
            "Найдены аномалии: $perfInfo$separator$jitInfo ($levelInfo)! " +
                    "Добавлен новый сид: ${seed.description} с энергией ${seed.energy} " +
                    "и интересностью ${seed.interestingness}"
        )
    }
}
