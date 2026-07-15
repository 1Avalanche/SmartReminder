package smartagent.review

enum class ReviewSeverity { CRITICAL, HIGH, MEDIUM, LOW, INFO }

enum class ReviewCategory {
    BUG, RUNTIME_ERROR, EDGE_CASE, STATE_MANAGEMENT,
    PERFORMANCE, ARCHITECTURE, CODE_QUALITY, SECURITY
}

data class ReviewIssue(
    val severity: ReviewSeverity,
    val category: ReviewCategory,
    val file: String,
    val line: Int? = null,
    val description: String,
    val recommendation: String
)

data class ReviewReport(
    val owner: String,
    val repo: String,
    val pullRequestNumber: Int,
    val prTitle: String,
    val timestamp: String,
    val summary: String,
    val issues: List<ReviewIssue>
)
