package smartagent.architect

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val taskJson = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

internal class TaskRepository {
    private val baseDir: File = if (File("smartagent").isDirectory) File("smartagent") else File(".")
    private val tasksDir: File = File(baseDir, "architect/tasks")

    init {
        tasksDir.mkdirs()
    }

    fun createTask(featureId: String, title: String): Task {
        // Pause current active task for this feature before creating new one
        getActiveTaskForFeature(featureId)?.let { pauseTask(it.id) }

        val id = generateId()
        val now = nowTimestamp()
        val task = Task(
            id = id,
            featureId = featureId,
            title = title,
            status = TaskStatus.ACTIVE,
            stage = Stage.PLANNING,
            currentStep = "Сбор требований",
            expectedAction = "Описать требования",
            createdAt = now,
            updatedAt = now
        )
        saveTask(task)
        return task
    }

    fun getTask(id: String): Task? {
        val file = File(tasksDir, "$id.json")
        if (!file.exists()) return null
        return runCatching { taskJson.decodeFromString<Task>(file.readText()) }.getOrNull()
    }

    fun getAllTasks(): List<Task> {
        if (!tasksDir.exists()) return emptyList()
        return tasksDir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f -> runCatching { taskJson.decodeFromString<Task>(f.readText()) }.getOrNull() }
            ?.sortedBy { it.createdAt }
            ?: emptyList()
    }

    fun getTasksForFeature(featureId: String): List<Task> =
        getAllTasks().filter { it.featureId == featureId }

    fun getActiveTaskForFeature(featureId: String): Task? =
        getTasksForFeature(featureId).firstOrNull { it.status == TaskStatus.ACTIVE }

    fun activateTask(taskId: String) {
        val task = getTask(taskId) ?: return
        getActiveTaskForFeature(task.featureId)?.let {
            if (it.id != taskId) pauseTask(it.id)
        }
        saveTask(task.copy(status = TaskStatus.ACTIVE, updatedAt = nowTimestamp()))
    }

    fun pauseTask(taskId: String) {
        val task = getTask(taskId) ?: return
        saveTask(task.copy(status = TaskStatus.PAUSED, updatedAt = nowTimestamp()))
    }

    fun updateTask(task: Task) {
        saveTask(task)
    }

    fun updateStage(taskId: String, stage: Stage) {
        val task = getTask(taskId) ?: return
        saveTask(task.copy(stage = stage, updatedAt = nowTimestamp()))
    }

    fun updateCurrentStep(taskId: String, currentStep: String?, expectedAction: String?) {
        val task = getTask(taskId) ?: return
        saveTask(task.copy(currentStep = currentStep, expectedAction = expectedAction, updatedAt = nowTimestamp()))
    }

    fun completeTask(taskId: String) {
        val task = getTask(taskId) ?: return
        saveTask(task.copy(status = TaskStatus.COMPLETED, stage = Stage.DONE, updatedAt = nowTimestamp()))
    }

    fun appendHistory(taskId: String, message: String, role: String = "User") {
        val file = File(tasksDir, "$taskId-history.md")
        file.appendText("## $role\n\n$message\n\n")
    }

    fun getHistory(taskId: String): String {
        val file = File(tasksDir, "$taskId-history.md")
        return if (file.exists()) file.readText().trim() else ""
    }

    fun savePlan(taskId: String, content: String) {
        File(tasksDir, "$taskId-plan.md").writeText(content)
    }

    fun getPlan(taskId: String): String {
        val file = File(tasksDir, "$taskId-plan.md")
        return if (file.exists()) file.readText().trim() else ""
    }

    fun saveArchitecture(taskId: String, content: String) {
        File(tasksDir, "$taskId-architecture.md").writeText(content)
    }

    fun getArchitecture(taskId: String): String {
        val file = File(tasksDir, "$taskId-architecture.md")
        return if (file.exists()) file.readText().trim() else ""
    }

    fun saveReview(taskId: String, content: String) {
        File(tasksDir, "$taskId-review.md").writeText(content)
    }

    fun getReview(taskId: String): String {
        val file = File(tasksDir, "$taskId-review.md")
        return if (file.exists()) file.readText().trim() else ""
    }

    fun clearAll() {
        tasksDir.listFiles()?.forEach { it.delete() }
    }

    private fun saveTask(task: Task) {
        tasksDir.mkdirs()
        File(tasksDir, "${task.id}.json").writeText(taskJson.encodeToString(task))
    }

    private fun generateId(): String {
        val existing = getAllTasks()
        val maxNum = existing.mapNotNull { it.id.removePrefix("task-").toIntOrNull() }.maxOrNull() ?: 0
        return "task-%03d".format(maxNum + 1)
    }

    private fun nowTimestamp(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}
