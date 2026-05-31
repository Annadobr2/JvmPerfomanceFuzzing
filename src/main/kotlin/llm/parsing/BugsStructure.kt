package llm.parsing

data class BugsStructure(
    val source: ValueSource,
    val id: Int,
    val status: String,
    val text: String,
    val systemNumber: String,
    val component: String,
    val subcomponent: String,
    val type: TypeBugs,
    val resolution: String,
    val fixVersion: String,
)

enum class ValueSource { OPENJDK, ORACLE }

enum class TypeBugs { Bug, Enhancement }