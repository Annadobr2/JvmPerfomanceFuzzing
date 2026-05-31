package infrastructure.performance

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import infrastructure.jvm.JvmExecutor
import infrastructure.jvm.entity.JvmExecutionResult
import infrastructure.performance.entity.JmhOptions
import infrastructure.performance.entity.PerformanceMetrics
import java.io.File
import javax.tools.ToolProvider

/**
 * Реализация измерителя производительности на базе JMH.
 * Генерирует и компилирует JMH-бенчмарки, запускает их на разных JVM.
 */
class PerformanceMeasurerImpl : PerformanceMeasurer {

    companion object {
        const val JMH_VERSION = "1.37"
        const val BENCHMARK_CLASS_NAME = "BenchmarkRunner"
    }

    override fun measureAll(
        executors: List<JvmExecutor>,
        classpath: File,
        packageName: String,
        className: String,
        quickMeasurement: Boolean,
        jvmOptionsProvider: (JvmExecutor) -> List<String>
    ): List<Pair<JvmExecutor, PerformanceMetrics>> {
        validateClassPath(classpath)

        val jmhConfig = if (quickMeasurement) JmhOptions() else JmhOptions(2, 10, 4, 15)
        return measureAll(executors, classpath, packageName, className, jmhConfig, jvmOptionsProvider)
    }

    override fun measureAll(
        executors: List<JvmExecutor>,
        classpath: File,
        packageName: String,
        className: String,
        jmhOptions: JmhOptions,
        jvmOptionsProvider: (JvmExecutor) -> List<String>
    ): List<Pair<JvmExecutor, PerformanceMetrics>> {
        validateClassPath(classpath)

        val benchmarkFile = prepareBenchmarkFile(classpath, packageName, className, jmhOptions)
        val jmhClasspath = getJmhClasspath()

        return executors.map { executor ->
            val jvmOptions = jvmOptionsProvider(executor)
            val jvmName = executor::class.simpleName ?: "unknown"

            val fullClasspath = "${classpath.absolutePath}${File.pathSeparator}$jmhClasspath"
            val relativeBenchmarkPath = "$packageName.$BENCHMARK_CLASS_NAME"

            val jmhResult = executor.execute(
                classpath,
                fullClasspath,
                relativeBenchmarkPath,
                emptyList(),
                jmhOptions.executionTimeout,
                jvmOptions
            )

            executor to parseJMHResults(jmhResult, benchmarkFile.resultFile, jvmName)
        }
    }

    private fun validateClassPath(classpath: File) {
        if (!classpath.exists() || !classpath.isDirectory) {
            throw IllegalArgumentException("Указанный classpath '${classpath.absolutePath}' не существует или не является директорией")
        }
    }

    private data class BenchmarkFiles(val sourceFile: File, val resultFile: File)

    private fun prepareBenchmarkFile(
        classpath: File,
        packageName: String,
        className: String,
        jmhOptions: JmhOptions
    ): BenchmarkFiles {
        val benchmarkDir = File(classpath, packageName)
        val benchmarkFile = File(benchmarkDir, "$BENCHMARK_CLASS_NAME.java")

        val jsonResultFolder = File(classpath, "results").apply {
            if (!exists()) mkdirs()
        }

        val jsonResultFileName = "$className-${System.currentTimeMillis()}-result.json"
        val jsonResultFile = File(jsonResultFolder, jsonResultFileName)

        benchmarkFile.writeText(generateJMHBenchmark(jmhOptions, className, jsonResultFile.absolutePath, packageName))
        compileBenchmarkClass(classpath, benchmarkFile)

        return BenchmarkFiles(benchmarkFile, jsonResultFile)
    }

