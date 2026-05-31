package llm.generate

/**
 * Результат первого шага: анализ JSON-описания бага.
 *
 * @param feasible      фдаг можно ли написать Java-программу по этому описанию
 * @param reason        причина
 * @param condensedHint сжатая подсказка для генерации
 */
data class PromptAnalysisResult(
    val feasible: Boolean,
    val reason: String,
    val condensedHint: String?,
)