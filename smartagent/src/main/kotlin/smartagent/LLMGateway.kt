package smartagent

interface LLMGateway {
    data class Response(val content: String, val usage: Usage? = null)
    fun chat(messages: List<Message>, model: ModelConfig, source: String = ""): Response?
}
