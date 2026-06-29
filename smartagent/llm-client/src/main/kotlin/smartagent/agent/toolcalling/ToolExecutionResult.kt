package smartagent.agent.toolcalling

data class ToolExecutionResult(
    val toolName: String,
    val result: String,
    val isError: Boolean = false
)
