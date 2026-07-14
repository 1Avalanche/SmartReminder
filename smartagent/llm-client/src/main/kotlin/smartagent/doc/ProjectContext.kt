package smartagent.doc

data class ProjectContext(
    val ragContext: String,
    val gitContext: DocGitContext?
)
