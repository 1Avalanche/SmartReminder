package smartagent.review

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import smartagent.doc.KnowledgeService
import smartagent.mcp_handler.McpSession
import smartagent.tools.Tool
import smartagent.tools.github.GitHubGetDiffTool
import smartagent.tools.github.GitHubGetFileContentsTool
import smartagent.tools.github.GitHubGetPRFilesTool
import smartagent.tools.github.GitHubGetPRTool

class GitHubReviewDataProvider(private val session: McpSession) {

    private val getPRTool = GitHubGetPRTool(session)
    private val getPRFilesTool = GitHubGetPRFilesTool(session)
    private val getDiffTool = GitHubGetDiffTool(session)
    private val getFileContentsTool = GitHubGetFileContentsTool(session)

    fun fetchContext(
        owner: String,
        repo: String,
        prNumber: Int,
        knowledge: KnowledgeService
    ): ReviewContext {
        val prMeta = callWithFallback(getPRTool, owner, repo, prNumber)
        logResult("get_pull_request", prMeta)

        val filesRaw = callWithFallback(getPRFilesTool, owner, repo, prNumber)
        logResult("get_pull_request_files", filesRaw)

        val diffRaw = callWithFallback(getDiffTool, owner, repo, prNumber)
        logResult("get_pull_request_diff", diffRaw)

        val title = extractStringField(prMeta, "title") ?: "PR #$prNumber"
        val description = extractStringField(prMeta, "body") ?: ""
        val baseBranch = extractNestedBranch(prMeta, "base") ?: "main"
        val headBranch = extractNestedBranch(prMeta, "head") ?: ""

        val patchesFromFiles = buildDiffFromFiles(filesRaw)
        val diff = when {
            diffRaw.isNotBlank() && !diffRaw.startsWith("[error]") -> diffRaw
            patchesFromFiles.isNotBlank() -> patchesFromFiles
            else -> ""
        }

        val filenames = extractFilenames(filesRaw)
        val fileContents = if (filenames.isNotEmpty() && headBranch.isNotBlank()) {
            fetchFileContents(owner, repo, headBranch, filenames)
        } else emptyMap()

        val ragContext = if (knowledge.isInitialized()) {
            runCatching {
                knowledge.getContext("code review: $title $description").ragContext
            }.getOrDefault("")
        } else ""

        return ReviewContext(
            owner = owner,
            repo = repo,
            pullRequestNumber = prNumber,
            prTitle = title,
            prDescription = description,
            baseBranch = baseBranch,
            headBranch = headBranch,
            changedFiles = filesRaw,
            diff = diff,
            ragContext = ragContext,
            projectRules = "",
            fileContents = fileContents
        )
    }

    fun debugFetch(owner: String, repo: String, prNumber: Int): Map<String, String> {
        val prMeta = callWithFallback(getPRTool, owner, repo, prNumber)
        val filesRaw = callWithFallback(getPRFilesTool, owner, repo, prNumber)
        val diffRaw = callWithFallback(getDiffTool, owner, repo, prNumber)
        return mapOf(
            "get_pull_request" to prMeta,
            "get_pull_request_files" to filesRaw,
            "get_pull_request_diff" to diffRaw
        )
    }

    private fun callWithFallback(tool: Tool, owner: String, repo: String, prNumber: Int): String {
        val camel = tool.execute(mapOf("owner" to owner, "repo" to repo, "pullNumber" to prNumber))
        if (camel.isNotBlank() && !camel.startsWith("[error]")) return camel
        return tool.execute(mapOf("owner" to owner, "repo" to repo, "pull_number" to prNumber))
    }

    private fun logResult(toolName: String, result: String) {
        val preview = result.take(200).replace('\n', '↵')
        println("[review] $toolName → ${result.length} chars: $preview")
    }

    private fun buildDiffFromFiles(filesJson: String): String {
        return try {
            val arr = Json.parseToJsonElement(filesJson).jsonArray
            buildString {
                for (item in arr) {
                    val obj = item.jsonObject
                    val filename = obj["filename"]?.jsonPrimitive?.content ?: continue
                    val status = obj["status"]?.jsonPrimitive?.content ?: ""
                    val patch = obj["patch"]?.jsonPrimitive?.content ?: ""
                    appendLine("--- $filename ($status)")
                    if (patch.isNotBlank()) appendLine(patch)
                    appendLine()
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractFilenames(filesJson: String): List<String> {
        return try {
            Json.parseToJsonElement(filesJson).jsonArray
                .mapNotNull { it.jsonObject["filename"]?.jsonPrimitive?.content }
        } catch (_: Exception) {
            Regex(""""filename"\s*:\s*"([^"]+)"""").findAll(filesJson)
                .map { it.groupValues[1] }
                .toList()
        }
    }

    private fun fetchFileContents(
        owner: String,
        repo: String,
        branch: String,
        filenames: List<String>
    ): Map<String, String> {
        return filenames
            .take(MAX_FILES_FOR_CONTENT)
            .associateWith { filename ->
                runCatching {
                    getFileContentsTool.execute(
                        mapOf("owner" to owner, "repo" to repo, "path" to filename, "branch" to branch)
                    )
                }.getOrDefault("")
            }
            .filterValues { it.isNotBlank() && !it.startsWith("[error]") }
    }

    private fun extractStringField(text: String, field: String): String? =
        Regex(""""$field"\s*:\s*"((?:[^"\\]|\\.)*)"""").find(text)?.groupValues?.get(1)

    private fun extractNestedBranch(text: String, field: String): String? =
        Regex(""""$field"\s*:\s*\{[^}]*?"label"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: Regex(""""$field"\s*:\s*\{[^}]*?"ref"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)

    companion object {
        private const val MAX_FILES_FOR_CONTENT = 5
    }
}
