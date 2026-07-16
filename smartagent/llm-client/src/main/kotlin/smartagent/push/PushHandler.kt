package smartagent.push

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import smartagent.mcp_handler.McpSession
import smartagent.mcp_handler.renderToolResult
import java.time.LocalDate
import java.util.Base64

class PushHandler(private val session: McpSession) {

    data class FileChange(val filename: String, val status: String, val additions: Int, val deletions: Int)

    data class DiffResult(val files: List<FileChange>, val authorName: String, val commitMessage: String)

    data class ChangelogFile(val path: String, val sha: String, val rawContent: String)

    fun handle(owner: String, repo: String, branch: String, beforeSha: String, afterSha: String): Result<String> {
        val diff = runCatching { fetchDiff(owner, repo, beforeSha, afterSha) }
            .getOrElse { e -> return Result.failure(e) }

        val entry = buildEntry(diff, branch)

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

        return DiffResult(emptyList(), "unknown", "")
    }

    internal fun parseDiffResult(text: String): DiffResult {
        val json = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { return DiffResult(emptyList(), "unknown", "") }

        val files = json["files"]?.jsonArray?.mapNotNull { el ->
            val obj = el.jsonObject
            val filename = obj["filename"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val status = obj["status"]?.jsonPrimitive?.content ?: "modified"
            val additions = obj["additions"]?.jsonPrimitive?.intOrNull ?: 0
            val deletions = obj["deletions"]?.jsonPrimitive?.intOrNull ?: 0
            FileChange(filename, status, additions, deletions)
        } ?: emptyList()

        val firstCommit = json["commits"]?.jsonArray?.firstOrNull()?.jsonObject
        val commitObj = firstCommit?.get("commit")?.jsonObject
        val authorName = commitObj?.get("author")?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "unknown"
        val commitMessage = commitObj?.get("message")?.jsonPrimitive?.content?.lines()?.firstOrNull() ?: ""

        return DiffResult(files, authorName, commitMessage)
    }

    internal fun parseCommitsResult(text: String): DiffResult {
        val arr = try { Json.parseToJsonElement(text).jsonArray } catch (_: Exception) { return DiffResult(emptyList(), "unknown", "") }
        val firstCommit = arr.firstOrNull()?.jsonObject ?: return DiffResult(emptyList(), "unknown", "")
        val commitObj = firstCommit["commit"]?.jsonObject
        val authorName = commitObj?.get("author")?.jsonObject?.get("name")?.jsonPrimitive?.content ?: "unknown"
        val commitMessage = commitObj?.get("message")?.jsonPrimitive?.content?.lines()?.firstOrNull() ?: ""
        return DiffResult(emptyList(), authorName, commitMessage)
    }

    internal fun buildEntry(diff: DiffResult, branch: String): String {
        val date = LocalDate.now().toString()
        return buildString {
            appendLine("## $date — ${diff.authorName} (branch: $branch)")
            if (diff.files.isNotEmpty()) {
                appendLine()
                for (f in diff.files) {
                    val stats = if (f.additions > 0 || f.deletions > 0) " (+${f.additions}/-${f.deletions})" else ""
                    appendLine("- ${f.filename} (${f.status}$stats)")
                }
            }
            if (diff.commitMessage.isNotBlank()) {
                appendLine()
                append("> \"${diff.commitMessage}\"")
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
            val encodedContent = fileInfo["content"]?.jsonPrimitive?.content ?: continue
            val rawContent = try {
                Base64.getDecoder().decode(encodedContent.replace("\n", "")).toString(Charsets.UTF_8)
            } catch (_: Exception) { continue }

            return ChangelogFile(candidate, sha, rawContent)
        }
        return null
    }

    private fun writeChangelog(owner: String, repo: String, branch: String, existing: ChangelogFile?, newContent: String): String {
        val path = existing?.path ?: DEFAULT_CHANGELOG
        val encodedContent = Base64.getEncoder().encodeToString(newContent.toByteArray(Charsets.UTF_8))

        val args = buildMap<String, JsonElement> {
            put("owner", JsonPrimitive(owner))
            put("repo", JsonPrimitive(repo))
            put("path", JsonPrimitive(path))
            put("message", JsonPrimitive("chore: update changelog for push to $branch"))
            put("content", JsonPrimitive(encodedContent))
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
