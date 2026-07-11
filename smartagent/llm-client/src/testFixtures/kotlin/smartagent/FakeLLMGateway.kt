package smartagent

class FakeLLMGateway(vararg responses: String) : LLMGateway {
    private val queue = ArrayDeque(responses.toList())
    val calls = mutableListOf<Triple<List<Message>, ModelConfig, String>>()

    override fun chat(messages: List<Message>, model: ModelConfig, source: String, options: OllamaOptions?): LLMGateway.Response? {
        val content = queue.removeFirstOrNull() ?: return null
        calls += Triple(messages, model, source)
        return LLMGateway.Response(content)
    }

    val callCount: Int get() = calls.size
}
