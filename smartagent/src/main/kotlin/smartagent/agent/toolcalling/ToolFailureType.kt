package smartagent.agent.toolcalling

enum class ToolFailureType {
    TIMEOUT,
    NETWORK_ERROR,
    VALIDATION_ERROR,
    BLOCKED_CONTENT,
    UNKNOWN_ERROR;

    companion object {
        fun classify(error: String): ToolFailureType {
            val msg = error.lowercase()
            return when {
                "timeout" in msg || "timed out" in msg || "deadline" in msg -> TIMEOUT
                "401" in msg || "403" in msg || "blocked" in msg || "forbidden" in msg ||
                        "bot protection" in msg || "access denied" in msg || "captcha" in msg -> BLOCKED_CONTENT
                "connect" in msg || "network" in msg || "unreachable" in msg ||
                        "refused" in msg || "no route" in msg -> NETWORK_ERROR
                "validation" in msg || "invalid" in msg || "unexpected" in msg ||
                        "required" in msg || "schema" in msg || "unknown field" in msg -> VALIDATION_ERROR
                else -> UNKNOWN_ERROR
            }
        }
    }
}
