package core.seed

import core.mutation.MutationRecord
import infrastructure.performance.anomaly.AnomalyGroupType
import infrastructure.performance.anomaly.PerformanceAnomalyGroup
import infrastructure.performance.entity.SignificanceLevel
import java.io.File
import kotlin.math.max

/**
 * Представляет единичный сид для фаззинга.
 * Хранит байткод, историю мутаций, метрики и подтвержденные аномалии.
 */
data class Seed(
    val bytecodeEntry: BytecodeEntry,
    var javacode: String,
    val mutationHistory: List<MutationRecord> = emptyList(),
    var energy: Int,
    var description: String,
    var interestingness: Double = 0.0,
    var anomalies: List<PerformanceAnomalyGroup> = emptyList(),
    var initial: Boolean = false,
    val iteration: Int = 0,
    var verified: Boolean = false
) {

    constructor(
        bytecodeEntry: BytecodeEntry,
        javacode: String,
        mutationHistory: List<MutationRecord> = emptyList(),
        anomalies: List<PerformanceAnomalyGroup> = emptyList(),
        iteration: Int = 0,
        interestingness: Double = 0.0,
        verified: Boolean = false
    ) : this(
        bytecodeEntry,
        javacode,
        mutationHistory,
        calculateEnergy(interestingness),
        generateSeedDescription(SignificanceLevel.NOT_SIGNIFICANT, verified, "", anomalies, iteration),
        interestingness,
        anomalies,
        false,
        iteration,
        verified
    )

    fun updateWithVerificationResults(
        significanceLevel: SignificanceLevel,
        confirmedAnomalies: List<PerformanceAnomalyGroup>,
        newInterestingness: Double
    ) {
        cleanAnomaliesArtifactReports()
        anomalies = confirmedAnomalies
        interestingness = newInterestingness
        energy = calculateEnergy(newInterestingness)
        verified = confirmedAnomalies.isNotEmpty()
        description = generateSeedDescription(significanceLevel, verified, description, anomalies, iteration)
    }

    fun cleanAnomaliesArtifactReports() {
        anomalies.forEach { anomalyGroup ->
            val allJvms = anomalyGroup.fasterJvms + anomalyGroup.slowerJvms

            allJvms.forEach { jvmResult ->
                jvmResult.metrics.jmhReportPath?.let { reportPath ->
                    val sourceFile = File(reportPath)
                    if (sourceFile.exists()) {
                        sourceFile.delete()
                    }
                }
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Seed) return false
        return bytecodeEntry.bytecode.contentEquals(other.bytecodeEntry.bytecode)
    }

    override fun hashCode(): Int {
        return bytecodeEntry.bytecode.contentHashCode()
    }

    companion object {
        private const val INITIAL_ENERGY = 10


        fun calculateEnergy(interestingness: Double): Int {
            return max(INITIAL_ENERGY, (interestingness / 10.0).toInt())
        }

        fun generateSeedDescription(
            significanceLevel: SignificanceLevel,
            verified: Boolean,
            oldDescription: String,
            anomalies: List<PerformanceAnomalyGroup>,
            iteration: Int
        ): String {
            val verifiedStatus = if (verified) "verified" else "notVerified"
            if (anomalies.isEmpty()) {
                return oldDescription.clearSignificanceLevel() + "_${verifiedStatus}_$significanceLevel"
            }

            val types = anomalies.map { it.anomalyType }.distinct().joinToString("_")

            val maxDeviation = anomalies
                .filter { it.anomalyType in listOf(AnomalyGroupType.TIME, AnomalyGroupType.MEMORY) }
                .maxOfOrNull { it.maxDeviation }
                ?.toInt() ?: 0

            val parts = buildList {
                if (maxDeviation > 0) add("_dev${maxDeviation}pct")
                if (anomalies.any { it.anomalyType == AnomalyGroupType.TIMEOUT }) add("_timeout")
                if (anomalies.any { it.anomalyType == AnomalyGroupType.ERROR }) add("_error")
                if (anomalies.any { it.anomalyType == AnomalyGroupType.JIT }) add("_jit")
            }

            return "anomaly_${types}${parts.joinToString("")}_iter_${iteration}_${verifiedStatus}_${significanceLevel}"
        }

        fun String.clearSignificanceLevel(): String {
            return this
                .replace("_verified", "")
                .replace("_notVerified", "")
                .replace("_" + SignificanceLevel.NOT_SIGNIFICANT.toString(), "")
                .replace("_" + SignificanceLevel.SEED_EVOLUTION.toString(), "")
                .replace("_" + SignificanceLevel.REPORTING.toString(), "")
        }
    }
}