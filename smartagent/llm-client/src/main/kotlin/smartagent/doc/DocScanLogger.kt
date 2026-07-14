package smartagent.doc

import java.io.File
import java.time.LocalDateTime

object DocScanLogger {
    private val logFile: File by lazy {
        val path = listOf("cli/docindex-scan.log", "docindex-scan.log")
            .firstOrNull { File(it).parentFile?.exists() ?: true } ?: "docindex-scan.log"
        File(path)
    }

    fun start(owner: String, repo: String, branch: String) {
        runCatching { logFile.writeText("=== DocIndex scan: $owner/$repo@$branch | ${LocalDateTime.now()} ===\n") }
    }

    fun logDoc(path: String) = append("[DOC]   $path")

    fun logSummary(docCount: Int, chunkCount: Int) =
        append("=== Done: $docCount docs, $chunkCount chunks | ${LocalDateTime.now()} ===")

    private fun append(line: String) {
        runCatching { logFile.appendText("$line\n") }
    }
}
