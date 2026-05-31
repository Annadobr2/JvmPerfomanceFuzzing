package llm.parsing

data class OpenJdkIssue(
    val key: String,
    val id: Long,
    val summary: String,
    val description: String?,
    val status: String,
    val issueType: String,
    val resolution: String?,
    val fixVersions: List<String>,
    val components: List<String>,
    val labels: List<String>,
    val url: String,
)

class OpenJdkIssueMapper(private val baseUrl: String) {

    fun fromDto(dto: JiraIssue): OpenJdkIssue {
        val f = dto.fields
        return OpenJdkIssue(
            key = dto.key,
            id = dto.id.toLongOrNull() ?: -1L,
            summary = f.summary.orEmpty(),
            description = f.description,
            status = f.status?.name.orEmpty(),
            issueType = f.issuetype?.name.orEmpty(),
            resolution = f.resolution?.name,
            fixVersions = f.fixVersions?.mapNotNull { it.name }.orEmpty(),
            components = f.components?.mapNotNull { it.name }.orEmpty(),
            labels = f.labels.orEmpty(),
            url = "${baseUrl.trimEnd('/')}/browse/${dto.key}",
        )
    }

    fun toBugsStructure(issue: OpenJdkIssue): BugsStructure {
        val numericId = issue.key.substringAfter("-").toIntOrNull() ?: -1

        val component = issue.components.firstOrNull().orEmpty()
        val subcomponent = issue.components.drop(1).joinToString(", ")

        val type = when (issue.issueType.lowercase()) {
            "enhancement" -> TypeBugs.Enhancement
            else -> TypeBugs.Bug
        }

        val text = buildString {
            append(issue.summary)
            val description = issue.description?.trim()
            if (!description.isNullOrEmpty()) {
                append("\n\n")
                append(description)
            }
        }

        return BugsStructure(
            source = ValueSource.OPENJDK,
            id = numericId,
            status = issue.status,
            text = text,
            systemNumber = issue.key,
            component = component,
            subcomponent = subcomponent,
            type = type,
            resolution = issue.resolution.orEmpty(),
            fixVersion = issue.fixVersions.firstOrNull().orEmpty(),
        )
    }
}