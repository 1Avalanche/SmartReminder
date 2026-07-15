package smartagent.review

data class ReviewContext(
    val owner: String,
    val repo: String,
    val pullRequestNumber: Int,
    val prTitle: String,
    val prDescription: String,
    val baseBranch: String,
    val headBranch: String,
    val changedFiles: String,
    val diff: String,
    val ragContext: String,
    val projectRules: String,
    val fileContents: Map<String, String> = emptyMap()
)
