package smartagent

class SessionConfig {
    var currentModel: ModelConfig = ModelConfig.DEEPSEEK
        private set
    var currentMode: AgentMode = AgentMode.CHAT
        internal set
    var repoContext: RepoContext? = null

    fun switchModel(model: ModelConfig) {
        currentModel = model
    }
}
