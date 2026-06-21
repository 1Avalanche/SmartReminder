package smartagent.architect

import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals

class TaskRepositoryTest {

    private lateinit var tmpDir: File
    private lateinit var featureRepo: FeatureRepository
    private lateinit var taskRepo: TaskRepository
    private lateinit var feature: Feature
    private lateinit var task: Task

    @Before
    fun setup() {
        tmpDir = createTempDirectory("smartagent-test").toFile()
        featureRepo = FeatureRepository(tmpDir)
        taskRepo = TaskRepository(tmpDir)
        feature = featureRepo.createFeature("Feature")
        task = taskRepo.createTask(feature.id, "Task")
    }

    @After
    fun teardown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `updateStage allows PLANNING to EXECUTION`() {
        taskRepo.updateStage(task.id, Stage.EXECUTION)
        assertEquals(Stage.EXECUTION, taskRepo.getTask(task.id)?.stage)
    }

    @Test
    fun `updateStage rejects PLANNING to VALIDATION`() {
        taskRepo.updateStage(task.id, Stage.VALIDATION)
        assertEquals(Stage.PLANNING, taskRepo.getTask(task.id)?.stage)
    }

    @Test
    fun `updateStage rejects PLANNING to DONE`() {
        taskRepo.updateStage(task.id, Stage.DONE)
        assertEquals(Stage.PLANNING, taskRepo.getTask(task.id)?.stage)
    }

    @Test
    fun `updateStage allows EXECUTION to VALIDATION`() {
        taskRepo.updateStage(task.id, Stage.EXECUTION)
        taskRepo.updateStage(task.id, Stage.VALIDATION)
        assertEquals(Stage.VALIDATION, taskRepo.getTask(task.id)?.stage)
    }

    @Test
    fun `updateStage rejects EXECUTION to PLANNING`() {
        taskRepo.updateStage(task.id, Stage.EXECUTION)
        taskRepo.updateStage(task.id, Stage.PLANNING)
        assertEquals(Stage.EXECUTION, taskRepo.getTask(task.id)?.stage)
    }

    @Test
    fun `updateStage allows VALIDATION to EXECUTION`() {
        taskRepo.updateStage(task.id, Stage.EXECUTION)
        taskRepo.updateStage(task.id, Stage.VALIDATION)
        taskRepo.updateStage(task.id, Stage.EXECUTION)
        assertEquals(Stage.EXECUTION, taskRepo.getTask(task.id)?.stage)
    }

    @Test
    fun `updateStage rejects VALIDATION to PLANNING`() {
        taskRepo.updateStage(task.id, Stage.EXECUTION)
        taskRepo.updateStage(task.id, Stage.VALIDATION)
        taskRepo.updateStage(task.id, Stage.PLANNING)
        assertEquals(Stage.VALIDATION, taskRepo.getTask(task.id)?.stage)
    }
}
