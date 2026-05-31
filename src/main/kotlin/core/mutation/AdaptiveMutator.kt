package core.mutation

import core.mutation.strategy.common.MutationStrategy
import core.mutation.strategy.common.StrategyStats
import infrastructure.translator.JimpleTranslator
import llm.mutation.MutationStrategyLLM
import kotlin.random.Random

/**
 * Адаптивный мутатор, который выбирает стратегии мутации на основе их эффективности.
 * Использует механизм "рулетки" с весами, основанными на предыдущих результатах.
 */
class AdaptiveMutator(
    private val jimpleTranslator: JimpleTranslator,
    private val strategies: List<MutationStrategy>,
    private val newStrategies : List<MutationStrategyLLM>,
    private val explorationFactor: Double = 0.2,
    private val forgetFactor: Double = 0.9,
    private val forgetFrequency: Int = 250
) : Mutator {
    private var iterationsSinceLastForget = 0
    private val strategyStats = mutableMapOf<String, StrategyStats>()
    private val random = Random

    private var lastMutationRecord: MutationRecord? = null

    override fun mutate(bytecode: ByteArray, className: String, packageName: String): ByteArray {
        refreshStatistics()

        val jimpleCode = jimpleTranslator.toJimple(bytecode, className)
        val selectedStrategy = selectStrategy()
        val strategyName = selectedStrategy::class.simpleName ?: "Unknown"

        val result = selectedStrategy.apply(jimpleCode.data, className, packageName)
        val success = !result.hadError
        println("Стратегия: $strategyName | Мутация: ${if (success) "✓ успешно" else "✗ неудачно"}")

        lastMutationRecord = MutationRecord(
            parentSeedDescription = "",  // Заполняется в EvolutionaryFuzzer
            strategyName = strategyName
        )

        updateStats(strategyName, wasSuccessful = success)

        val finalJimple = if (success) result.resultCode else jimpleCode.data
        val tempByteArray = jimpleTranslator.toBytecode(finalJimple, className, packageName)
        return tempByteArray
    }

    override fun mutate(javacode: String, className: String, packageName: String): Pair<ByteArray?, String> {
        refreshStatistics()

        val selectedStrategy = selectStrategyNew()
        // Используем strategyId() вместо simpleName — для DynamicMutationStrategyLLM
        // это уникальное имя промпта, а не "DynamicMutationStrategyLLM" для всех сразу
        val strategyName = selectedStrategy.strategyId()

        val result = selectedStrategy.apply(javacode, className, packageName)
        println("Стратегия: $strategyName | Мутация: ${if (!result.hadError) "✓ успешно" else "✗ неудачно"}")

        lastMutationRecord = MutationRecord(
            parentSeedDescription = "",  // Заполняется в EvolutionaryFuzzer
            strategyName = strategyName
        )

        updateStats(strategyName, wasSuccessful = !result.hadError)

        // Если LLM вернул невалидный код — не тратим время на компиляцию оригинала
        if (result.hadError) return Pair(null, javacode)

        val newByteCode = selectedStrategy.compileJavaCode(className, result.resultCode)
        return Pair(newByteCode, result.resultCode)
    }


    fun getLastMutationRecord(): MutationRecord? = lastMutationRecord

    fun notifyNewSeedGenerated(foundAnomaly: Boolean = false) {
        lastMutationRecord?.let { mutation ->
            strategyStats[mutation.strategyName]?.apply {
                seedsGenerated++
                if (foundAnomaly) anomaliesFound++
            }
        }
    }

    fun notifySeedRejected() {
        lastMutationRecord?.let { mutation ->
            strategyStats[mutation.strategyName]?.apply {
                failures++
            }
        }
    }

    private fun selectStrategy(): MutationStrategy {



        if (random.nextDouble() < explorationFactor) {
            return strategies.random() as MutationStrategy
        }

        val weightedStrategies = strategies.map { strategy ->
            val name = strategy::class.simpleName ?: "Unknown"
            val effectiveness = strategyStats[name]?.calculateEffectiveness() ?: 0.1
            strategy to effectiveness
        }

        val totalWeight = weightedStrategies.sumOf { it.second }
        if (totalWeight <= 0) {
            return strategies.random() as MutationStrategy
        }

        val randomPoint = random.nextDouble() * totalWeight
        var cumulativeWeight = 0.0

        for ((strategy, weight) in weightedStrategies) {
            cumulativeWeight += weight
            if (randomPoint <= cumulativeWeight) {
                return strategy as MutationStrategy
            }
        }

        return strategies.random() as MutationStrategy
    }

    private fun selectStrategyNew(): MutationStrategyLLM {
        if (newStrategies.isEmpty()) error("No LLM mutation strategies available")

        // Exploration: случайный выбор с вероятностью explorationFactor
        if (random.nextDouble() < explorationFactor) {
            return newStrategies.random()
        }

        // Exploitation: рулетка по эффективности, ключ — strategyId()
        val weighted = newStrategies.map { strategy ->
            val effectiveness = strategyStats[strategy.strategyId()]?.calculateEffectiveness() ?: 0.1
            strategy to effectiveness
        }

        val total = weighted.sumOf { it.second }
        if (total <= 0) return newStrategies.random()

        var point = random.nextDouble() * total
        for ((strategy, weight) in weighted) {
            point -= weight
            if (point <= 0) return strategy
        }

        return newStrategies.random()
    }


    private fun updateStats(strategyName: String, wasSuccessful: Boolean) {
        strategyStats.getOrPut(strategyName) { StrategyStats() }.apply {
            totalApplications++
            if (wasSuccessful) successfulMutations++
        }
    }

    private fun refreshStatistics() {
        if (++iterationsSinceLastForget >= forgetFrequency) {
            applyForgetFactor()
            iterationsSinceLastForget = 0
        }
    }

    private fun applyForgetFactor() {
        strategyStats.values.forEach { stats ->
            with(stats) {
                totalApplications = (totalApplications * forgetFactor).toInt()
                successfulMutations = (successfulMutations * forgetFactor).toInt()
                seedsGenerated = (seedsGenerated * forgetFactor).toInt()
                anomaliesFound = (anomaliesFound * forgetFactor).toInt()
                failures = (failures * forgetFactor).toInt()
            }
        }
    }
}