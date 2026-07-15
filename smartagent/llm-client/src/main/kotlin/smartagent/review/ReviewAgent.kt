package smartagent.review

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig
import java.time.Instant

class ReviewAgent(private val gateway: LLMGateway) {

    fun review(context: ReviewContext, model: ModelConfig = ModelConfig.QWEN): ReviewReport {
        val prompt = buildPrompt(context)
        val messages = listOf(
            Message("system", SYSTEM_PROMPT),
            Message("user", prompt)
        )

        val response = gateway.chat(messages, model, source = "review")
            ?: error("LLM returned null response during review")

        return parseReport(context, response.content)
    }

    private fun buildPrompt(ctx: ReviewContext): String = buildString {
        appendLine("## Pull Request: ${ctx.prTitle}")
        appendLine("Repository: ${ctx.owner}/${ctx.repo} | PR #${ctx.pullRequestNumber}")
        appendLine("Branch: ${ctx.headBranch} → ${ctx.baseBranch}")
        appendLine()

        if (ctx.prDescription.isNotBlank()) {
            appendLine("### Description")
            appendLine(ctx.prDescription)
            appendLine()
        }

        if (ctx.ragContext.isNotBlank()) {
            appendLine("### Project Architecture Context (RAG)")
            appendLine(ctx.ragContext.take(4000))
            appendLine()
        }

        if (ctx.fileContents.isNotEmpty()) {
            appendLine("### Full File Contents (after changes)")
            for ((filename, content) in ctx.fileContents) {
                appendLine("--- $filename ---")
                appendLine(content.take(3000))
                appendLine()
            }
        }

        if (ctx.diff.isNotBlank()) {
            appendLine("### Changes (diff)")
            appendLine(ctx.diff.take(8000))
            appendLine()
        }

        appendLine("Perform a thorough code review. Return JSON only.")
    }

    private fun parseReport(ctx: ReviewContext, llmOutput: String): ReviewReport {
        val json = extractJson(llmOutput)
        return try {
            val root = Json.parseToJsonElement(json).jsonObject
            val summary = root["summary"]?.jsonPrimitive?.content ?: llmOutput.take(500)
            val issues = root["issues"]?.jsonArray?.mapNotNull { parseIssue(it.jsonObject) } ?: emptyList()
            ReviewReport(
                owner = ctx.owner,
                repo = ctx.repo,
                pullRequestNumber = ctx.pullRequestNumber,
                prTitle = ctx.prTitle,
                timestamp = Instant.now().toString(),
                summary = summary,
                issues = issues
            )
        } catch (_: Exception) {
            ReviewReport(
                owner = ctx.owner,
                repo = ctx.repo,
                pullRequestNumber = ctx.pullRequestNumber,
                prTitle = ctx.prTitle,
                timestamp = Instant.now().toString(),
                summary = llmOutput,
                issues = emptyList()
            )
        }
    }

    private fun parseIssue(obj: JsonObject): ReviewIssue? {
        return try {
            ReviewIssue(
                severity = ReviewSeverity.valueOf(
                    obj["severity"]?.jsonPrimitive?.content?.uppercase() ?: "MEDIUM"
                ),
                category = ReviewCategory.valueOf(
                    obj["category"]?.jsonPrimitive?.content?.uppercase()
                        ?.replace(" ", "_") ?: "CODE_QUALITY"
                ),
                file = obj["file"]?.jsonPrimitive?.content ?: "",
                line = obj["line"]?.jsonPrimitive?.content?.toIntOrNull(),
                description = obj["description"]?.jsonPrimitive?.content ?: "",
                recommendation = obj["recommendation"]?.jsonPrimitive?.content ?: ""
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractJson(text: String): String {
        val jsonBlock = Regex("```(?:json)?\\s*(\\{[\\s\\S]*?})\\s*```").find(text)?.groupValues?.get(1)
        if (jsonBlock != null) return jsonBlock
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start != -1 && end > start) return text.substring(start, end + 1)
        return text
    }

    companion object {
        private val SYSTEM_PROMPT = """
You are an expert code reviewer. Analyze the provided pull request and return a JSON report.

IMPORTANT: All text fields in the JSON response (summary, description, recommendation) MUST be written in Russian language.

Check for:
- Potential bugs and logic errors
- Runtime errors and null pointer exceptions
- Unhandled edge cases
- State management and concurrency issues (coroutines, Flow, race conditions)
- Performance problems
- Architecture violations relative to the existing codebase
- Code quality issues
- Security vulnerabilities

Use the project architecture context (RAG) to evaluate changes against existing patterns.
If no issues found in a category, do not invent them.

Return ONLY valid JSON in this format:
{
  "summary": "Краткая общая оценка PR на русском языке",
  "issues": [
    {
      "severity": "CRITICAL|HIGH|MEDIUM|LOW|INFO",
      "category": "BUG|RUNTIME_ERROR|EDGE_CASE|STATE_MANAGEMENT|PERFORMANCE|ARCHITECTURE|CODE_QUALITY|SECURITY",
      "file": "path/to/file.kt",
      "line": 42,
      "description": "Что не так и почему — на русском языке",
      "recommendation": "Как исправить — на русском языке"
    }
  ]
}

If there are no issues, return an empty issues array with a positive summary in Russian.
""".trimIndent()
    }
}
