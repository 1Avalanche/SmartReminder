package smartagent

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal class ScenarioRunner(
    private val client: ChatClient,
    private val session: ChatSession
) {
    fun run() {
        val tempDir = resolveTempDir()
        if (!tempDir.exists() || !tempDir.isDirectory) {
            println("${Colors.LIGHT_YELLOW}Scenario dir not found: ${tempDir.absolutePath}${Colors.RESET}")
            return
        }

        val questionFiles = loadQuestionFiles(tempDir)
        if (questionFiles.isEmpty()) {
            println("${Colors.LIGHT_YELLOW}No question files in: ${tempDir.absolutePath}${Colors.RESET}")
            return
        }

        val outputFile = File(tempDir, "scenario.md")
        val modelName = session.currentModel.shortName
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

        outputFile.appendText("## $modelName ($timestamp)\n\n")
        println("${Colors.DARK_GRAY}Running ${questionFiles.size} question(s) on $modelName → ${outputFile.name}${Colors.RESET}\n")

        for (file in questionFiles) {
            val question = file.readText().trim()
            if (question.isBlank()) continue

            print("${Colors.BRIGHT_WHITE}> ")
            println("${Colors.RESET}$question")

            val startMs = System.currentTimeMillis()
            val reply = client.sendMessage(
                text = question,
                systemPromptOverride = "",
                includeHistory = false,
                saveToHistory = false
            )
            val elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0

            outputFile.appendText("**You:** $question\n\n")
            if (reply != null) {
                outputFile.appendText("**$modelName:** $reply\n\n*⏱ ${String.format("%.1f", elapsedSec)}s*\n\n---\n\n")
            } else {
                outputFile.appendText("**$modelName:** *(no response)* — *⏱ ${String.format("%.1f", elapsedSec)}s*\n\n---\n\n")
            }
        }

        println("${Colors.LIGHT_GREEN}Scenario complete. Saved to: ${outputFile.absolutePath}${Colors.RESET}")
    }

    companion object {
        fun resolveTempDir(): File =
            listOf("temp", "../temp")
                .map { File(it) }
                .firstOrNull { it.exists() && it.isDirectory }
                ?: File("temp")

        fun loadQuestionFiles(dir: File): List<File> =
            dir.listFiles { f -> f.isFile && f.name != "scenario.md" }
                ?.sortedBy { it.name }
                ?: emptyList()
    }
}