    private fun compileBenchmarkClass(classpath: File, benchmarkFile: File) {
        val compiler = ToolProvider.getSystemJavaCompiler()
        requireNotNull(compiler) { "Java compiler not available. Ensure JDK is installed." }

        val fileManager = compiler.getStandardFileManager(null, null, null)

        fileManager.use { manager ->
            val compilationUnits = manager.getJavaFileObjectsFromFiles(listOf(benchmarkFile))
            val compileClasspath = "${classpath.absolutePath}${File.pathSeparator}${getJmhClasspath()}"
            val options = listOf("-d", classpath.absolutePath, "-cp", compileClasspath, "--release", "17")

            val task = compiler.getTask(null, manager, null, options, null, compilationUnits)

            if (!task.call()) {
                throw RuntimeException("Ошибка компиляции JMH-бенчмарка")
            }
        }
    }

    private fun getJmhClasspath(): String {
        val gradleCache = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1")
        if (!gradleCache.exists()) throw RuntimeException("Gradle cache не найден, возможно JMH не установлен")

        val jmhDependencies = listOf(
            "jmh-core-$JMH_VERSION.jar",
            "jmh-generator-annprocess-$JMH_VERSION.jar",
            "jopt-simple-5.0.4.jar",
            "commons-math3-3.6.1.jar"
        )

        val jmhJars = gradleCache.walk()
            .filter { it.isFile && jmhDependencies.any { dep -> it.name.contains(dep) } }
            .map { it.absolutePath }
            .toList()

        if (jmhJars.size != jmhDependencies.size) {
            throw RuntimeException("Не найдены JMH JAR'ы версии $JMH_VERSION в Gradle кэше. " +
                    "Не найдены: ${jmhDependencies.filter { jmhDep -> jmhJars.none { it.contains(jmhDep) } }}")
        }

        return jmhJars.joinToString(File.pathSeparator)
    }

    private fun generateJMHBenchmark(
        jmhOptions: JmhOptions,
        className: String,
        resultFileName: String,
        packageName: String
    ): String {
        return """
        package ${packageName};

        import org.openjdk.jmh.annotations.*;
        import org.openjdk.jmh.results.format.ResultFormatType;
        import org.openjdk.jmh.runner.Runner;
        import org.openjdk.jmh.runner.RunnerException;
        import org.openjdk.jmh.runner.options.Options;
        import org.openjdk.jmh.runner.options.OptionsBuilder;
        import org.openjdk.jmh.runner.options.TimeValue;
        
        import java.util.concurrent.TimeUnit;

        @BenchmarkMode(Mode.AverageTime)
        @OutputTimeUnit(TimeUnit.MILLISECONDS)
        @State(Scope.Thread)
        public class $BENCHMARK_CLASS_NAME {

            private $className instance = new $className();
            
            private long totalMemoryUsedInKB = 0;
            private int iterations = 0;

            @Benchmark
            public void benchmarkMethod() throws Exception {
                instance.main(null);
            }

            @Setup(Level.Invocation)
            public void setup() {
                System.gc();
            }

            @TearDown(Level.Invocation)
            public void tearDown() {
                long usedMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                totalMemoryUsedInKB += (usedMemory >> 10);
                iterations++;
            }
            
            @TearDown(Level.Trial)
            public void printAverageMemoryUsage() {
                long avgMemoryUsed = totalMemoryUsedInKB / iterations;
                System.out.println("AVERAGE_MEMORY_USAGE_KB: " + avgMemoryUsed);
            }

            public static void main(String[] args) throws RunnerException {
                Options opt = new OptionsBuilder()
                    .include($BENCHMARK_CLASS_NAME.class.getSimpleName())
                    .warmupIterations(${jmhOptions.warmupIterations})
                    .warmupTime(TimeValue.seconds(${jmhOptions.warmupTimeSeconds}))
                    .measurementIterations(${jmhOptions.measurementIterations})
                    .measurementTime(TimeValue.seconds(${jmhOptions.measurementTimeSeconds}))
                    .forks(${jmhOptions.forks})
                    .resultFormat(ResultFormatType.JSON)
                    .result("${resultFileName.replace("\\", "/")}")
                    .build();

                new Runner(opt).run();
            }
        }
    """.trimIndent()
    }

