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

class PlanningAgentTest {

    private lateinit var tmpDir: File
    private lateinit var taskRepo: TaskRepository
    private lateinit var featureRepo: FeatureRepository
    private lateinit var tokensFile: File
    private val config = SessionConfig()
    private lateinit var tokens: TokenTracker
    private lateinit var feature: Feature
    private lateinit var task: Task

    @Before
    fun setup() {
        tmpDir = createTempDirectory("smartagent-test").toFile()
        featureRepo = FeatureRepository(tmpDir)
        taskRepo = TaskRepository(tmpDir)
        tokensFile = File(tmpDir, "tokens.json")
        tokens = TokenTracker(tokensFile)
        feature = featureRepo.createFeature("Test feature")
        task = taskRepo.createTask(feature.id, "Test task")
    }

    @After
    fun teardown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `run returns response when gateway succeeds`() {
        val json = """{"planningComplete":false,"currentStep":"Сбор требований","expectedAction":"Уточнить","summary":"","response":"Расскажите подробнее"}"""
        val gateway = FakeLLMGateway(json)
        val agent = PlanningAgent(config, tokens, taskRepo, gateway)

        val result = agent.run(feature, task, "добавь кнопку")

        assertNotNull(result)
        assertEquals(false, result.planningComplete)
        assertEquals("Расскажите подробнее", result.response)
    }

    @Test
    fun `run returns null when gateway returns null`() {
        val gateway = FakeLLMGateway()
        val agent = PlanningAgent(config, tokens, taskRepo, gateway)

        val result = agent.run(feature, task, "input")

        assertNull(result)
    }

    @Test
    fun `run does not change task stage when planningComplete`() {
        val json = """{"planningComplete":true,"currentStep":"Готово","expectedAction":"Начать проектирование","summary":"требования собраны","response":"Приступаем","plan":"## Plan\n- step 1"}"""
        val gateway = FakeLLMGateway(json)
        val agent = PlanningAgent(config, tokens, taskRepo, gateway)

        agent.run(feature, task, "всё понятно, начинаем")

        val updatedTask = taskRepo.getTask(task.id)
        assertEquals(Stage.PLANNING, updatedTask?.stage)
    }

    @Test
    fun `run saves plan when planningComplete and plan is not blank`() {
        val json = """{"planningComplete":true,"currentStep":"Готово","expectedAction":"","summary":"","response":"","plan":"## My Plan"}"""
        val gateway = FakeLLMGateway(json)
        val agent = PlanningAgent(config, tokens, taskRepo, gateway)

        agent.run(feature, task, "input")

        val plan = taskRepo.getPlan(task.id)
        assertEquals("## My Plan", plan)
    }

    @Test
    fun `run returns null for malformed JSON response`() {
        val gateway = FakeLLMGateway("this is not json")
        val agent = PlanningAgent(config, tokens, taskRepo, gateway)

        val result = agent.run(feature, task, "input")

        assertNull(result)
    }

    @Test
    fun `run appends to task history`() {
        val json = """{"planningComplete":false,"currentStep":"","expectedAction":"","summary":"","response":"Ответ агента"}"""
        val gateway = FakeLLMGateway(json)
        val agent = PlanningAgent(config, tokens, taskRepo, gateway)

        agent.run(feature, task, "вопрос")

        val history = taskRepo.getHistory(task.id)
        assertTrue(history.contains("Ответ агента"))
    }
}
