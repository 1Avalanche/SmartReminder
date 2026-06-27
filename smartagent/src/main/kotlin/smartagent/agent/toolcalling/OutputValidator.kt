package smartagent.agent.toolcalling

object OutputValidator {

    const val FALLBACK_MESSAGE = "System error: invalid agent response format. Please try again."
    const val MAX_PARSE_RETRIES = 2

    private val FORBIDDEN_XML_TOKENS = listOf(
        "<tool_calls>", "</tool_calls>",
        "<tool_call>", "</tool_call>",
        "<invoke>", "</invoke>",
        "<function_calls>", "</function_calls>"
    )

    fun containsXml(raw: String): Boolean {
        val lower = raw.lowercase()
        return FORBIDDEN_XML_TOKENS.any { it in lower }
    }

    fun isSafeForUser(text: String): Boolean = !containsXml(text)

    fun xmlRejectionPrompt(raw: String): String {
        val preview = raw.take(200).replace("\n", "↵")
        return buildString {
            appendLine("Your response used an unsupported XML format.")
            appendLine("Preview: $preview")
            appendLine()
            appendLine("You MUST use ONLY one of these two formats:")
            appendLine()
            appendLine("Format 1 — tool call:")
            appendLine("TOOL_CALL")
            appendLine("tool=<tool_name>")
            appendLine("arguments={\"key\":\"value\"}")
            appendLine()
            appendLine("Format 2 — final answer:")
            appendLine("FINAL_ANSWER")
            appendLine("<your response>")
            appendLine()
            appendLine("Do NOT output XML, markdown code blocks, or any other format.")
        }.trimEnd()
    }

    fun parseErrorRecoveryPrompt(raw: String, reason: String): String {
        val preview = raw.take(200).replace("\n", "↵")
        return buildString {
            appendLine("Your response could not be parsed. Reason: $reason")
            appendLine("Preview: $preview")
            appendLine()
            appendLine("Respond with EXACTLY one of:")
            appendLine()
            appendLine("TOOL_CALL")
            appendLine("tool=<tool_name>")
            appendLine("arguments={\"key\":\"value\"}")
            appendLine()
            appendLine("OR:")
            appendLine()
            appendLine("FINAL_ANSWER")
            appendLine("<your response>")
            appendLine()
            appendLine("No text before these keywords. No XML. No markdown.")
        }.trimEnd()
    }
}