    private fun parseJMHResults(
        jmhResult: JvmExecutionResult,
        jsonResultFile: File,
        jvmName: String
    ): PerformanceMetrics {
        if (jmhResult.timedOut) {
            return createTimedOutMetrics(jmhResult)
        }

        return try {
            if (!jsonResultFile.exists() || jsonResultFile.length() == 0L) {
                println("[JMH ERROR] [$jvmName] Файл результатов отсутствует или пуст. exitCode=${jmhResult.exitCode}")
                if (jmhResult.stdout.isNotBlank())
                    println("[JMH STDOUT] ${jmhResult.stdout.take(2000)}")
                if (jmhResult.stderr.isNotBlank())
                    println("[JMH STDERR] ${jmhResult.stderr.take(2000)}")
                return createFailedParsingMetrics(jmhResult)
            }

            val reportPath = renameResultFile(jsonResultFile, jvmName)
            val metrics = parseResultFile(File(reportPath), jmhResult)

            return metrics.copy(jmhReportPath = reportPath)
        } catch (e: Exception) {
            println("Ошибка парсинга результатов JMH: ${e.message}")
            if (jmhResult.stdout.isNotBlank())
                println("[JMH STDOUT] ${jmhResult.stdout.take(2000)}")
            createFailedParsingMetrics(jmhResult)
        }
    }

    private fun parseResultFile(jsonResultFile: File, jmhResult: JvmExecutionResult): PerformanceMetrics {
        val objectMapper = jacksonObjectMapper()
        val resultJson = objectMapper.readTree(jsonResultFile)

        if (resultJson.isEmpty || resultJson.first() == null) {
            return createFailedParsingMetrics(jmhResult)
        }

        val primaryMetric = resultJson.first()["primaryMetric"]
        val avgTimeMs = primaryMetric["score"].asDouble()
        val scoreError = primaryMetric["scoreError"].asDouble()
        val minTimeMs = primaryMetric["scorePercentiles"]["0.0"].asDouble()
        val maxTimeMs = primaryMetric["scorePercentiles"]["100.0"].asDouble()

        val memoryUsageRegex = Regex("""AVERAGE_MEMORY_USAGE_KB: (\d+)""")
        val avgMemoryUsageKb = memoryUsageRegex.find(jmhResult.stdout)?.groupValues?.get(1)?.toLongOrNull() ?: -1L

        return PerformanceMetrics(
            avgTimeMs = avgTimeMs,
            avgMemoryUsageKb = avgMemoryUsageKb,
            scoreError = scoreError,
            minTimeMs = minTimeMs,
            maxTimeMs = maxTimeMs,
            successfullyParsed = true,
            jvmExecutionResult = jmhResult
        )
    }

    private fun renameResultFile(jsonResultFile: File, jvmName: String): String {
        val jsonFilePathWithoutExtension = jsonResultFile.path.replace(".json", "")
        val newFile = File("$jsonFilePathWithoutExtension-$jvmName.json")
        jsonResultFile.renameTo(newFile)
        return newFile.absolutePath
    }

    private fun createTimedOutMetrics(jmhResult: JvmExecutionResult): PerformanceMetrics {
        return PerformanceMetrics(
            avgTimeMs = -1.0,
            avgMemoryUsageKb = -1L,
            scoreError = 0.0,
            minTimeMs = 0.0,
            maxTimeMs = 0.0,
            successfullyParsed = false,
            jvmExecutionResult = jmhResult
        )
    }

    private fun createFailedParsingMetrics(jmhResult: JvmExecutionResult): PerformanceMetrics {
        val result = jmhResult.copy(exitCode = -100)
        return PerformanceMetrics(
            avgTimeMs = -1.0,
            avgMemoryUsageKb = -1L,
            scoreError = 0.0,
            minTimeMs = 0.0,
            maxTimeMs = 0.0,
            successfullyParsed = false,
            jvmExecutionResult = result
        )
    }
}