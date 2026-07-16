package smartagent.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import smartagent.LLMGateway
import smartagent.Message
import smartagent.ModelConfig
import smartagent.mcp_handler.McpSession
import smartagent.mcp_handler.renderToolResult
import smartagent.tools.Tool
import smartagent.tools.github.GitHubGetDiffTool
import smartagent.tools.github.GitHubGetPRFilesTool
import smartagent.tools.github.GitHubGetPRTool
import java.time.LocalDate

class PushHandler(
    private val session: McpSession,
    private val gateway: LLMGateway? = null,
    private val model: ModelConfig = ModelConfig.QWEN
) {

    data class FileChange(val filename: String, val status: String, val additions: Int, val deletions: Int)

    data class DiffResult(val files: List<FileChange>, val authors: List<String>, val commitMessages: List<String>, val diffText: String = "")

    data class ChangelogFile(val path: String, val sha: String, val rawContent: String)

    fun handle(owner: String, repo: String, branch: String, beforeSha: String, afterSha: String, prNumber: Int? = null): Result<String> {
        println("[Push] handle: $owner/$repo branch=$branch before=${beforeSha.take(7)} after=${afterSha.take(7)} prNumber=$prNumber")
        val diff = if (prNumber != null) {
            println("[Push] fetching PR diff for #$prNumber")
            runCatching { fetchPRDiff(owner, repo, prNumber) }
                .onFailure { println("[Push] fetchPRDiff failed: ${it.message}") }
                .getOrElse {
                    println("[Push] falling back to compare_commits")
                    runCatching { fetchDiff(owner, repo, beforeSha, afterSha) }.getOrElse { DiffResult(emptyList(), listOf("unknown"), emptyList()) }
                }
        } else {
            println("[Push] no prNumber, using compare_commits")
            runCatching { fetchDiff(owner, repo, beforeSha, afterSha) }
                .getOrElse { e -> return Result.failure(e) }
        }
        println("[Push] diff: ${diff.files.size} files, authors=${diff.authors}, commits=${diff.commitMessages.size}")

        val prTitle = if (prNumber != null) runCatching { fetchPRTitle(owner, repo, prNumber) }.getOrNull() else null
        println("[Push] prTitle=$prTitle")
        val summary = runCatching { generateSummary(diff, prTitle) }.onFailure { println("[Push] generateSummary failed: ${it.message}") }.getOrNull()
        println("[Push] summary=${summary?.take(80)}")
        val entry = buildEntry(diff, branch, prTitle, summary)

        val changelogFile = runCatching { findChangelog(owner, repo, branch) }.getOrNull()

        val newContent = if (changelogFile != null) {
            entry + "\n\n" + changelogFile.rawContent
        } else {
            "# Changelog\n\n$entry"
        }

        return runCatching {
            writeChangelog(owner, repo, branch, changelogFile, newContent)
        }
    }

    internal fun fetchDiff(owner: String, repo: String, base: String, head: String): DiffResult {
        val isInitialPush = base.all { it == '0' }

        if (!isInitialPush) {
            val result = session.callTool(
                "compare_commits", mapOf(
                    "owner" to JsonPrimitive(owner),
                    "repo" to JsonPrimitive(repo),
                    "base" to JsonPrimitive(base),
                    "head" to JsonPrimitive(head)
                )
            )
            if (result != null) {
                val text = renderToolResult(result)
                if (text.isNotBlank() && !text.startsWith("[error]")) {
                    return parseDiffResult(text)
                }
            }
        }

        // Fallback: get latest commit info from branch
        val commitsResult = session.callTool(
            "list_commits", mapOf(
                "owner" to JsonPrimitive(owner),
                "repo" to JsonPrimitive(repo),
                "sha" to JsonPrimitive(head)
            )
        )
        if (commitsResult != null) {
            val text = renderToolResult(commitsResult)
            if (text.isNotBlank() && !text.startsWith("[error]")) {
                return parseCommitsResult(text)
            }
        }

        return DiffResult(emptyList(), listOf("unknown"), emptyList())
    }

    private fun callWithFallback(tool: Tool, owner: String, repo: String, prNumber: Int): String {
        val result = tool.execute(mapOf("owner" to owner, "repo" to repo, "pullNumber" to prNumber))
        if (result.isNotBlank() && !result.startsWith("[error]")) return result
        return tool.execute(mapOf("owner" to owner, "repo" to repo, "pull_number" to prNumber))
    }

    internal fun fetchPRDiff(owner: String, repo: String, prNumber: Int): DiffResult {
        val filesText = callWithFallback(GitHubGetPRFilesTool(session), owner, repo, prNumber)
        println("[Push] get_pull_request_files (${filesText.length} chars): ${filesText.take(200)}")
        val files = if (filesText.isNotBlank() && !filesText.startsWith("[error]")) parsePRFilesResult(filesText) else emptyList()

        val prMeta = callWithFallback(GitHubGetPRTool(session), owner, repo, prNumber)
        println("[Push] get_pull_request (${prMeta.length} chars): ${prMeta.take(200)}")
        val author = extractAuthorFromPR(prMeta)

        val diffText = callWithFallback(GitHubGetDiffTool(session), owner, repo, prNumber)
        println("[Push] get_pull_request_diff (${diffText.length} chars)")

        return DiffResult(files, listOf(author), emptyList(), diffText)
    }

    internal fun fetchPRTitle(owner: String, repo: String, prNumber: Int): String? {
        val prMeta = callWithFallback(GitHubGetPRTool(session), owner, repo, prNumber)
        if (prMeta.isBlank() || prMeta.startsWith("[error]")) return null
        return try {
            Json.parseToJsonElement(prMeta).jsonObject["title"]?.jsonPrimitive?.content
        } catch (_: Exception) {
            Regex(""""title"\s*:\s*"([^"]+)"""").find(prMeta)?.groupValues?.get(1)
        }
    }

    internal fun extractAuthorFromPR(prMeta: String): String {
        if (prMeta.isBlank() || prMeta.startsWith("[error]")) return "unknown"
        return try {
            Json.parseToJsonElement(prMeta).jsonObject["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content ?: "unknown"
        } catch (_: Exception) {
            Regex(""""login"\s*:\s*"([^"]+)"""").find(prMeta)?.groupValues?.get(1) ?: "unknown"
        }
    }

    internal fun parsePRFilesResult(text: String): List<FileChange> {
        val arr = try { Json.parseToJsonElement(text).jsonArray } catch (_: Exception) { return emptyList() }
        return arr.mapNotNull { el ->
            val obj = el.jsonObject
            val filename = obj["filename"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val status = obj["status"]?.jsonPrimitive?.content ?: "modified"
            val additions = obj["additions"]?.jsonPrimitive?.intOrNull ?: 0
            val deletions = obj["deletions"]?.jsonPrimitive?.intOrNull ?: 0
            FileChange(filename, status, additions, deletions)
        }
    }

    internal fun parseDiffResult(text: String): DiffResult {
        val json = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { return DiffResult(emptyList(), listOf("unknown"), emptyList()) }

        val files = json["files"]?.jsonArray?.mapNotNull { el ->
            val obj = el.jsonObject
            val filename = obj["filename"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val status = obj["status"]?.jsonPrimitive?.content ?: "modified"
            val additions = obj["additions"]?.jsonPrimitive?.intOrNull ?: 0
            val deletions = obj["deletions"]?.jsonPrimitive?.intOrNull ?: 0
            FileChange(filename, status, additions, deletions)
        } ?: emptyList()

        val commits = json["commits"]?.jsonArray ?: kotlinx.serialization.json.JsonArray(emptyList())
        val authors = commits.mapNotNull { el ->
            el.jsonObject["commit"]?.jsonObject?.get("author")?.jsonObject?.get("name")?.jsonPrimitive?.content
        }.distinct().ifEmpty { listOf("unknown") }
        val commitMessages = commits.mapNotNull { el ->
            el.jsonObject["commit"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.lines()?.firstOrNull()
        }

        return DiffResult(files, authors, commitMessages)
    }

    internal fun parseCommitsResult(text: String): DiffResult {
        val arr = try { Json.parseToJsonElement(text).jsonArray } catch (_: Exception) { return DiffResult(emptyList(), listOf("unknown"), emptyList()) }
        val authors = arr.mapNotNull { el ->
            el.jsonObject["commit"]?.jsonObject?.get("author")?.jsonObject?.get("name")?.jsonPrimitive?.content
        }.distinct().ifEmpty { listOf("unknown") }
        val commitMessages = arr.mapNotNull { el ->
            el.jsonObject["commit"]?.jsonObject?.get("message")?.jsonPrimitive?.content?.lines()?.firstOrNull()
        }
        return DiffResult(emptyList(), authors, commitMessages)
    }

    internal fun generateSummary(diff: DiffResult, prTitle: String?): String? {
        if (gateway == null) return null
        val prompt = buildString {
            if (prTitle != null) appendLine("PR: $prTitle")
            if (diff.diffText.isNotBlank()) {
                appendLine("Diff:")
                appendLine(diff.diffText.take(4000))
            } else if (diff.files.isNotEmpty()) {
                appendLine("Changed files:")
                diff.files.take(20).forEach { f ->
                    appendLine("- ${f.filename} (${f.status}, +${f.additions}/-${f.deletions})")
                }
            }
        }.trim()
        if (prompt.isBlank()) return null
        val messages = listOf(
            Message("system", "You are a technical writer. Given a PR title and diff, write a concise 1-2 sentence summary in Russian describing what was done and why. Be specific, focus on purpose and impact. Do not list file names."),
            Message("user", prompt)
        )
        return gateway.chat(messages, model, source = "changelog")?.content?.trim()?.takeIf { it.isNotBlank() }
    }

    internal fun buildEntry(diff: DiffResult, branch: String, prTitle: String? = null, summary: String? = null): String {
        val date = LocalDate.now().toString()
        val authorsStr = diff.authors.joinToString(", ")
        return buildString {
            appendLine("## $date — $authorsStr (branch: $branch)")
            if (prTitle != null) {
                appendLine()
                appendLine("**$prTitle**")
            }
            if (summary != null) {
                appendLine()
                appendLine(summary)
            }
            if (diff.files.isNotEmpty()) {
                appendLine()
                for (f in diff.files) {
                    val stats = if (f.additions > 0 || f.deletions > 0) " (+${f.additions}/-${f.deletions})" else ""
                    appendLine("- ${f.filename} (${f.status}$stats)")
                }
            }
            if (diff.commitMessages.isNotEmpty()) {
                appendLine()
                for (msg in diff.commitMessages) {
                    appendLine("> \"$msg\"")
                }
            }
        }.trimEnd()
    }

    internal fun findChangelog(owner: String, repo: String, branch: String): ChangelogFile? {
        for (candidate in CHANGELOG_CANDIDATES) {
            val result = session.callTool(
                "get_file_contents", mapOf(
                    "owner" to JsonPrimitive(owner),
                    "repo" to JsonPrimitive(repo),
                    "path" to JsonPrimitive(candidate),
                    "branch" to JsonPrimitive(branch)
                )
            ) ?: continue

            val text = renderToolResult(result)
            if (text.isBlank() || text.startsWith("[error]")) continue

            val fileInfo = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { continue }
            val sha = fileInfo["sha"]?.jsonPrimitive?.content ?: continue
            val rawContent = fileInfo["content"]?.jsonPrimitive?.content ?: continue

            return ChangelogFile(candidate, sha, rawContent)
        }
        return null
    }

    private fun writeChangelog(owner: String, repo: String, branch: String, existing: ChangelogFile?, newContent: String): String {
        val path = existing?.path ?: DEFAULT_CHANGELOG

        val args = buildMap<String, JsonElement> {
            put("owner", JsonPrimitive(owner))
            put("repo", JsonPrimitive(repo))
            put("path", JsonPrimitive(path))
            put("message", JsonPrimitive("chore: update changelog for push to $branch"))
            put("content", JsonPrimitive(newContent))
            put("branch", JsonPrimitive(branch))
            if (existing != null) put("sha", JsonPrimitive(existing.sha))
        }

        val result = session.callTool("create_or_update_file", args)
            ?: error("create_or_update_file вернул null")
        val text = renderToolResult(result)
        if (text.startsWith("[error]")) error("Не удалось записать changelog: $text")
        return "Changelog обновлён: $path"
    }

    companion object {
        internal val CHANGELOG_CANDIDATES = listOf(
            "CHANGELOG.md", "CHANGES.md", "HISTORY.md",
            "changelog.md", "changes.md", "history.md",
            "CHANGELOG", "CHANGES"
        )
        private const val DEFAULT_CHANGELOG = "CHANGELOG.md"
    }
}
