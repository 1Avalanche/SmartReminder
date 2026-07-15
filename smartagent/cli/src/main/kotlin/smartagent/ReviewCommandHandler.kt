package smartagent

import smartagent.doc.KnowledgeService
import smartagent.mcp_handler.McpManager
import smartagent.review.GitHubReviewDataProvider
import smartagent.review.GitHubReviewPublisher
import smartagent.review.ReviewAgent

class ReviewCommandHandler(
    private val knowledge: KnowledgeService,
    private val gateway: LLMGateway
) {
    fun handle(args: List<String>) {
        if (args.getOrNull(0) == "debug") {
            handleDebug(args.drop(1))
            return
        }

        val ownerRepo = args.getOrNull(0)
        val prNumberStr = args.getOrNull(1)

        if (ownerRepo == null || prNumberStr == null) {
            println("${Colors.LIGHT_YELLOW}Usage: /review <owner>/<repo> <pr_number>${Colors.RESET}")
            println("${Colors.LIGHT_YELLOW}       /review debug <owner>/<repo> <pr_number>${Colors.RESET}")
            return
        }

        val parts = ownerRepo.split("/")
        if (parts.size != 2) {
            println("${Colors.LIGHT_YELLOW}Format must be <owner>/<repo>${Colors.RESET}")
            return
        }
        val (owner, repo) = parts

        val prNumber = prNumberStr.toIntOrNull()
        if (prNumber == null) {
            println("${Colors.LIGHT_YELLOW}pr_number must be an integer${Colors.RESET}")
            return
        }

        val session = McpManager.getSession("github") ?: run {
            println("${Colors.LIGHT_YELLOW}GitHub MCP not connected. Run /init or /mcp github init first.${Colors.RESET}")
            return
        }

        println("${Colors.DARK_GRAY}Fetching PR #$prNumber from $owner/$repo...${Colors.RESET}")
        val dataProvider = GitHubReviewDataProvider(session)
        val context = runCatching {
            dataProvider.fetchContext(owner, repo, prNumber, knowledge)
        }.getOrElse { e ->
            println("${Colors.LIGHT_YELLOW}Failed to fetch PR data: ${e.message}${Colors.RESET}")
            return
        }

        println("${Colors.DARK_GRAY}PR: ${context.prTitle}${Colors.RESET}")
        if (context.diff.isBlank() && context.fileContents.isEmpty()) {
            println("${Colors.LIGHT_YELLOW}Warning: no diff or file contents available. Review may be incomplete.${Colors.RESET}")
            println("${Colors.DARK_GRAY}Tip: run /review debug $owner/$repo $prNumber to inspect MCP responses.${Colors.RESET}")
        }
        println("${Colors.DARK_GRAY}Running AI review...${Colors.RESET}")

        val agent = ReviewAgent(gateway)
        val report = runCatching {
            agent.review(context, ModelConfig.QWEN)
        }.getOrElse { e ->
            println("${Colors.LIGHT_YELLOW}Review failed: ${e.message}${Colors.RESET}")
            return
        }

        val publisher = GitHubReviewPublisher(session)
        val markdown = publisher.toMarkdown(report)

        println()
        println(markdown)
        println()

        print("${Colors.LIGHT_YELLOW}Post review as PR comment? [y/N]: ${Colors.RESET}")
        System.out.flush()
        val confirm = readlnOrNull()?.trim()?.lowercase()
        if (confirm == "y" || confirm == "yes") {
            val result = runCatching { publisher.publish(report) }.getOrElse { e ->
                println("${Colors.LIGHT_YELLOW}Failed to post comment: ${e.message}${Colors.RESET}")
                return
            }
            println("${Colors.LIGHT_GREEN}Review posted: $result${Colors.RESET}")
        } else {
            println("${Colors.DARK_GRAY}Comment not posted.${Colors.RESET}")
        }
    }

    private fun handleDebug(args: List<String>) {
        val ownerRepo = args.getOrNull(0)
        val prNumberStr = args.getOrNull(1)
        if (ownerRepo == null || prNumberStr == null) {
            println("${Colors.LIGHT_YELLOW}Usage: /review debug <owner>/<repo> <pr_number>${Colors.RESET}")
            return
        }
        val parts = ownerRepo.split("/")
        if (parts.size != 2) {
            println("${Colors.LIGHT_YELLOW}Format must be <owner>/<repo>${Colors.RESET}")
            return
        }
        val (owner, repo) = parts
        val prNumber = prNumberStr.toIntOrNull()
        if (prNumber == null) {
            println("${Colors.LIGHT_YELLOW}pr_number must be an integer${Colors.RESET}")
            return
        }
        val session = McpManager.getSession("github") ?: run {
            println("${Colors.LIGHT_YELLOW}GitHub MCP not connected${Colors.RESET}")
            return
        }

        println("${Colors.DARK_GRAY}Fetching raw MCP responses for PR #$prNumber from $owner/$repo...${Colors.RESET}")
        val results = GitHubReviewDataProvider(session).debugFetch(owner, repo, prNumber)
        for ((tool, result) in results) {
            println()
            println("${Colors.LIGHT_GREEN}=== $tool (${result.length} chars) ===${Colors.RESET}")
            println(if (result.isBlank()) "(empty)" else result.take(3000))
        }
    }
}
