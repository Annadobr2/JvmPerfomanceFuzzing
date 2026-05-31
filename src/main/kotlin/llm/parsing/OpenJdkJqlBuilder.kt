package llm.parsing

object OpenJdkJqlBuilder {

    /**
     * Строит JQL для поиска performance-задач.
     */
    fun performanceIssues(config: OpenJdkSearchConfig): String {
        val types = config.issueTypes.joinToString(",") { it.name }
        val labels = config.labels.joinToString(",")
        val excludedStatuses = config.excludeStatuses.joinToString(",")

        return "project = ${config.projectKey}" +
                " AND issuetype in ($types)" +
                " AND labels in ($labels)" +
                " AND status not in ($excludedStatuses)" +
                " ORDER BY ${config.orderBy}"
    }
}