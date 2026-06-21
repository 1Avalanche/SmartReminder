package smartagent.architect

import smartagent.ChatSession
import smartagent.Colors
import smartagent.LogEntry
import smartagent.NetworkLogger
import smartagent.ProfileAgent

internal class ArchitectOrchestrator(
    private val session: ChatSession,
    private val featureRepository: FeatureRepository,
    private val taskRepository: TaskRepository,
    private val invariantAgent: InvariantAgent,
    private val intentClassifier: IntentClassifier,
    private val planningAgent: PlanningAgent,
    private val executionAgent: ExecutionAgent,
    private val validationAgent: ValidationAgent
) {
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
        val intentSpinner = AgentSpinner.start("IntentClassifier")
        val intentResult = intentClassifier.classify(userInput)
        intentSpinner.stop()

        when (intentResult?.intent) {
            UserIntent.NEW_FEATURE -> handleNewFeature(userInput)
            UserIntent.NEW_TASK -> handleNewTask(userInput)
            UserIntent.SWITCH_FEATURE -> { handleSwitchFeature(intentResult); return }
            UserIntent.TASK_UPDATE -> handleTaskUpdate(userInput, intentResult)
            else -> handleDefault(userInput)
        }

        val activeFeature = featureRepository.getActiveFeature()
        if (activeFeature == null) {
            println()
            println("${Colors.DARK_GRAY}Нет активного проекта. Опишите задачу или создайте проект: /feature create <название>${Colors.RESET}")
            println()
            return
        }

        val activeTask = taskRepository.getActiveTaskForFeature(activeFeature.id)
        if (activeTask == null) {
            println()
            println("${Colors.DARK_GRAY}Нет активной задачи. Опишите задачу в чате.${Colors.RESET}")
            println()
            return
        }

        dispatchToAgent(activeFeature, activeTask, userInput)
    }

    private fun dispatchToAgent(feature: Feature, task: Task, userInput: String) {
        when (task.stage) {
            Stage.PLANNING -> {
                var input = userInput
                var result: PlanningAgentResponse? = null
                val invariants = invariantAgent.getAllInvariants()
                for (attempt in 1..MAX_INVARIANT_RETRIES) {
                    val spinner = AgentSpinner.start("PlanningAgent", task.stage)
                    val fetched = fetchWithRetry(
                        tag = "PlanningAgent", taskId = task.id,
                        isEmpty = { it.response.isNullOrBlank() && !it.planningComplete }
                    ) { planningAgent.fetch(feature, task, input, invariants) }
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
                        input = "[INVARIANT VIOLATION] ${invResult.reason}\n\nОригинальный запрос: $userInput"
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
                } else {
                    println("${Colors.DARK_GRAY}Агент не дал ответа. Попробуйте уточнить или повторить запрос.${Colors.RESET}")
                }
                println()
                if (result.planningComplete) {
                    taskRepository.updateStage(task.id, Stage.EXECUTION)
                    NetworkLogger.logEvent("[ArchitectOrchestrator]", "PLANNING → EXECUTION: ${task.id} | ${task.title}")
                    val next = taskRepository.getTask(task.id) ?: return
                    dispatchToAgent(feature, next, AUTO_EXEC)
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
                    dispatchToAgent(feature, next, AUTO_VALID)
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
                        taskRepository.updateStage(task.id, Stage.EXECUTION)
                        NetworkLogger.logEvent("[ArchitectOrchestrator]", "VALIDATION → EXECUTION: ${task.id} | ${task.title} | reason: ${fetched.currentStep}")
                        val next = taskRepository.getTask(task.id) ?: return
                        val feedback = "[VALIDATION FEEDBACK]\n${fetched.review.orEmpty()}\n\nУстрани все замечания из review выше."
                        dispatchToAgent(feature, next, feedback)
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

    private fun handleNewFeature(userInput: String) {
        val feature = featureRepository.createFeature(userInput)
        val task = taskRepository.createTask(feature.id, userInput)
        NetworkLogger.logEvent("[ArchitectOrchestrator]", "NEW_FEATURE: ${feature.id} | ${feature.title}")
        NetworkLogger.logEvent("[ArchitectOrchestrator]", "NEW_TASK: ${task.id} | featureId=${feature.id}")
        taskRepository.appendHistory(task.id, userInput)
    }

    private fun handleNewTask(userInput: String) {
        val feature = featureRepository.getActiveFeature() ?: return
        val task = taskRepository.createTask(feature.id, userInput)
        NetworkLogger.logEvent("[ArchitectOrchestrator]", "NEW_TASK: ${task.id} | featureId=${feature.id} | ${task.title}")
        taskRepository.appendHistory(task.id, userInput)
    }

    private fun handleTaskUpdate(userInput: String, intentResult: IntentResult) {
        val feature = featureRepository.getActiveFeature() ?: return
        val targetTaskId = intentResult.taskId
        if (targetTaskId != null) {
            val target = taskRepository.getTask(targetTaskId)
            if (target != null && target.featureId == feature.id) {
                val current = taskRepository.getActiveTaskForFeature(feature.id)
                if (current?.id != targetTaskId) {
                    taskRepository.activateTask(targetTaskId)
                    NetworkLogger.logEvent("[ArchitectOrchestrator]", "TASK_SWITCH: ${current?.id ?: "none"} → $targetTaskId")
                }
            }
        }
        val activeTask = taskRepository.getActiveTaskForFeature(feature.id) ?: return
        taskRepository.appendHistory(activeTask.id, userInput)
    }

    private fun handleSwitchFeature(intentResult: IntentResult) {
        val targetId = intentResult.featureId ?: return
        val prevActive = featureRepository.getActiveFeature()

        if (prevActive != null) {
            val prevTask = taskRepository.getActiveTaskForFeature(prevActive.id)
            if (prevTask != null) {
                taskRepository.pauseTask(prevTask.id)
                NetworkLogger.logEvent("[ArchitectOrchestrator]", "PAUSE_TASK: ${prevTask.id} | ${prevTask.title}")
            }
        }

        featureRepository.setActiveFeature(targetId)
        val newActive = featureRepository.getActiveFeature() ?: return

        NetworkLogger.logEvent(
            source = "[ArchitectOrchestrator]",
            message = "SWITCH_FEATURE: ${prevActive?.id ?: "none"} → ${newActive.id} | ${newActive.title}"
        )

        println()
        println("${Colors.LIGHT_YELLOW}Переключились на: ${newActive.title}${Colors.RESET}")

        val pausedTask = taskRepository.getTasksForFeature(newActive.id)
            .filter { it.status == TaskStatus.PAUSED }
            .maxByOrNull { it.updatedAt }

        if (pausedTask != null) {
            taskRepository.activateTask(pausedTask.id)
            NetworkLogger.logEvent("[ArchitectOrchestrator]", "RESUME_TASK: ${pausedTask.id} | ${pausedTask.title}")
            println("${Colors.LIGHT_VIOLET}Вернемся к задаче: ${pausedTask.title}${Colors.RESET}")
            val resumeSpinner = AgentSpinner.startResume(pausedTask.stage)
            Thread.sleep(1500)
            resumeSpinner.stop()
        } else {
            val activeTask = taskRepository.getActiveTaskForFeature(newActive.id)
            if (activeTask != null) {
                println("${Colors.DARK_GRAY}Продолжаем: ${activeTask.title} | ${activeTask.stage.displayName()}${Colors.RESET}")
            }
        }
        println()
    }

    private fun handleDefault(userInput: String) {
        val feature = featureRepository.getActiveFeature() ?: return
        val task = taskRepository.getActiveTaskForFeature(feature.id) ?: return
        taskRepository.appendHistory(task.id, userInput)
    }

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
        private const val MAX_EXEC_ATTEMPTS = 5
        private const val MAX_LLM_RETRIES = 3
    }
}
