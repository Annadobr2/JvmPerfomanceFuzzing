package llm.parsing

data class OpenJdkSearchConfig(
    val baseUrl: String = "https://bugs.openjdk.org",
    val projectKey: String = "JDK",
    val labels: List<String> = listOf(
        "Performance",
        "performance",
        "performance-prg",
        "performance-test",
        "PerformDynamicPatch"
    ),
    val issueTypes: List<TypeBugs> = listOf(TypeBugs.Bug, TypeBugs.Enhancement),
    val orderBy: String = "updated DESC",
    // Исключение закрытых, исправленных, отклонённых
    val excludeStatuses: List<String> = listOf("Resolved", "Closed"),
)