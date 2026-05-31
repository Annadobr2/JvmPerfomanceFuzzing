package infrastructure.launch
import core.mutation.strategy.*
import core.mutation.strategy.common.MutationStrategy
import infrastructure.translator.JimpleTranslator


enum class MutationStrategyType(val description: String) {
    DEAD_CODE("DeadCodeInsertionMutationStrategy"),
    SHUFFLE("ShuffleStatementsMutationStrategy"),
    COND_RANDOM("ConditionalRandomWrapperMutationStrategy"),
    LOOP("LoopWrapperMutationStrategy"),
    BOXING("BoxingUnboxingMutationStrategy"),
    EXCEPTION("ExceptionHandlingMutator"),
    INVERT_BRANCH("InvertBranchConditionMutator"),
    LOOKUP_SWITCH("LookupSwitchMutator"),
    REDUNDANT_COMP("RedundantComputationMutator"),
    REDUNDANT_NULL("RedundantNullCheckMutator"),
    CONST_BLOCK("ConstantFoldingBlockerMutator"),
    REVERSE_LOOP("LoopDirectionReverserMutator"),
}

fun mutationStrategyFromEnum(type: MutationStrategyType, jimpleTranslator: JimpleTranslator) : MutationStrategy = when (type) {
    MutationStrategyType.DEAD_CODE -> DeadCodeInsertionMutationStrategy(jimpleTranslator)
    MutationStrategyType.SHUFFLE -> ShuffleStatementsMutationStrategy(jimpleTranslator)
    MutationStrategyType.COND_RANDOM -> ConditionalRandomWrapperMutationStrategy(jimpleTranslator)
    MutationStrategyType.LOOP -> LoopWrapperMutationStrategy(jimpleTranslator)
    MutationStrategyType.BOXING -> BoxingUnboxingMutationStrategy(jimpleTranslator)
    MutationStrategyType.EXCEPTION -> ExceptionHandlingMutator(jimpleTranslator)
    MutationStrategyType.INVERT_BRANCH -> InvertBranchConditionMutator(jimpleTranslator)
    MutationStrategyType.LOOKUP_SWITCH -> LookupSwitchMutator(jimpleTranslator)
    MutationStrategyType.REDUNDANT_COMP -> RedundantComputationMutator(jimpleTranslator)
    MutationStrategyType.REDUNDANT_NULL -> RedundantNullCheckMutator(jimpleTranslator)
    MutationStrategyType.CONST_BLOCK -> ConstantFoldingBlockerMutator(jimpleTranslator)
    MutationStrategyType.REVERSE_LOOP -> LoopDirectionReverserMutator(jimpleTranslator)
}
