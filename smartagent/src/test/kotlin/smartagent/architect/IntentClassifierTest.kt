package smartagent.architect

import org.junit.After
import org.junit.Before
import org.junit.Test
import smartagent.FakeLLMGateway
import smartagent.SessionConfig
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IntentClassifierTest {

    private lateinit var tmpDir: File
    private lateinit var featureRepo: FeatureRepository
    private lateinit var taskRepo: TaskRepository
    private val config = SessionConfig()

    @Before
    fun setup() {
        tmpDir = createTempDirectory("smartagent-test").toFile()
        featureRepo = FeatureRepository(tmpDir)
        taskRepo = TaskRepository(tmpDir)
    }

    @After
    fun teardown() {
        tmpDir.deleteRecursively()
    }

    @Test
    fun `classify returns NEW_FEATURE for valid JSON`() {
        val json = """{"intent":"NEW_FEATURE","confidence":0.95,"reason":"user wants new feature"}"""
        val gateway = FakeLLMGateway(json)
        val classifier = IntentClassifier(config, featureRepo, taskRepo, gateway)

        val result = classifier.classify("добавь авторизацию")

        assertNotNull(result)
        assertEquals(UserIntent.NEW_FEATURE, result.intent)
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `classify returns QUESTION intent correctly`() {
        val json = """{"intent":"QUESTION","confidence":0.8,"reason":"user asking question"}"""
        val gateway = FakeLLMGateway(json)
        val classifier = IntentClassifier(config, featureRepo, taskRepo, gateway)

        val result = classifier.classify("что такое solid?")

        assertNotNull(result)
        assertEquals(UserIntent.QUESTION, result.intent)
    }

    @Test
    fun `classify returns null when gateway returns null`() {
        val gateway = FakeLLMGateway()
        val classifier = IntentClassifier(config, featureRepo, taskRepo, gateway)

        val result = classifier.classify("some input")

        assertNull(result)
    }

    @Test
    fun `classify returns null for malformed JSON`() {
        val gateway = FakeLLMGateway("not valid json at all")
        val classifier = IntentClassifier(config, featureRepo, taskRepo, gateway)

        val result = classifier.classify("some input")

        assertNull(result)
    }

    @Test
    fun `classify passes featureId for SWITCH_FEATURE intent`() {
        val json = """{"intent":"SWITCH_FEATURE","featureId":"feature-002","confidence":0.9}"""
        val gateway = FakeLLMGateway(json)
        val classifier = IntentClassifier(config, featureRepo, taskRepo, gateway)

        val result = classifier.classify("переключись на feature-002")

        assertNotNull(result)
        assertEquals(UserIntent.SWITCH_FEATURE, result.intent)
        assertEquals("feature-002", result.featureId)
    }

    @Test
    fun `classify parses JSON wrapped in code block`() {
        val json = "```json\n{\"intent\":\"NEW_TASK\",\"confidence\":0.85}\n```"
        val gateway = FakeLLMGateway(json)
        val classifier = IntentClassifier(config, featureRepo, taskRepo, gateway)

        val result = classifier.classify("создай новую задачу")

        assertNotNull(result)
        assertEquals(UserIntent.NEW_TASK, result.intent)
    }
}
