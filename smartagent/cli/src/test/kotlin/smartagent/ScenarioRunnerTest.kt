package smartagent

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScenarioRunnerTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `loadQuestionFiles returns files sorted by name`() {
        val dir = tmpFolder.newFolder("temp")
        dir.resolve("q3.md").writeText("Q3")
        dir.resolve("q1.md").writeText("Q1")
        dir.resolve("q2.md").writeText("Q2")

        val files = ScenarioRunner.loadQuestionFiles(dir)
        assertEquals(listOf("q1.md", "q2.md", "q3.md"), files.map { it.name })
    }

    @Test
    fun `loadQuestionFiles excludes scenario_md`() {
        val dir = tmpFolder.newFolder("temp")
        dir.resolve("q1.md").writeText("Q1")
        dir.resolve("scenario.md").writeText("existing output")

        val files = ScenarioRunner.loadQuestionFiles(dir)
        assertEquals(listOf("q1.md"), files.map { it.name })
    }

    @Test
    fun `loadQuestionFiles returns empty list for empty dir`() {
        val dir = tmpFolder.newFolder("temp")
        val files = ScenarioRunner.loadQuestionFiles(dir)
        assertTrue(files.isEmpty())
    }

    @Test
    fun `output file accumulates multiple scenario runs`() {
        val outputFile = tmpFolder.newFile("scenario.md")
        outputFile.appendText("## model-a (2026-07-06 10:00)\n\n")
        outputFile.appendText("**You:** Q1\n\n**model-a:** A1\n\n*⏱ 1.2s*\n\n---\n\n")
        outputFile.appendText("## model-b (2026-07-06 11:00)\n\n")
        outputFile.appendText("**You:** Q2\n\n**model-b:** A2\n\n*⏱ 2.5s*\n\n---\n\n")

        val content = outputFile.readText()
        assertTrue("## model-a" in content)
        assertTrue("## model-b" in content)
        assertTrue("⏱ 1.2s" in content)
        assertTrue("⏱ 2.5s" in content)
    }
}
