package llm.generate

class PromptConstructor(
    private val maxContextChars: Int = 8_000,
) {

    fun build(
        role: String,
        instruction: String,
        programContext: String,
        outputRules: String,
    ): String {
        val truncatedContext = programContext.trim().take(maxContextChars)
        val wasTruncated = truncatedContext.length < programContext.trim().length
        if (wasTruncated) {
            println("[PromptConstructor] programContext truncated to $maxContextChars chars (was ${programContext.trim().length})")
        }

        return buildString {
            appendLine("### ROLE")
            appendLine(role.trim())
            appendLine()

            appendLine("### INSTRUCTION")
            appendLine(instruction.trim())
            appendLine()

            appendLine("### PROGRAM CONTEXT")
            appendLine(truncatedContext)
            appendLine()

            appendLine("### OUTPUT RULES")
            appendLine(outputRules.trim())
            appendLine()
        }
    }
}
