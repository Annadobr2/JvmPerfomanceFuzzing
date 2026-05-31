package llm.mutation

import llm.client.LlmClient
import llm.generate.PromptConstructor

/**
 * Стратегия "переписывания":
 *   взять исходный код сида и написать функционально эквивалентную,
 *   но структурно иную реализацию.
 */
class RewriteCodeStrategy(
    promptConstructor: PromptConstructor,
    llmClient: LlmClient,
) : MutationStrategyLLM(promptConstructor, llmClient) {

    override val instructionPrompt: String = """
        Modify the provided Java code by rewriting it as a DIFFERENT but functionally equivalent program.
        - Change variable names, control flow structure, and data structures where possible.
        - Preserve the overall computational intent and the required class/method signatures.
        - Do NOT just rename identifiers — meaningfully restructure the logic.
        - The rewritten program must stress-test the JVM in a similar way as the original.
    """.trimIndent()
}
