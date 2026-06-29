package smartagent.architect

import smartagent.ChatSession
import smartagent.Colors
import smartagent.LLMGateway
import smartagent.LogEntry
import smartagent.Message
import smartagent.NetworkLogger
import smartagent.ProfileAgent
import smartagent.SessionConfig
import smartagent.TokenTracker
import smartagent.json
import java.io.File

internal class ArchitectOrchestrator(
    private val session: ChatSession,
    private val featureRepository: FeatureRepository,
    private val taskRepository: TaskRepository,
    private val invariantAgent: InvariantAgent,
    private val planningAgent: PlanningAgent,
    private val executionAgent: ExecutionAgent,
    private val validationAgent: ValidationAgent,
    private val gateway: LLMGateway,
    private val config: SessionConfig,
    private val tokens: TokenTracker
) {
    private val promptDir: File = listOf(
        "smartagent/src/main/kotlin/prompts/architect",
        "src/main/kotlin/prompts/architect",
        "prompts/architect"
    ).map(::File).firstOrNull { it.isDirectory } ?: File("smartagent/src/main/kotlin/prompts/architect")

    fun process(userInput: String) {
        val invSpinner = AgentSpinner.start("InvariantAgent")
        val invariantResult = invariantAgent.check(userInput)
        invSpinner.stop()

        when (invariantResult.status) {
            InvariantStatus.INVALID -> {
                println()
                println("${Colors.LIGHT_YELLOW}Запрос отклонён: ${invariantResult.reason}${Colors.RESET}")
                println()
                val activeTask = featureRepository.getActiveFeature()
                    ?.let { taskRepository.getActiveTaskForFeature(it.id) }
                if (activeTask != null) {
                    taskRepository.appendHistory(activeTask.id, userInput, role = "User")
                    taskRepository.appendHistory(activeTask.id, "Запрос отклонён: ${invariantResult.reason}", role = "InvariantAgent")
                }
                return
            }
            InvariantStatus.NEW_INVARIANT -> {
                invariantAgent.saveUserInvariant(invariantResult.invariant)
                println()
                println("${Colors.LIGHT_GREEN}Инвариант зафиксирован: ${invariantResult.invariant}${Colors.RESET}")
                println()
            }
            InvariantStatus.VALID -> Unit
        }

        try {
            route(userInput)
        } finally {
            session.addLogEntry(LogEntry(userInput, "", ""))
            if (session.shouldTriggerProfile()) Thread {
                ProfileAgent(session).update()
            }.apply { isDaemon = true }.start()
        }
    }

    private fun route(userInput: String) {
        val thinkSpinner = AgentSpinner.start("ArchitectOrchestrator")
        val thought = think(userInput)
        thinkSpinner.stop()

        if (thought == null) {
            println()
            println("${Colors.LIGHT_YELLOW}Не удалось получить ответ. Попробуйте повторить.${Colors.RESET}")
            println()
            return
        }

        // Suppress arch response during active PLANNING dialogue — PlanningAgent owns that conversation
        val activeFeature = featureRepository.getActiveFeature()
        val activeTask = activeFeature?.let { taskRepository.getActiveTaskForFeature(it.id) }
        val silentRoute = thought.action == ArchitectAction.UPDATE_TASK && activeTask?.stage == Stage.PLANNING

        if (!silentRoute) {
            println()
            println("${Colors.LIGHT_VIOLET}${thought.response}${Colors.RESET}")
            println()
        }

        when (thought.action) {
            ArchitectAction.ANSWER -> Unit
            ArchitectAction.CREATE_TASK -> handleCreateTask(thought)
            ArchitectAction.UPDATE_TASK -> handleUpdateTask(thought, userInput)
            ArchitectAction.SWITCH_TASK -> handleSwitchTask(thought)
        }
    }

    private fun think(userInput: String): ArchitectThought? {
        val messages = listOf(
            Message("system", loadSystemPrompt()),
            Message("user", buildThinkingContext(userInput))
        )
        val response = gateway.chat(messages, config.currentModel, "[ArchitectOrchestrator]") ?: return null
        response.usage?.let { tokens.addTokenEntry(it) }
        return parseThought(response.content)
    }

    private fun buildThinkingContext(userInput: String): String = buildString {
        val feature = featureRepository.getActiveFeature()
        if (feature != null) {
            appendLine("ACTIVE FEATURE")
            appendLine("id: ${feature.id} | title: ${feature.title}")
            if (feature.summary.isNotBlank()) appendLine("summary: ${feature.summary}")
            appendLine()

            val tasks = taskRepository.getTasksForFeature(feature.id)
                .filter { it.status != TaskStatus.COMPLETED }
            if (tasks.isNotEmpty()) {
                appendLine("OPEN TASKS")
                tasks.forEach { t ->
                    val marker = if (t.status == TaskStatus.ACTIVE) " [ACTIVE]" else ""
                    appendLine("${t.id} | ${t.title}$marker | stage: ${t.stage}")
                    if (t.summary.isNotBlank()) appendLine("  summary: ${t.summary}")
                }
                appendLine()
            } else {
                appendLine("OPEN TASKS: нет")
                appendLine()
            }

            val activeTask = taskRepository.getActiveTaskForFeature(feature.id)
            if (activeTask != null) {
                val history = taskRepository.getHistory(activeTask.id)
                if (history.isNotBlank()) {
                    appendLine("ACTIVE TASK HISTORY")
                    appendLine(history)
                    appendLine()
                }
            }
        } else {
            appendLine("ACTIVE FEATURE: none")
            appendLine()
        }

        val archSettings = loadArchSettings()
        if (archSettings.isNotBlank()) {
            appendLine("ARCH SETTINGS")
            appendLine(archSettings)
            appendLine()
        }

        val invariants = invariantAgent.getAllInvariants()
        if (invariants.isNotBlank()) {
            appendLine("INVARIANTS")
            appendLine(invariants)
            appendLine()
        }

        val profile = loadUserProfile()
        if (profile.isNotBlank()) {
            appendLine("USER PROFILE")
            appendLine(profile)
            appendLine()
        }

        appendLine("USER MESSAGE")
        appendLine()
        append(userInput)
    }.trimEnd()

    private fun handleCreateTask(thought: ArchitectThought) {
        val feature = featureRepository.getActiveFeature()
            ?: featureRepository.createFeature(thought.taskTitle ?: "Новый проект")

        val task = taskRepository.createTask(feature.id, thought.taskTitle ?: "Новая задача")

        thought.taskDescription?.takeIf { it.isNotBlank() }?.let { desc ->
            taskRepository.updateTask(task.copy(summary = desc))
        }

        taskRepository.appendHistory(task.id, thought.response, role = "ArchitectOrchestrator")
        NetworkLogger.logEvent("[ArchitectOrchestrator]", "CREATE_TASK: ${task.id} | featureId=${feature.id} | ${task.title}")

        val freshTask = taskRepository.getTask(task.id) ?: return
        val planningContext = PlanningContext(
            taskTitle = freshTask.title,
            taskDescription = thought.taskDescription ?: freshTask.title,
            additionalContext = thought.additionalContext,
            history = taskRepository.getHistory(freshTask.id),
            featureSummary = feature.summary
        )

        dispatchToAgent(feature, freshTask, planningContext = planningContext)
    }

    private fun handleUpdateTask(thought: ArchitectThought, userInput: String) {
        val feature = featureRepository.getActiveFeature() ?: run {
            println()
            println("${Colors.DARK_GRAY}Нет активного проекта. Опишите задачу или создайте проект: /feature create <название>${Colors.RESET}")
            println()
            return
        }
        val task = taskRepository.getActiveTaskForFeature(feature.id) ?: run {
            println()
            println("${Colors.DARK_GRAY}Нет активной задачи. Опишите задачу в чате.${Colors.RESET}")
            println()
            return
        }

        taskRepository.appendHistory(task.id, userInput, role = "User")

        val inPlanning = task.stage == Stage.PLANNING
        // Don't add arch response to history during PLANNING — keeps PlanningAgent's Q&A clean
        if (!inPlanning) {
            taskRepository.appendHistory(task.id, thought.response, role = "ArchitectOrchestrator")
        }

        val freshTask = taskRepository.getTask(task.id) ?: return
        val planningContext = PlanningContext(
            taskTitle = freshTask.title,
            // During PLANNING: use PlanningAgent's accumulated summary, not arch's re-interpretation
            taskDescription = if (inPlanning) freshTask.summary
                              else (thought.taskDescription?.takeIf { it.isNotBlank() } ?: freshTask.summary),
            additionalContext = if (inPlanning) null else thought.additionalContext,
            history = taskRepository.getHistory(freshTask.id),
            featureSummary = feature.summary
        )

        dispatchToAgent(feature, freshTask, userInput = userInput, planningContext = planningContext)
    }

    private fun handleSwitchTask(thought: ArchitectThought) {
        val feature = featureRepository.getActiveFeature() ?: return
        val targetTitle = thought.taskTitle ?: return

        val target = taskRepository.getTasksForFeature(feature.id)
            .filter { it.status != TaskStatus.COMPLETED }
            .firstOrNull { it.title.equals(targetTitle, ignoreCase = true) }
            ?: taskRepository.getTasksForFeature(feature.id)
                .filter { it.status != TaskStatus.COMPLETED }
                .firstOrNull { it.title.contains(targetTitle, ignoreCase = true) }

        if (target == null) {
            println()
            println("${Colors.LIGHT_YELLOW}Задача не найдена: $targetTitle${Colors.RESET}")
            println()
            return
        }

        val current = taskRepository.getActiveTaskForFeature(feature.id)
        if (current?.id != target.id) {
            current?.let { taskRepository.pauseTask(it.id) }
            taskRepository.activateTask(target.id)
            NetworkLogger.logEvent("[ArchitectOrchestrator]", "SWITCH_TASK: ${current?.id ?: "none"} → ${target.id} | ${target.title}")
            println()
            println("${Colors.LIGHT_YELLOW}Переключились на задачу: ${target.title} | ${target.stage.displayName()}${Colors.RESET}")
            println()
        }
    }

    private fun dispatchToAgent(
        feature: Feature,
        task: Task,
        userInput: String = "",
        planningContext: PlanningContext? = null,
        validationRounds: Int = MAX_VALIDATION_ROUNDS
    ) {
        when (task.stage) {
            Stage.PLANNING -> {
                val ctx = planningContext ?: PlanningContext(
                    taskTitle = task.title,
                    taskDescription = task.summary,
                    history = taskRepository.getHistory(task.id),
                    featureSummary = feature.summary
                )
                var currentCtx = ctx
                var result: PlanningAgentResponse? = null
                val invariants = invariantAgent.getAllInvariants()
                for (attempt in 1..MAX_INVARIANT_RETRIES) {
                    val spinner = AgentSpinner.start("PlanningAgent", task.stage)
                    val fetched = fetchWithRetry(
                        tag = "PlanningAgent", taskId = task.id,
                        isEmpty = { it.response.isNullOrBlank() && !it.planningComplete }
                    ) { planningAgent.fetch(feature, task, currentCtx, invariants) }
                    spinner.stop()
                    if (fetched == null) {
                        println()
                        println("${Colors.LIGHT_YELLOW}Не удалось получить ответ от агента. Попробуйте повторить.${Colors.RESET}")
                        println()
                        return
                    }

                    val invSpinner = AgentSpinner.start("InvariantAgent")
                    val invResult = invariantAgent.check(fetched.response.orEmpty())
                    invSpinner.stop()

                    if (invResult.status == InvariantStatus.INVALID) {
                        if (attempt == MAX_INVARIANT_RETRIES) {
                            println()
                            println("${Colors.LIGHT_YELLOW}Извините, не получилось обработать ваш запрос с учётом существующих ограничений. Попробуйте изменить запрос.${Colors.RESET}")
                            println()
                            return
                        }
                        val violation = "[INVARIANT VIOLATION] ${invResult.reason}"
                        val prevCtx = currentCtx.additionalContext
                        currentCtx = currentCtx.copy(
                            additionalContext = if (prevCtx.isNullOrBlank()) violation else "$violation\n\n$prevCtx"
                        )
                    } else {
                        planningAgent.apply(task, fetched)
                        result = fetched
                        break
                    }
                }
                result ?: return
                println()
                if (!result.response.isNullOrBlank()) {
                    println("${Colors.LIGHT_VIOLET}${result.response}${Colors.RESET}")
                } else if (!result.planningComplete) {
                    println("${Colors.DARK_GRAY}Агент не дал ответа. Попробуйте уточнить или повторить запрос.${Colors.RESET}")
                }
                println()
                if (result.planningComplete) {
                    if (result.plan.isNullOrBlank()) {
                        println()
                        println("${Colors.DARK_GRAY}Планирование не завершено — план не создан. Уточните детали.${Colors.RESET}")
                        println()
                    } else {
                        taskRepository.updateStage(task.id, Stage.EXECUTION)
                        NetworkLogger.logEvent("[ArchitectOrchestrator]", "PLANNING → EXECUTION: ${task.id} | ${task.title}")
                        val next = taskRepository.getTask(task.id) ?: return
                        dispatchToAgent(feature, next, AUTO_EXEC)
                    }
                }
            }
            Stage.EXECUTION -> {
                val invariants = invariantAgent.getAllInvariants()
                var execInput = userInput
                var executionDone = false

                for (execAttempt in 1..MAX_EXEC_ATTEMPTS) {
                    var result: ExecutionAgentResponse? = null
                    var invariantInput = execInput

                    for (attempt in 1..MAX_INVARIANT_RETRIES) {
                        val spinner = AgentSpinner.start("ExecutionAgent", task.stage)
                        val fetched = fetchWithRetry(
                            tag = "ExecutionAgent", taskId = task.id,
                            isEmpty = { it.artifact.isNullOrBlank() }
                        ) { executionAgent.fetch(feature, task, invariantInput, invariants) }
                        spinner.stop()
                        if (fetched == null) {
                            println()
                            println("${Colors.LIGHT_YELLOW}Не удалось получить ответ от агента. Попробуйте повторить.${Colors.RESET}")
                            println()
                            return
                        }

                        val invSpinner = AgentSpinner.start("InvariantAgent")
                        val invResult = invariantAgent.check(fetched.artifact.orEmpty().ifBlank { fetched.response.orEmpty() })
                        invSpinner.stop()

                        if (invResult.status == InvariantStatus.INVALID) {
                            if (attempt == MAX_INVARIANT_RETRIES) {
                                println()
                                println("${Colors.LIGHT_YELLOW}Извините, не получилось обработать ваш запрос с учётом существующих ограничений. Попробуйте изменить запрос.${Colors.RESET}")
                                println()
                                return
                            }
                            invariantInput = "[INVARIANT VIOLATION] ${invResult.reason}\n\nОригинальный запрос: $execInput"
                        } else {
                            executionAgent.apply(task, fetched)
                            result = fetched
                            break
                        }
                    }

                    result ?: return

                    if (result.executionComplete) {
                        executionDone = true
                        break
                    }

                    NetworkLogger.logEvent("[ArchitectOrchestrator]", "EXECUTION attempt $execAttempt: not complete, retrying: ${task.id}")
                    execInput = AUTO_EXEC
                }

                if (executionDone) {
                    taskRepository.updateStage(task.id, Stage.VALIDATION)
                    NetworkLogger.logEvent("[ArchitectOrchestrator]", "EXECUTION → VALIDATION: ${task.id} | ${task.title}")
                    val next = taskRepository.getTask(task.id) ?: return
                    dispatchToAgent(feature, next, AUTO_VALID, validationRounds = validationRounds)
                } else {
                    NetworkLogger.logEvent("[ArchitectOrchestrator]", "EXECUTION: max attempts ($MAX_EXEC_ATTEMPTS) reached without completion: ${task.id}")
                }
            }
            Stage.VALIDATION -> {
                val invariants = invariantAgent.getAllInvariants()
                val spinner = AgentSpinner.start("ValidationAgent", task.stage)
                val fetched = fetchWithRetry(
                    tag = "ValidationAgent", taskId = task.id
                ) { validationAgent.fetch(feature, task, userInput, invariants) }
                spinner.stop()
                if (fetched == null) {
                    println()
                    println("${Colors.LIGHT_YELLOW}Не удалось получить ответ от агента. Попробуйте повторить.${Colors.RESET}")
                    println()
                    return
                }
                validationAgent.apply(task, fetched)
                when {
                    fetched.validationPassed -> {
                        taskRepository.completeTask(task.id)
                        NetworkLogger.logEvent("[ArchitectOrchestrator]", "VALIDATION → DONE: ${task.id} | ${task.title} (feature ${task.featureId} stays ACTIVE)")
                        val artifact = taskRepository.getArchitecture(task.id)
                        println()
                        if (artifact.isNotBlank()) {
                            println("${Colors.LIGHT_VIOLET}${artifact}${Colors.RESET}")
                            println()
                        }
                        println("${Colors.LIGHT_GREEN}Готово. Можете описать следующую задачу или продолжить проект.${Colors.RESET}")
                        println()
                    }
                    fetched.returnToExecution -> {
                        if (validationRounds <= 0) {
                            NetworkLogger.logEvent("[ArchitectOrchestrator]", "VALIDATION → ABORT (max rounds): ${task.id} | ${task.title}")
                            println()
                            println("${Colors.LIGHT_YELLOW}Достигнут лимит итераций проверки. Задача сохранена в текущем состоянии.${Colors.RESET}")
                            println()
                            return
                        }
                        taskRepository.updateStage(task.id, Stage.EXECUTION)
                        NetworkLogger.logEvent("[ArchitectOrchestrator]", "VALIDATION → EXECUTION: ${task.id} | ${task.title} | reason: ${fetched.currentStep}")
                        val next = taskRepository.getTask(task.id) ?: return
                        val feedback = "[VALIDATION FEEDBACK]\n${fetched.review.orEmpty()}\n\nУстрани все замечания из review выше."
                        dispatchToAgent(feature, next, feedback, validationRounds = validationRounds - 1)
                    }
                }
            }
            else -> {
                println()
                println("${Colors.DARK_GRAY}Задача завершена или приостановлена. Опишите следующую задачу.${Colors.RESET}")
                println()
            }
        }
    }

    private fun parseThought(raw: String): ArchitectThought? {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        runCatching { json.decodeFromString<ArchitectThought>(trimmed) }.getOrNull()?.let { return it }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start >= 0 && end > start) {
            runCatching { json.decodeFromString<ArchitectThought>(raw.substring(start, end + 1)) }
                .getOrNull()?.let { return it }
        }
        return null
    }

    private fun loadSystemPrompt(): String =
        runCatching { File(promptDir, "architect_orchestrator.txt").readText() }
            .getOrElse { FALLBACK_SYSTEM_PROMPT }

    private fun loadArchSettings(): String =
        listOf("smartagent/arch_settings.md", "arch_settings.md")
            .map(::File)
            .firstOrNull { it.exists() }
            ?.runCatching { readText().trim() }
            ?.getOrNull() ?: ""

    private fun loadUserProfile(): String =
        listOf("smartagent/user_profile.md", "user_profile.md", "cli/user_profile.md")
            .map(::File)
            .firstOrNull { it.exists() }
            ?.runCatching { readText().trim() }
            ?.getOrNull() ?: ""

    private fun <T> fetchWithRetry(
        tag: String,
        taskId: String,
        isEmpty: (T) -> Boolean = { false },
        fetch: () -> T?
    ): T? {
        for (attempt in 1..MAX_LLM_RETRIES) {
            val result = fetch()
            if (result != null && !isEmpty(result)) return result
            val reason = if (result == null) "null response" else "empty content"
            NetworkLogger.logEvent("[ArchitectOrchestrator]", "$tag LLM $reason, retry $attempt/$MAX_LLM_RETRIES: $taskId")
        }
        return null
    }

    companion object {
        private const val AUTO_EXEC = "Начни проектирование на основе утверждённого плана."
        private const val AUTO_VALID = "Проверь созданную архитектуру на полноту и корректность."
        private const val MAX_INVARIANT_RETRIES = 3
        private const val MAX_VALIDATION_ROUNDS = 3
        private const val MAX_EXEC_ATTEMPTS = 5
        private const val MAX_LLM_RETRIES = 3
    }
}

private val FALLBACK_SYSTEM_PROMPT = """
Ты — главный архитектор проекта. Осмысли намерение пользователя и верни ТОЛЬКО JSON:
{"response":"...","action":"ANSWER|CREATE_TASK|UPDATE_TASK|SWITCH_TASK","taskTitle":null,"taskDescription":null,"additionalContext":null}
""".trimIndent()
