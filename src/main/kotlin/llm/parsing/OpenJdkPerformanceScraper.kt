package llm.parsing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.File
import java.time.Duration

class OpenJdkPerformanceScraper(
    private val config: OpenJdkSearchConfig = OpenJdkSearchConfig(),
    private val client: OkHttpClient = defaultClient(),
) : Scraper {

    private val api = OpenJdkJiraApi(config.baseUrl, client)
    private val mapper = OpenJdkIssueMapper(config.baseUrl)

    private val fields = listOf(
        "summary", "description", "status", "issuetype",
        "resolution", "fixVersions", "components", "labels",
    )

    // ---- public API ----

    override fun searchPerformanceIssues(maxIssues: Int): List<OpenJdkIssue> {
        require(maxIssues > 0) { "maxIssues must be > 0" }
        return paginate(maxIssues) { dto -> mapper.fromDto(dto) }
    }

    fun searchPerformanceBugs(maxIssues: Int): List<BugsStructure> {
        require(maxIssues > 0) { "maxIssues must be > 0" }
        return paginate(maxIssues) { dto -> mapper.toBugsStructure(mapper.fromDto(dto)) }
    }

    /**
     * Читает баги из кэш-директории [cacheDir]
     *
     * @param maxIssues  максимальное число багов для возврата (>0)
     * @param cacheDir   директория с JSON-файлами (data/openjdk_bugs)
     * @return список [BugsStructure], прочитанных из кэша
     */
    fun exportPerformanceBugsAsSeparateFiles(maxIssues: Int, cacheDir: File): List<BugsStructure> {
        require(maxIssues > 0) { "maxIssues must be > 0" }

        if (!cacheDir.exists() || !cacheDir.isDirectory) {
            println("[Scraper] Cache dir not found: ${cacheDir.absolutePath}")
            return emptyList()
        }

        val jsonMapper = jacksonObjectMapper()

        val files = cacheDir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedBy { it.name }
            ?: emptyList()

        println("[Scraper] Found ${files.size} JSON files in ${cacheDir.path}, loading up to $maxIssues")

        return files
            .take(maxIssues)
            .mapNotNull { file ->
                try {
                    jsonMapper.readValue<BugsStructure>(file)
                } catch (e: Exception) {
                    println("[Scraper] Skipping ${file.name}: ${e.message}")
                    null
                }
            }
            .also { println("[Scraper] Loaded ${it.size} bugs from cache") }
    }

    // ---- pagination ----

    /**
     * Пагинация. Обходит все страницы Jira до [maxIssues]
     * и применяет [transform] к каждому DTO. Возвращает список результатов.
     */
    private fun <T> paginate(maxIssues: Int, transform: (JiraIssue) -> T): List<T> {
        val jql = OpenJdkJqlBuilder.performanceIssues(config)
        val result = mutableListOf<T>()
        var startAt = 0

        while (result.size < maxIssues) {
            val pageSize = minOf(50, maxIssues - result.size)
            val response = api.search(jql, startAt, pageSize, fields)

            if (response.issues.isEmpty()) break

            response.issues
                .take(maxIssues - result.size)
                .mapTo(result, transform)

            val next = startAt + response.maxResults
            if (next >= response.total) break
            startAt = next
        }

        return result
    }

    // ---- defaults ----

    companion object {
        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(Duration.ofSeconds(20))
                .readTimeout(Duration.ofSeconds(120))
                .writeTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(240))
                .retryOnConnectionFailure(true)
                .build()
    }
}