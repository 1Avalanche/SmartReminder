package smartagent.architect

import smartagent.Colors
import smartagent.NetworkLogger

internal class FeatureOrchestrator(
    private val featureRepository: FeatureRepository,
    private val taskRepository: TaskRepository,
    private val intentClassifier: IntentClassifier,
    private val planningAgent: PlanningAgent,
    private val executionAgent: ExecutionAgent,
    private val validationAgent: ValidationAgent
) {
    fun process(userInput: String): Boolean {
        val intentSpinner = AgentSpinner.start("IntentClassifier")
        val intentResult = intentClassifier.classify(userInput)
        intentSpinner.stop()

        when (intentResult?.intent) {
            UserIntent.NEW_FEATURE -> handleNewFeature(userInput)
            UserIntent.NEW_TASK -> handleNewTask(userInput)
            UserIntent.SWITCH_FEATURE -> return handleSwitchFeature(intentResult)
            UserIntent.TASK_UPDATE -> handleTaskUpdate(userInput, intentResult)
            else -> handleDefault(userInput)
        }

        val activeFeature = featureRepository.getActiveFeature() ?: return true
        val activeTask = taskRepository.getActiveTaskForFeature(activeFeature.id) ?: return true

        return dispatchToAgent(activeFeature, activeTask, userInput)
    }

    private fun dispatchToAgent(feature: Feature, task: Task, userInput: String): Boolean {
        return when (task.stage) {
            Stage.PLANNING -> {
                val spinner = AgentSpinner.start("PlanningAgent", task.stage)
                val agentResponse = planningAgent.run(feature, task, userInput)
                spinner.stop()
                agentResponse ?: return true
                if (agentResponse.response.isNotBlank()) {
                    println()
                    println("${Colors.LIGHT_VIOLET}${agentResponse.response}${Colors.RESET}")
                    println()
                }
                if (agentResponse.planningComplete) {
                    val next = taskRepository.getTask(task.id) ?: return false
                    return dispatchToAgent(feature, next, AUTO_EXEC)
                }
                false
            }
            Stage.EXECUTION -> {
                val spinner = AgentSpinner.start("ExecutionAgent", task.stage)
                val agentResponse = executionAgent.run(feature, task, userInput)
                spinner.stop()
                agentResponse ?: return true
                if (agentResponse.response.isNotBlank()) {
                    println()
                    println("${Colors.LIGHT_VIOLET}${agentResponse.response}${Colors.RESET}")
                    println()
                }
                if (agentResponse.executionComplete) {
                    val next = taskRepository.getTask(task.id) ?: return false
                    return dispatchToAgent(feature, next, AUTO_VALID)
                }
                false
            }
            Stage.VALIDATION -> {
                val spinner = AgentSpinner.start("ValidationAgent", task.stage)
                val agentResponse = validationAgent.run(feature, task, userInput)
                spinner.stop()
                agentResponse ?: return true
                if (agentResponse.response.isNotBlank()) {
                    println()
                    println("${Colors.LIGHT_VIOLET}${agentResponse.response}${Colors.RESET}")
                    println()
                }
                if (agentResponse.validationPassed) {
                    println("${Colors.LIGHT_GREEN}Готово. Можете описать следующую задачу или продолжить проект.${Colors.RESET}")
                    println()
                }
                false
            }
            else -> true
        }
    }

    companion object {
        private const val AUTO_EXEC = "Начни проектирование на основе утверждённого плана."
        private const val AUTO_VALID = "Проверь созданную архитектуру на полноту и корректность."
    }

    private fun handleNewFeature(userInput: String) {
        val feature = featureRepository.createFeature(userInput)
        val task = taskRepository.createTask(feature.id, userInput)
        NetworkLogger.logEvent("[FSM]", "NEW_FEATURE: ${feature.id} | ${feature.title}")
        NetworkLogger.logEvent("[FSM]", "NEW_TASK: ${task.id} | featureId=${feature.id}")
        taskRepository.appendHistory(task.id, userInput)
    }

    private fun handleNewTask(userInput: String) {
        val feature = featureRepository.getActiveFeature() ?: return
        // createTask automatically pauses previous active task
        val task = taskRepository.createTask(feature.id, userInput)
        NetworkLogger.logEvent("[FSM]", "NEW_TASK: ${task.id} | featureId=${feature.id} | ${task.title}")
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
                    NetworkLogger.logEvent("[FSM]", "TASK_SWITCH: ${current?.id ?: "none"} → $targetTaskId")
                }
            }
        }
        val activeTask = taskRepository.getActiveTaskForFeature(feature.id) ?: return
        taskRepository.appendHistory(activeTask.id, userInput)
    }

    private fun handleSwitchFeature(intentResult: IntentResult): Boolean {
        val targetId = intentResult.featureId ?: return true
        val prevActive = featureRepository.getActiveFeature()

        if (prevActive != null) {
            val prevTask = taskRepository.getActiveTaskForFeature(prevActive.id)
            if (prevTask != null) {
                taskRepository.pauseTask(prevTask.id)
                NetworkLogger.logEvent("[FSM]", "PAUSE_TASK: ${prevTask.id} | ${prevTask.title}")
            }
        }

        featureRepository.setActiveFeature(targetId)
        val newActive = featureRepository.getActiveFeature() ?: return true

        NetworkLogger.logEvent(
            source = "[FSM]",
            message = "SWITCH_FEATURE: ${prevActive?.id ?: "none"} → ${newActive.id} | ${newActive.title}"
        )

        println()
        println("${Colors.LIGHT_YELLOW}Переключились на: ${newActive.title}${Colors.RESET}")

        val pausedTask = taskRepository.getTasksForFeature(newActive.id)
            .filter { it.status == TaskStatus.PAUSED }
            .maxByOrNull { it.updatedAt }

        if (pausedTask != null) {
            taskRepository.activateTask(pausedTask.id)
            NetworkLogger.logEvent("[FSM]", "RESUME_TASK: ${pausedTask.id} | ${pausedTask.title}")
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
        return true
    }

    private fun handleDefault(userInput: String) {
        val feature = featureRepository.getActiveFeature() ?: return
        val task = taskRepository.getActiveTaskForFeature(feature.id) ?: return
        taskRepository.appendHistory(task.id, userInput)
    }
}
