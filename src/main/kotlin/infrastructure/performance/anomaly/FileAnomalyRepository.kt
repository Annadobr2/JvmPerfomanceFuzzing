package infrastructure.performance.anomaly

import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import core.mutation.MutationRecord
import core.seed.BytecodeEntry
import core.seed.Seed
import core.seed.Seed.Companion.clearSignificanceLevel
import infrastructure.jit.JITReportGenerator
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Хранилище аномалий производительности на файловой системе.
 * Сохраняет детальную информацию о сидах, их байткоде, обнаруженных аномалиях и JIT-данных.
 */
class FileAnomalyRepository(
    directoryPath: String,
    private val jitReportGenerator: JITReportGenerator = JITReportGenerator()
) : AnomalyRepository {
    private val objectMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        factory.setStreamReadConstraints(
            StreamReadConstraints.builder().maxStringLength(50_000_000).build()
        )
    }
    private val rootDir = File(directoryPath).apply { mkdirs() }
    private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    override fun getAllSeeds(): List<Seed> {
        if (!rootDir.exists() || !rootDir.isDirectory) {
            println("Репозиторий аномалий не существует: ${rootDir.absolutePath}")
            return emptyList()
        }

        val seedDirectories = rootDir.listFiles { file ->
            file.isDirectory && file.name.startsWith("seed_")
        } ?: return emptyList()

        println("Найдено ${seedDirectories.size} директорий с сидами")

        return seedDirectories.mapNotNull { seedDir ->
            try {
                loadSeedFromDirectory(seedDir)
            } catch (e: Exception) {
                println("Ошибка при загрузке сида из директории ${seedDir.name}: ${e.message}")
                e.printStackTrace()
                null
            }
        }
    }

    override fun saveSeedAnomalies(seed: Seed) {
        if (seed.anomalies.isEmpty()) {
            println("Сид не содержит аномалий для сохранения")
            return
        }

        val timestamp = LocalDateTime.now().format(timestampFormatter)
        val seedDirName = createSeedDirectoryName(seed)
        val seedDir = File(rootDir, seedDirName).apply { mkdirs() }
        val directories = createDirectoryStructure(seedDir)

        saveSeedInfo(seed, directories.seedDir, timestamp)
        saveBytecode(seed, directories.bytecodeDir)
        saveAnomalies(seed, directories, timestamp)

        println("Сохранено ${seed.anomalies.size} аномалий для сида ${seed.description} в ${seedDir.absolutePath}")
    }

    override fun clear() {
        rootDir.listFiles()?.forEach { it.deleteRecursively() }
        println("Репозиторий аномалий очищен")
    }

    /**
     * Загружает сид из указанной директории.
     */
    private fun loadSeedFromDirectory(seedDir: File): Seed? {
        val seedInfoFile = File(seedDir, "seed_info.json")
        if (!seedInfoFile.exists()) {
            println("Отсутствует файл информации о сиде: ${seedInfoFile.absolutePath}")
            return null
        }

        val seedInfo = objectMapper.readValue(seedInfoFile.readText(), Map::class.java)

        val bytecodeDir = File(seedDir, "bytecode")
        if (!bytecodeDir.exists() || !bytecodeDir.isDirectory) {
            println("Отсутствует директория байткода: ${bytecodeDir.absolutePath}")
            return null
        }

        val className = seedInfo["className"] as String
        val packageName = seedInfo["packageName"] as String

        val classFile = bytecodeDir.listFiles { file ->
            file.isFile && file.name == "$className.class"
        }?.firstOrNull()

        if (classFile == null) {
            println("Не найден файл байткода для класса $className в ${bytecodeDir.absolutePath}")
            return null
        }

        val bytecode = Files.readAllBytes(classFile.toPath())

        val anomaliesDir = File(seedDir, "anomalies")
        val anomalies = if (anomaliesDir.exists() && anomaliesDir.isDirectory) {
            anomaliesDir.listFiles { file ->
                file.isFile && file.name.startsWith("anomaly_") && file.name.endsWith(".json")
            }?.mapNotNull { anomalyFile ->
                try {
                    objectMapper.readValue(anomalyFile.readText(), PerformanceAnomalyGroup::class.java)
                } catch (e: Exception) {
                    println("Ошибка при загрузке аномалии из файла ${anomalyFile.name}: ${e.message}")
                    null
                }
            } ?: emptyList()
        } else {
            emptyList()
        }

        val bytecodeEntry = BytecodeEntry(bytecode, className, packageName)

        @Suppress("UNCHECKED_CAST")
        val mutationHistory = try {
            (seedInfo["mutationHistory"] as List<Map<String, String>>).map { mutationMap ->
                MutationRecord(
                    parentSeedDescription = mutationMap["parentSeedDescription"] as String,
                    strategyName = mutationMap["strategyName"] as String,
                    timestamp = mutationMap["timestamp"] as String
                )
            }
        } catch (e: Exception) {
            println("Ошибка при загрузке истории мутаций: ${e.message}")
            emptyList()
        }

        val interestingness = (seedInfo["interestingness"] as Number).toDouble()
        val verified = seedInfo["verified"] as Boolean
        val description = seedInfo["description"] as String
        val javacode = seedInfo["javacode"] as? String ?: ""
        val energy = Seed.calculateEnergy(interestingness)

        return Seed(
            bytecodeEntry = bytecodeEntry,
            javacode = javacode,
            mutationHistory = mutationHistory,
            energy = energy,
            description = description,
            interestingness = interestingness,
            anomalies = anomalies,
            verified = verified,
            initial = false,
            iteration = 0
        )
    }

    private data class DirectoryStructure(
        val seedDir: File,
        val anomaliesDir: File,
        val bytecodeDir: File,
        val jmhReportsDir: File
    )

    private fun createDirectoryStructure(seedDir: File): DirectoryStructure {
        val anomaliesDir = File(seedDir, "anomalies").apply { mkdirs() }
        val bytecodeDir = File(seedDir, "bytecode").apply { mkdirs() }
        val jmhReportsDir = File(seedDir, "jmh_reports").apply { mkdirs() }

        return DirectoryStructure(seedDir, anomaliesDir, bytecodeDir, jmhReportsDir)
    }

    private fun createSeedDirectoryName(seed: Seed): String {
        val bytecodeHash = seed.bytecodeEntry.bytecode.contentHashCode().toString(16)
        return "seed_${seed.description.replace(" ", "_").clearSignificanceLevel()}_${bytecodeHash}"
    }

    private fun saveSeedInfo(seed: Seed, seedDir: File, timestamp: String) {
        val seedInfo = mapOf(
            "description" to seed.description,
            "className" to seed.bytecodeEntry.className,
            "packageName" to seed.bytecodeEntry.packageName,
            "javacode" to seed.javacode,
            "mutationHistory" to seed.mutationHistory,
            "interestingness" to seed.interestingness,
            "verified" to seed.verified,
            "timestamp" to timestamp,
            "anomaliesCount" to seed.anomalies.size
        )

        File(seedDir, "seed_info.json").writeText(
            objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(seedInfo)
        )
    }

    private fun saveBytecode(seed: Seed, bytecodeDir: File) {
        val bytecodeFile = File(bytecodeDir, "${seed.bytecodeEntry.className}.class")
        Files.write(bytecodeFile.toPath(), seed.bytecodeEntry.bytecode)
    }

    private fun saveAnomalies(seed: Seed, directories: DirectoryStructure, timestamp: String) {
        val timestampString = timestamp.replace(" ", "_").replace(":", "-")

        seed.anomalies.forEachIndexed { index, anomalyGroup ->
            val filePrefix = generateAnomalyFilePrefix(anomalyGroup, index)
            val anomalyFile = File(directories.anomaliesDir, "anomaly_${filePrefix}_$timestampString.json")

            if (anomalyGroup.anomalyType == AnomalyGroupType.JIT) {
                objectMapper.writeValue(anomalyFile, anomalyGroup)
            } else {
                objectMapper.writeValue(anomalyFile, anomalyGroup.copy(jitData = null))
            }
        }

        val reportsCopied: Int = copyJmhReports(seed, directories.jmhReportsDir)
        if (reportsCopied > 0) {
            println("Сохранено $reportsCopied JMH отчетов для сида ${seed.description}")
        }

        generateJitReports(seed, directories.anomaliesDir)
    }

    private fun copyJmhReports(seed: Seed, jmhReportsDir: File): Int {
        val jmhReports = mutableSetOf<String>()

        seed.anomalies.forEach { anomalyGroup ->
            val allJvms = anomalyGroup.fasterJvms + anomalyGroup.slowerJvms

            allJvms.forEach { jvmResult ->
                jvmResult.metrics.jmhReportPath?.let { reportPath ->
                    val sourceFile = File(reportPath)
                    if (sourceFile.exists()) {
                        val targetFileName =
                            "jmh_${jvmResult.jvmName}.json"
                        val targetFile = File(jmhReportsDir, targetFileName)
                        if (!targetFile.exists()) {
                            sourceFile.copyTo(targetFile, overwrite = true)
                            jmhReports.add(targetFileName)
                        }
                    }
                }
            }
        }
        seed.cleanAnomaliesArtifactReports()

        return jmhReports.size
    }

    private fun generateJitReports(seed: Seed, anomaliesDir: File) {
        seed.anomalies
            .filter { it.jitData != null }
            .distinctBy { it.jitData.hashCode() }
            .forEachIndexed { index, anomalyGroup ->
                val jitDir = File(anomaliesDir, "jit_analysis").apply { mkdirs() }

                anomalyGroup.jitData?.let { jitData ->
                    val report = jitReportGenerator.generateMarkdownReport(jitData)
                    File(jitDir, "jit_report_$index.md").writeText(report)
                    File(jitDir, "jit_details_$index.json").writeText(
                        objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jitData)
                    )
                }
            }
    }

    private fun generateAnomalyFilePrefix(anomaly: PerformanceAnomalyGroup, index: Int): String {
        val anomalyType = anomaly.anomalyType.name.lowercase()

        val anomalyDescription = when (anomaly.anomalyType) {
            AnomalyGroupType.TIME, AnomalyGroupType.MEMORY -> {
                val deviation = "%.0f".format(anomaly.averageDeviation)
                "${anomalyType}_dev${deviation}pct"
            }

            AnomalyGroupType.TIMEOUT -> "timeout"

            AnomalyGroupType.ERROR -> {
                val exitCode = anomaly.exitCodes.values.firstOrNull() ?: "unknown"
                "error_code${exitCode}"
            }

            AnomalyGroupType.JIT -> {
                val probability = anomaly.jitData?.comparisons?.maxOfOrNull { it.jitRelatedProbability } ?: 0.0
                val probPercent = "%.0f".format(probability * 100)
                "jit_prob${probPercent}pct"
            }
        }

        return "${index}_${anomalyDescription}"
    }
}
