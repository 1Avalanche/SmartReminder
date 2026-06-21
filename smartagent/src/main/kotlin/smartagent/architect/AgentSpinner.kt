package smartagent.architect

import smartagent.Colors
import smartagent.Spinner

internal object AgentSpinner {
    private val agentMessages = mapOf(
        "IntentClassifier" to "Размышляю...",
        "PlanningAgent" to "Планирую...",
        "ExecutionAgent" to "Выполняю...",
        "ValidationAgent" to "Проверяю результат...",
        "ArchitectClient" to "Обдумываю ответ...",
        "SummaryAgent" to "Сжимаю контекст...",
        "ProfileAgent" to "Обновляю профиль..."
    )

    private val stageMessages = mapOf(
        Stage.PLANNING to "Собираю требования...",
        Stage.EXECUTION to "Проектирую систему...",
        Stage.VALIDATION to "Проверяю архитектуру..."
    )

    fun start(agentName: String, stage: Stage? = null): Spinner {
        val msg = agentMessages[agentName]
            ?: stage?.let { stageMessages[it] }
            ?: "Размышляю..."
        return Spinner("${Colors.DARK_GRAY}$msg${Colors.RESET}")
    }

    fun startResume(stage: Stage): Spinner {
        val msg = stageMessages[stage] ?: "Восстанавливаю контекст..."
        return Spinner("${Colors.DARK_GRAY}$msg${Colors.RESET}")
    }
}
