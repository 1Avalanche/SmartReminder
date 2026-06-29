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
        val planningAgent = PlanningAgent(config, tokens, taskRepo, mainGateway)
        val executionAgent = ExecutionAgent(config, tokens, taskRepo, mainGateway)
        val validationAgent = ValidationAgent(config, tokens, taskRepo, mainGateway)
        return ArchitectOrchestrator(ChatSession(), featureRepo, taskRepo, invariantAgent, planningAgent, executionAgent, validationAgent, mainGateway, config, tokens)
    }

    @Test
    fun `process with CREATE_TASK creates feature and task`() {
        val thoughtJson = """{"response":"Создаю задачу по авторизации","action":"CREATE_TASK","taskTitle":"Авторизация","taskDescription":"Спроектировать систему авторизации"}"""
        val planningJson = """{"planningComplete":false,"currentStep":"Сбор","expectedAction":"Уточнить","summary":"","response":"Опишите подробнее"}"""
        val orchestrator = makeOrchestrator(FakeLLMGateway(thoughtJson, planningJson))

        orchestrator.process("добавь авторизацию")

        val feature = featureRepo.getActiveFeature()
        assertNotNull(feature)
        val task = taskRepo.getActiveTaskForFeature(feature.id)
        assertNotNull(task)
        assertEquals(Stage.PLANNING, task.stage)
    }

    @Test
    fun `process with ANSWER and no active feature does not crash`() {
        val thoughtJson = """{"response":"SOLID — это пять принципов объектно-ориентированного дизайна.","action":"ANSWER"}"""
        val orchestrator = makeOrchestrator(FakeLLMGateway(thoughtJson))

        orchestrator.process("что такое SOLID?")

        assertNull(featureRepo.getActiveFeature())
    }

    @Test
    fun `process with SWITCH_TASK switches active task`() {
        val feature = featureRepo.createFeature("My Feature")
        val task1 = taskRepo.createTask(feature.id, "Task One")
        val task2 = taskRepo.createTask(feature.id, "Task Two")
        taskRepo.activateTask(task1.id)

        val thoughtJson = """{"response":"Переключаюсь на Task Two","action":"SWITCH_TASK","taskTitle":"Task Two"}"""
        val orchestrator = makeOrchestrator(FakeLLMGateway(thoughtJson))

        orchestrator.process("давай вернёмся к Task Two")

        val activeTask = taskRepo.getActiveTaskForFeature(feature.id)
        assertNotNull(activeTask)
        assertEquals(task2.id, activeTask.id)
    }

    @Test
    fun `process dispatches to planning agent when task in PLANNING stage`() {
        val feature = featureRepo.createFeature("My Feature")
        taskRepo.createTask(feature.id, "My Task")

        val thoughtJson = """{"response":"Продолжаем планирование","action":"UPDATE_TASK","taskTitle":"My Task","taskDescription":"Уточнить требования к задаче"}"""
        val planningJson = """{"planningComplete":false,"currentStep":"Шаг 1","expectedAction":"Ответить","summary":"","response":"Нужно больше информации"}"""
        val mainGateway = FakeLLMGateway(thoughtJson, planningJson)
        val orchestrator = makeOrchestrator(mainGateway)

        orchestrator.process("уточняю требования")

        assertEquals(2, mainGateway.callCount)
    }

    @Test
    fun `FSM transitions PLANNING to EXECUTION in orchestrator when planningComplete`() {
        val feature = featureRepo.createFeature("My Feature")
        val task = taskRepo.createTask(feature.id, "My Task")

        val thoughtJson = """{"response":"Принято, продолжаем","action":"UPDATE_TASK","taskTitle":"My Task","taskDescription":"Задача ясна"}"""
        val planningJson = """{"planningComplete":true,"currentStep":"Готово","expectedAction":"","summary":"ok","response":"Начинаем","plan":"## Plan"}"""
        val executionJson = """{"executionComplete":false,"currentStep":"Проектирование","expectedAction":"Продолжить","artifact":"","response":"Работаю над архитектурой"}"""
        val orchestrator = makeOrchestrator(FakeLLMGateway(thoughtJson, planningJson, executionJson))

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

        val thoughtJson = """{"response":"Принято, уточняем архитектуру навигации","action":"UPDATE_TASK","taskTitle":"My Task","taskDescription":"MVI, навигация через готовые библиотеки"}"""
        val planningJson = """{"planningComplete":false,"currentStep":"","expectedAction":"","summary":"","response":"Принято, MVI + готовые библиотеки навигации"}"""
        val mainGateway = FakeLLMGateway(thoughtJson, planningJson)

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

        val thoughtJson = """{"response":"Продолжаем выполнение","action":"UPDATE_TASK","taskTitle":"My Task","taskDescription":"Завершить проектирование"}"""
        val executionJson = """{"executionComplete":true,"currentStep":"Готово","expectedAction":"","artifact":"## Architecture","response":"Готово"}"""
        val validationJson = """{"validationPassed":false,"returnToExecution":false,"currentStep":"Проверка","expectedAction":"Уточнить","review":"","response":"Проверяю"}"""
        val orchestrator = makeOrchestrator(FakeLLMGateway(thoughtJson, executionJson, validationJson))

        orchestrator.process("готово")

        val updated = taskRepo.getTask(task.id)
        assertEquals(Stage.VALIDATION, updated?.stage)
    }
}
