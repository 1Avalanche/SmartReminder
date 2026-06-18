package smartagent.architect

import org.junit.After
import org.junit.Before
import org.junit.Test
import smartagent.FakeLLMGateway
import smartagent.SessionConfig
import smartagent.TokenTracker
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeatureOrchestratorTest {

    private lateinit var tmpDir: File
    private lateinit var featureRepo: FeatureRepository
    private lateinit var taskRepo: TaskRepository
    private val config = SessionConfig()
    private lateinit var tokens: TokenTracker

    @Before
    fun setup() {
        tmpDir = createTempDirectory("smartagent-test").toFile()
        featureRepo = FeatureRepository(tmpDir)
        taskRepo = TaskRepository(tmpDir)
        tokens = TokenTracker(File(tmpDir, "tokens.json"))
    }

    @After
    fun teardown() {
        tmpDir.deleteRecursively()
    }

    private fun makeOrchestrator(gateway: FakeLLMGateway): FeatureOrchestrator {
        val intentClassifier = IntentClassifier(config, featureRepo, taskRepo, gateway)
        val planningAgent = PlanningAgent(config, tokens, taskRepo, gateway)
        val executionAgent = ExecutionAgent(config, tokens, taskRepo, gateway)
        val validationAgent = ValidationAgent(config, tokens, taskRepo, gateway)
        return FeatureOrchestrator(featureRepo, taskRepo, intentClassifier, planningAgent, executionAgent, validationAgent)
    }

    @Test
    fun `process with NEW_FEATURE creates feature and task`() {
        val intentJson = """{"intent":"NEW_FEATURE","confidence":0.95}"""
        val planningJson = """{"planningComplete":false,"currentStep":"Сбор","expectedAction":"Уточнить","summary":"","response":"Опишите подробнее"}"""
        val gateway = FakeLLMGateway(intentJson, planningJson)
        val orchestrator = makeOrchestrator(gateway)

        val callLlm = orchestrator.process("добавь авторизацию")

        assertEquals(false, callLlm)
        val feature = featureRepo.getActiveFeature()
        assertNotNull(feature)
        val task = taskRepo.getActiveTaskForFeature(feature.id)
        assertNotNull(task)
        assertEquals(Stage.PLANNING, task.stage)
    }

    @Test
    fun `process with QUESTION and no active feature returns callLlm=true`() {
        val intentJson = """{"intent":"QUESTION","confidence":0.8}"""
        val gateway = FakeLLMGateway(intentJson)
        val orchestrator = makeOrchestrator(gateway)

        val callLlm = orchestrator.process("что такое SOLID?")

        assertTrue(callLlm)
        assertNull(featureRepo.getActiveFeature())
    }

    @Test
    fun `process with SWITCH_FEATURE switches active feature`() {
        val feature1 = featureRepo.createFeature("Feature One")
        val feature2 = featureRepo.createFeature("Feature Two")
        featureRepo.setActiveFeature(feature1.id)
        taskRepo.createTask(feature1.id, "Task One")

        val intentJson = """{"intent":"SWITCH_FEATURE","featureId":"${feature2.id}","confidence":0.9}"""
        val gateway = FakeLLMGateway(intentJson)
        val orchestrator = makeOrchestrator(gateway)

        orchestrator.process("переключись на Feature Two")

        val active = featureRepo.getActiveFeature()
        assertNotNull(active)
        assertEquals(feature2.id, active.id)
    }

    @Test
    fun `process dispatches to planning agent when task in PLANNING stage`() {
        val feature = featureRepo.createFeature("My Feature")
        taskRepo.createTask(feature.id, "My Task")

        val intentJson = """{"intent":"TASK_UPDATE","confidence":0.8}"""
        val planningJson = """{"planningComplete":false,"currentStep":"Шаг 1","expectedAction":"Ответить","summary":"","response":"Нужно больше информации"}"""
        val gateway = FakeLLMGateway(intentJson, planningJson)
        val orchestrator = makeOrchestrator(gateway)

        val callLlm = orchestrator.process("уточняю требования")

        assertEquals(false, callLlm)
        assertEquals(2, gateway.callCount)
    }
}
