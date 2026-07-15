package smartagent

import kotlinx.coroutines.runBlocking
import smartagent.agent.assist.AssistOrchestrator
import smartagent.doc.FileIndexStorage
import smartagent.doc.GitCloneDocumentSource
import smartagent.doc.IndexBuilder
import smartagent.doc.JsonMetadataStorage
import smartagent.doc.ProjectKnowledgeService
import smartagent.doc.RagSearcher
import smartagent.mcp_handler.McpManager
import smartagent.telegram.api.HttpApiServer
import smartagent.telegram.bot.TelegramBotRunner
import smartagent.telegram.review.TelegramReviewHandler
import smartagent.tools.ToolRegistry
import smartagent.tools.index.IndexInitTool
import smartagent.tools.rag.RagSearchTool

fun main() {
    val token = System.getenv("TELEGRAM_BOT_TOKEN")
        ?: error("TELEGRAM_BOT_TOKEN env var not set")
    val gateway = OkHttpLLMGateway()
    val model = ModelConfig.QWEN

    McpManager.initRemoteServers()

    val indexDir = "${System.getProperty("user.home")}/.config/smartagent/index"
    val indexStorage = FileIndexStorage(JsonVectorIndexPersistence(), "$indexDir/fixed.json")
    val metadataStorage = JsonMetadataStorage("$indexDir/metadata.json")
    val indexBuilder = IndexBuilder(
        embeddingGenerator = OpenRouterEmbeddingGenerator(),
        indexStorage = indexStorage,
        metadataStorage = metadataStorage
    )
    val ragSearcher = RagSearcher(OpenRouterEmbeddingGenerator(), indexStorage)
    val projectKnowledgeService = ProjectKnowledgeService(
        indexBuilder = indexBuilder,
        ragSearcher = ragSearcher,
        metadataStorage = metadataStorage,
        sourceFactory = { owner, repo, branch, paths -> GitCloneDocumentSource(owner, repo, branch, paths) }
    )
    ToolRegistry.register(RagSearchTool(projectKnowledgeService))
    ToolRegistry.register(IndexInitTool(projectKnowledgeService))
    val assistOrchestrator = AssistOrchestrator(projectKnowledgeService, gateway)
    val reviewHandler = TelegramReviewHandler(gateway, projectKnowledgeService)

    val httpPort = System.getenv("HTTP_PORT")?.toIntOrNull() ?: 8080
    val httpApiKey = System.getenv("HTTP_API_KEY")
        ?: error("HTTP_API_KEY env var not set")
    HttpApiServer(assistOrchestrator, reviewHandler, model, httpApiKey, httpPort).start()

    runBlocking {
        TelegramBotRunner(token, gateway, model, assistOrchestrator, projectKnowledgeService).start(this)
    }
}
