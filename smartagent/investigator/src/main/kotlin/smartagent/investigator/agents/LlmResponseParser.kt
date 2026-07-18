package smartagent.investigator.agents

private val THINK_BLOCK = Regex("<think>[\\s\\S]*?</think>", setOf(RegexOption.IGNORE_CASE))

fun stripThinkBlocks(content: String): String =
    THINK_BLOCK.replace(content, "").trim()

fun extractJson(content: String): String {
    val stripped = stripThinkBlocks(content)
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

    if (stripped.startsWith("{") || stripped.startsWith("[")) return stripped

    // JSON embedded in surrounding text (MiniMax sometimes wraps content as `"content": "..."`)
    val start = stripped.indexOf('{')
    val end = stripped.lastIndexOf('}')
    if (start >= 0 && end > start) return stripped.substring(start, end + 1)

    return stripped
}
