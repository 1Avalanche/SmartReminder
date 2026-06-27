package smartagent.agent.toolcalling

/**
 * Tracks tool failure state during a single ToolCallingLoop run.
 * Not thread-safe — one instance per run() call.
 */
class ToolFailureEngine(allToolNames: Set<String>) {

    private data class ToolRunState(
        val name: String,
        var successCount: Int = 0,
        var failureCount: Int = 0,
        var lastFailureType: ToolFailureType? = null,
        var disabled: Boolean = false
    )

    private val states: MutableMap<String, ToolRunState> =
        allToolNames.associateWith { ToolRunState(it) }.toMutableMap()

    // Canonical key for a (toolName, args) pair — prevents identical retry detection
    private val calledSignatures = mutableSetOf<String>()

    val allToolNames: Set<String> = allToolNames

    val disabledTools: Set<String>
        get() = states.values.filter { it.disabled }.map { it.name }.toSet()

    val availableTools: Set<String>
        get() = allToolNames - disabledTools

    fun getState(toolName: String): Triple<Int, Int, ToolFailureType?> {
        val s = states[toolName] ?: return Triple(0, 0, null)
        return Triple(s.successCount, s.failureCount, s.lastFailureType)
    }

    fun isDisabled(toolName: String): Boolean = states[toolName]?.disabled == true

    /** Returns true if this exact (tool, args) pair was already attempted this run. */
    fun isAlreadyCalled(toolName: String, args: Map<String, String>): Boolean =
        callSignature(toolName, args) in calledSignatures

    fun markCalled(toolName: String, args: Map<String, String>) {
        calledSignatures.add(callSignature(toolName, args))
    }

    fun recordSuccess(toolName: String) {
        val s = states.getOrPut(toolName) { ToolRunState(toolName) }
        s.successCount++
    }

    /**
     * Records a tool failure, classifies the error, and disables the tool
     * for the remainder of this run if the failure is TIMEOUT or BLOCKED_CONTENT.
     * Returns the classified failure type.
     */
    fun recordFailure(toolName: String, error: String): ToolFailureType {
        val type = ToolFailureType.classify(error)
        val s = states.getOrPut(toolName) { ToolRunState(toolName) }
        s.failureCount++
        s.lastFailureType = type
        if (type == ToolFailureType.TIMEOUT || type == ToolFailureType.BLOCKED_CONTENT) {
            s.disabled = true
            println("[ToolFailure] $toolName → disabled for this run (${type.name})")
        }
        return type
    }

    /**
     * Builds the re-planning message injected into LLM context after a tool failure.
     * Includes: failure type, disabled tools, and fallback suggestion if available.
     */
    fun buildReplanMessage(toolName: String, failureType: ToolFailureType, errorMsg: String): String {
        val fallback = ToolFallbackStrategy.findAvailableFallback(toolName, availableTools)
        val disabled = disabledTools
        return buildString {
            appendLine("Tool '$toolName' failed.")
            appendLine("Failure type: ${failureType.name}")
            appendLine("Error: $errorMsg")
            if (disabled.isNotEmpty()) {
                appendLine("Disabled tools for this request (do NOT call): ${disabled.joinToString(", ")}")
            }
            when {
                fallback != null ->
                    appendLine("Suggested fallback: use '$fallback' instead.")
                else ->
                    appendLine("No known fallback available. If no other tool can satisfy the request, respond with FINAL_ANSWER.")
            }
        }.trimEnd()
    }

    /**
     * Message injected when LLM attempts the exact same (tool + args) that already failed.
     */
    fun buildIdenticalRetryMessage(toolName: String): String {
        val fallback = ToolFallbackStrategy.findAvailableFallback(toolName, availableTools)
        return buildString {
            appendLine("You already tried '$toolName' with the same arguments and it failed.")
            appendLine("Do NOT call the same tool again with the same arguments.")
            if (fallback != null) {
                appendLine("Use '$fallback' instead, or choose another available tool.")
            } else {
                appendLine("No fallback is known. If you cannot complete the request, respond with FINAL_ANSWER.")
            }
        }.trimEnd()
    }

    private fun callSignature(toolName: String, args: Map<String, String>): String {
        val sortedArgs = args.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        return "$toolName|$sortedArgs"
    }
}
