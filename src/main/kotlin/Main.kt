import core.EvolutionaryFuzzer
import core.mutation.AdaptiveMutator
import core.mutation.strategy.common.MutationStrategy
import core.seed.BytecodeEntry
import core.seed.EnergySeedManager
import core.seed.JavaCode
import infrastructure.bytecode.JavaByteCodeProvider
import infrastructure.jit.JITAnalyzer
import infrastructure.jvm.*
import infrastructure.launch.MutationStrategyType
import infrastructure.launch.mutationStrategyFromEnum
import infrastructure.performance.PerformanceAnalyzerImpl
import infrastructure.performance.PerformanceMeasurerImpl
import infrastructure.performance.anomaly.FileAnomalyRepository
import infrastructure.performance.verify.DetailedMeasurementAnomalyVerifier
import infrastructure.translator.JimpleTranslator
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import llm.client.*
import llm.generate.*
import llm.mutation.*
import llm.parsing.BugsStructure
import llm.parsing.OpenJdkPerformanceScraper
import llm.parsing.SingleFilePerBugExporter
import java.io.File
import java.io.PrintStream

fun main(args: Array<String>) {

    System.setOut(PrintStream(System.out, true, Charsets.UTF_8))
    System.setErr(PrintStream(System.err, true, Charsets.UTF_8))

    val parser = ArgParser("jvm-perf-fuzzer")

    val jvmKeys by parser.option(
        ArgType.String, shortName = "j", fullName = "jvms",
        description = "Список JVM через запятую: ${JvmType.entries.joinToString(",") { it.key }}"
    ).default("")

    val defaultSeedsDir = "src/test/resources/InitialSeedExamples"
    val seedsDir by parser.option(
        ArgType.String, shortName = "s", fullName = "seedsDir", description = "Путь к директории с сидами"
    ).default(defaultSeedsDir)

    val iterations by parser.option(
        ArgType.Int, shortName = "n", description = "Максимальное число итераций"
    ).default(100)

    val parseCount by parser.option(
        ArgType.Int, shortName = "k", description = "Количество для парсинга багов "
    ).default(50)

    val flagGenerateSeed by parser.option(
        ArgType.Boolean, shortName = "w", fullName = "generate-seed",
        description = "true = генерировать Java-программы из багов; false = использовать готовые сиды"
    ).default(false)

    val flagParseBugs by parser.option(
        ArgType.Boolean, shortName = "p", fullName = "parse-bugs",
        description = "true = скачать баги с Jira; false = читать из кэша data/openjdk_bugs"
    ).default(false)

    val flagGenerateStrategy by parser.option(
        ArgType.Boolean, shortName = "l", fullName = "generate-strategy",
        description = "true = генерировать промпты стратегий через LLM; false = читать из кэша data/mutation_prompts.json"
    ).default(false)


    val mutationStrategies by parser.option(
        ArgType.String,
        shortName = "m",
        fullName = "mutation-strategies",
        description = "Стратегии мутаций через запятую: ${MutationStrategyType.entries.joinToString(",") { it.name }}"
    ).default(MutationStrategyType.entries.joinToString(",") { it.name })

    val enableJitAnalysis by parser.option(
        ArgType.Boolean, fullName = "enable-jit-analysis", description = "Включить анализ JIT-логов"
    ).default(false)

    val anomalyDir by parser.option(
        ArgType.String, shortName = "a", fullName = "anomaliesDir", description = "Папка для сохранения аномалий"
    ).default("anomalies")

    parser.parse(args)

    println("==== Запуск JVM Performance Fuzzer ====")
    println("Используемые JVM: ${if (jvmKeys.isBlank()) "Дефолтные" else jvmKeys}")
    println("Директория сидов: $seedsDir")
    println("Максимальное число итераций: $iterations")
    println("Стратегии мутаций: $mutationStrategies")
    println("Директория аномалий: $anomalyDir")
    println("Анализ JIT-логов: $enableJitAnalysis")
    println("========================================")
    println("Запуск парсинга")

    // Настройки LLM из resources/setting.json
    val config = ConfigModel.load()
    val llmClientGenerator = LlmClientFactory.create(config)

    val configReader = JvmConfigReader()
    val javaVersion = configReader.getFirstConfiguredJavaVersion()

    println("========================================")
    println("Будет загружено $parseCount багов с ....")

    val scraper = OpenJdkPerformanceScraper()
    val cacheDir = File("data/openjdk_bugs")
    val bugs: List<BugsStructure> = if (flagParseBugs) {
        val fetched = scraper.searchPerformanceBugs(maxIssues = parseCount)
        // Сохранение в кэш
        val exporter = SingleFilePerBugExporter()
        fetched.forEach { exporter.exportBug(it, cacheDir) }
        println("[Scraper] Сохранено ${fetched.size} багов в ${cacheDir.path}")
        fetched
    } else {
        scraper.exportPerformanceBugsAsSeparateFiles(40, cacheDir)
    }

    println("${bugs.size} загружено")

    if (flagGenerateSeed) {
        println("========================================")
        println("Генерация java программ")

        // Выбор первой JVM для валидации сгенерированных программ
        val firstJvmExecutor: JvmExecutor = if (jvmKeys.isNotBlank()) {
            jvmKeys.split(",")
                .mapNotNull { JvmType.fromKey(it) }
                .firstOrNull()
                ?.let { jvmExecutorFromEnum(it, configReader) }
                ?: HotSpotJvmExecutor(configReader)
        } else {
            HotSpotJvmExecutor(configReader)
        }
        println("Валидатор генерации использует JVM: ${firstJvmExecutor::class.simpleName}")
        val programValidator = GeneratedProgramValidator(firstJvmExecutor, javaVersion)
        val analyzer = BugPromptAnalyzer(llmClientGenerator)
        val generator = JavaProgramGenerator(llmClientGenerator, PromptConstructor(), javaVersion)

        val pipeline = JavaGenerationPipeline(analyzer, generator, programValidator, runAfterCompile = false)

        val report = pipeline.processAndSave(
            bugs = bugs,
            outputDir = File("src/test/resources/GeneratedFromBugs"),
        )
        println(report)
    } else {
        println("========================================")
        println("Генерация java программ пропущена, будут использованы только переданные сиды")
    }

    val promptExtractor = MutationPromptExtractor(
        llmClient   = llmClientGenerator,
        promptsFile = File("data/mutation_prompts.json"),
    )
    val mutationPrompts: List<MutationPrompt> = if (flagGenerateStrategy) {
        promptExtractor.extract(bugs)      // генерация LLM, сохранение  в кэш
    } else {
        promptExtractor.loadOnly()         // загрузка из кэша;
    }
    println("Промпты для мутаций готовы: ${mutationPrompts.size}")
    println("========================================")

    val packageName = "benchmark"
    val classNames: List<String> = if (seedsDir != defaultSeedsDir) {
        File(seedsDir)
            .listFiles { file -> file.isFile && file.extension == "java" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            ?: error("Не удалось найти .java файлы в $seedsDir")
    } else {
        listOf(
            "ArrayManipulationTest",
            "BitOperations",
            "Boxing",
            "BubbleSort",
            "CollectionBenchmark",
            "CollectionsProcessor",
            "ConditionalExpressionTest",
            "ExceptionHandlingPatterns",
            "FloatingPointOperations",
            "LambdaAndStreams",
            "MathOperations",
            "MethodInliningTest",
            "MatrixMultiplier",
            "PrimeChecker",
            "StringProcessor",
            "SwitchPatternTest",
        )
    }

    // ------ JVM executors ------
    val jvmExecutors = if (jvmKeys.isNotBlank()) {
        jvmKeys
            .split(",")
            .mapNotNull { JvmType.fromKey(it) }
            .map { jvmExecutorFromEnum(it, configReader) }
    } else {
        listOf(
            HotSpotJvmExecutor(configReader),
            GraalVmExecutor(configReader),
            OpenJ9JvmExecutor(configReader)
        )
    }

    // ------ Seeds ------
    val initialPool = classNames.mapNotNull { className ->
        try {
            val byteCode = JavaByteCodeProvider("$seedsDir/$className.java").getBytecode()
            BytecodeEntry(byteCode, className, packageName)
        } catch (e: Exception) {
            println("[WARN] Пропускаем сид '$className': ${e.message}")
            null
        }
    }

    val javaCodeList: List<String> = JavaCode.load(seedsDir, classNames)

    // ------ Мутационные стратегии ------
    val jimpleTranslator = JimpleTranslator()
    val promptConstructor = PromptConstructor()
    val llmClient = OpenAiClient(config)

    val selectedStrategies: List<MutationStrategy> = mutationStrategies
        .split(",")
        .mapNotNull { name -> MutationStrategyType.entries.find { it.name == name.trim() } }
        .map { mutationStrategyFromEnum(it, jimpleTranslator) }

    // Статические стратегии — присутствуют всегда, независимо от mutation_prompts.json
    val staticLlmStrategies: List<MutationStrategyLLM> = listOf(
        RewriteCodeStrategy(promptConstructor, llmClient),
        ErasureStrategy(promptConstructor, llmClient),
    )

    val newSelectedStrategies: List<MutationStrategyLLM> = if (mutationPrompts.isNotEmpty()) {
        val dynamic = mutationPrompts.map { prompt ->
            DynamicMutationStrategyLLM(prompt, promptConstructor, llmClient)
        }
        (staticLlmStrategies + dynamic).also {
            println("LLM-стратегии: ${it.size} (${staticLlmStrategies.size} статических + ${dynamic.size} динамических)")
        }
    } else {
        println("mutation_prompts.json пуст — будут использвана только статические")
        staticLlmStrategies
    }

    val mutator = AdaptiveMutator(jimpleTranslator, selectedStrategies, newSelectedStrategies)

    val perfMeasurer = PerformanceMeasurerImpl()
    val perfAnalyzer = PerformanceAnalyzerImpl()
    val anomalyRepository = FileAnomalyRepository(anomalyDir)
    val jitLoggingOptionsProvider = if (enableJitAnalysis) JITLoggingOptionsProvider() else null
    val jitAnalyzer = if (enableJitAnalysis) JITAnalyzer(jitLoggingOptionsProvider!!) else null
    val anomalyVerifier = DetailedMeasurementAnomalyVerifier(perfMeasurer, perfAnalyzer, anomalyRepository, jitAnalyzer)

    val fuzzer = EvolutionaryFuzzer(
        mutator,
        perfMeasurer,
        perfAnalyzer,
        EnergySeedManager(),
        anomalyVerifier,
        jitLoggingOptionsProvider,
        maxIterations = iterations
    )

    fuzzer.fuzz(initialPool, javaCodeList, jvmExecutors)
}
