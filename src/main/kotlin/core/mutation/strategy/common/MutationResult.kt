package core.mutation.strategy.common

/**
 * Результат применения мутации к Jimple-коду.
 */
data class MutationResult(val resultCode: String, val hadError: Boolean)
