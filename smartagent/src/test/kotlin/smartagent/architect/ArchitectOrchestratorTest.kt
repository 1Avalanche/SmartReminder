package smartagent.architect

import org.junit.After
import org.junit.Before
import org.junit.Test
import smartagent.ChatSession
import smartagent.FakeLLMGateway
import smartagent.SessionConfig
import smartagent.TokenTracker
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchitectOrchestratorTest {

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

    private fun alwaysValidGateway(vararg extra: String): FakeLLMGateway {
        val valid = """{"status":"VALID","reason":"","invariant":""}"""
        val responses = Array(20) { valid } + extra.toList()
        return FakeLLMGateway(*responses)
    }

    private fun makeOrchestrator(
        mainGateway: FakeLLMGateway,
        invGateway: FakeLLMGateway = alwaysValidGateway()
    ): ArchitectOrchestrator {
        val invariantAgent = InvariantAgent(config, tokens, invGateway)
        val intentClassifier = IntentClassifier(config, featureRepo, taskRepo, mainGateway)
        val planningAgent = PlanningAgent(config, tokens, taskRepo, mainGateway)
        val executionAgent = ExecutionAgent(config, tokens, taskRepo, mainGateway)
        val validationAgent = ValidationAgent(config, tokens, taskRepo, mainGateway)
        return ArchitectOrchestrator(ChatSession(), featureRepo, taskRepo, invariantAgent, intentClassifier, planningAgent, executionAgent, validationAgent)
    }

    @Test
    fun `process with NEW_FEATURE creates feature and task`() {
        val intentJson = """{"intent":"NEW_FEATURE","confidence":0.95}"""
        val planningJson = """{"planningComplete":false,"currentStep":"Сбор","expectedAction":"Уточнить","summary":"","response":"Опишите подробнее"}"""
        val orchestrator = makeOrchestrator(FakeLLMGateway(intentJson, planningJson))

        orchestrator.process("добавь авторизацию")

        val feature = featureRepo.getActiveFeature()
        assertNotNull(feature)
        val task = taskRepo.getActiveTaskForFeature(feature.id)
        assertNotNull(task)
        assertEquals(Stage.PLANNING, task.stage)
    }

    @Test
    fun `process with QUESTION and no active feature does not crash`() {
        val intentJson = """{"intent":"QUESTION","confidence":0.8}"""
        val orchestrator = makeOrchestrator(FakeLLMGateway(intentJson))

        orchestrator.process("что такое SOLID?")

        assertNull(featureRepo.getActiveFeature())
    }

    @Test
    fun `process with SWITCH_FEATURE switches active feature`() {
        val feature1 = featureRepo.createFeature("Feature One")
        val feature2 = featureRepo.createFeature("Feature Two")
        featureRepo.setActiveFeature(feature1.id)
        taskRepo.createTask(feature1.id, "Task One")

        val intentJson = """{"intent":"SWITCH_FEATURE","featureId":"${feature2.id}","confidence":0.9}"""
        val orchestrator = makeOrchestrator(FakeLLMGateway(intentJson))

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
        val mainGateway = FakeLLMGateway(intentJson, planningJson)
        val orchestrator = makeOrchestrator(mainGateway)

        orchestrator.process("уточняю требования")

        assertEquals(2, mainGateway.callCount)
    }

    @Test
    fun `FSM transitions PLANNING to EXECUTION in orchestrator when planningComplete`() {
        val feature = featureRepo.createFeature("My Feature")
        val task = taskRepo.createTask(feature.id, "My Task")

        val intentJson = """{"intent":"TASK_UPDATE","confidence":0.8}"""
        val planningJson = """{"planningComplete":true,"currentStep":"Готово","expectedAction":"","summary":"ok","response":"Начинаем","plan":"## Plan"}"""
        val executionJson = """{"executionComplete":false,"currentStep":"Проектирование","expectedAction":"Продолжить","artifact":"","response":"Работаю над архитектурой"}"""
        val orchestrator = makeOrchestrator(FakeLLMGateway(intentJson, planningJson, executionJson))

        orchestrator.process("всё понятно")

        val updated = taskRepo.getTask(task.id)
        assertEquals(Stage.EXECUTION, updated?.stage)
    }

    @Test
    fun `INVALID invariant blocks processing`() {
        val invalidJson = """{"status":"INVALID","reason":"Запрещено использовать синглтоны","invariant":""}"""
        val invGateway = FakeLLMGateway(invalidJson)
        val orchestrator = makeOrchestrator(FakeLLMGateway(), invGateway)

        orchestrator.process("использую синглтон ApiSample")

        assertNull(featureRepo.getActiveFeature())
    }

    @Test
    fun `NEW_INVARIANT saves invariant`() {
        val newInvJson = """{"status":"NEW_INVARIANT","reason":"","invariant":"Не использовать синглтоны"}"""
        val invGateway = FakeLLMGateway(newInvJson)
        val orchestrator = makeOrchestrator(FakeLLMGateway(), invGateway)

        orchestrator.process("запрети синглтоны")

        val saved = InvariantAgent(config, tokens, FakeLLMGateway()).getUserInvariants()
        assertTrue(saved.contains("Не использовать синглтоны"))
    }

    @Test
    fun `NEW_INVARIANT saves invariant and continues to route planning agent`() {
        val feature = featureRepo.createFeature("My Feature")
        taskRepo.createTask(feature.id, "My Task")

        val newInvJson = """{"status":"NEW_INVARIANT","reason":"","invariant":"навигация через готовые библиотеки"}"""
        val validJson = """{"status":"VALID","reason":"","invariant":""}"""
        val invGateway = FakeLLMGateway(newInvJson, *Array(10) { validJson })

        val intentJson = """{"intent":"TASK_UPDATE","confidence":0.8}"""
        val planningJson = """{"planningComplete":false,"currentStep":"","expectedAction":"","summary":"","response":"Принято, MVI + готовые библиотеки навигации"}"""
        val mainGateway = FakeLLMGateway(intentJson, planningJson)

        val orchestrator = makeOrchestrator(mainGateway, invGateway)
        orchestrator.process("MVI. навигацию лучше через готовые библиотеки")

        assertEquals(2, mainGateway.callCount)
        val inv = InvariantAgent(config, tokens, FakeLLMGateway()).getUserInvariants()
        assertTrue(inv.contains("навигация через готовые библиотеки"))
    }

    @Test
    fun `FSM transitions EXECUTION to VALIDATION in orchestrator when executionComplete`() {
        val feature = featureRepo.createFeature("My Feature")
        val task = taskRepo.createTask(feature.id, "My Task")
        taskRepo.updateStage(task.id, Stage.EXECUTION)

        val intentJson = """{"intent":"TASK_UPDATE","confidence":0.8}"""
        val executionJson = """{"executionComplete":true,"currentStep":"Готово","expectedAction":"","artifact":"## Architecture","response":"Готово"}"""
        val validationJson = """{"validationPassed":false,"returnToExecution":false,"currentStep":"Проверка","expectedAction":"Уточнить","review":"","response":"Проверяю"}"""
        val orchestrator = makeOrchestrator(FakeLLMGateway(intentJson, executionJson, validationJson))

        orchestrator.process("готово")

        val updated = taskRepo.getTask(task.id)
        assertEquals(Stage.VALIDATION, updated?.stage)
    }
}
