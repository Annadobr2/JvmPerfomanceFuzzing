package llm.parsing

interface Scraper {
    fun searchPerformanceIssues(maxIssues: Int = 20): List<OpenJdkIssue>
}