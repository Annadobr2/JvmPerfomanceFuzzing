package llm.parsing

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class OpenJdkJiraApi(
    private val baseUrl: String,
    private val client: OkHttpClient,
) {
    private val mapper = jacksonObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    fun search(
        jql: String,
        startAt: Int,
        maxResults: Int,
        fields: List<String>,
    ): JiraSearchResponse {
        val url = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("rest/api/2/search")
            .addQueryParameter("jql", jql)
            .addQueryParameter("startAt", startAt.toString())
            .addQueryParameter("maxResults", maxResults.toString())
            .addQueryParameter("fields", fields.joinToString(","))
            .build()

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body
                ?: throw JiraApiException("Empty response body (HTTP ${response.code})")

            if (!response.isSuccessful) {
                val errorText = body.string().take(3000)
                throw JiraApiException("HTTP ${response.code}: $errorText")
            }

            // Читаем поток один раз — без промежуточного bytes()
            return mapper.readValue(body.byteStream(), JiraSearchResponse::class.java)
        }
    }
}

class JiraApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

// -------- DTOs --------

@JsonIgnoreProperties(ignoreUnknown = true)
data class JiraSearchResponse(
    val startAt: Int = 0,
    val maxResults: Int = 0,
    val total: Int = 0,
    val issues: List<JiraIssue> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JiraIssue(
    val id: String = "",
    val key: String = "",
    val fields: JiraFields = JiraFields(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JiraFields(
    val summary: String? = null,
    val description: String? = null,
    val status: JiraNamed? = null,
    val issuetype: JiraNamed? = null,
    val resolution: JiraNamed? = null,
    val fixVersions: List<JiraNamed>? = null,
    val components: List<JiraNamed>? = null,
    val labels: List<String>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class JiraNamed(
    val name: String? = null,
)