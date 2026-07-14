package smartagent.tools

object ToolRegistry {

    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.id] = tool
    }

    fun get(id: String): Tool = tools[id] ?: error("Tool not found: $id")

    fun has(id: String): Boolean = tools.containsKey(id)

    fun getAll(): List<Tool> = tools.values.toList()

    fun clear() {
        tools.clear()
    }
}
