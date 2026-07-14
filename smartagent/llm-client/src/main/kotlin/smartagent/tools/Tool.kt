package smartagent.tools

interface Tool {
    val id: String
    val description: String
    fun execute(args: Map<String, Any>): String
}
