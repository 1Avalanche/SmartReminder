# llm-client

Shared JVM-библиотека. Нет `main` — только API для CLI и Telegram.

Предоставляет: LLM-gateway, RAG-pipeline, MCP-интеграцию, agentic tool-calling, управление контекстом.

## Зависимости

```
:cli       ──┐
             ├── :llm-client
:telegram  ──┘
```

## Ключевые компоненты

### Gateway & Модели

| Класс | Описание |
|-------|----------|
| `LLMGateway` | Интерфейс: `chat(messages, model, options) → Response` |
| `OkHttpLLMGateway` | Реализация через OkHttp: OpenRouter + Ollama |
| `ModelConfig` | Enum всех моделей: shortName, apiModelId, url, contextWindow, isLocal |
| `AgentMode` | Enum режимов: CHAT, CODE_ANALYZER, ARCHITECT, ASSIST, INDEX, QUESTION |
| `ChatSetting` | Настройка chat-режима: NO / OPTIMUM |
| `ApiDtos` | DTO: `Message`, `OllamaOptions`, `Usage`, `StructuredResponse` |

### Сессия и история

| Класс | Описание |
|-------|----------|
| `ChatSession` | Статeful-сессия: текущий режим, модель, история, file context |
| `SessionConfig` | Конфигурация запроса: model, maxTokens, systemPrompt |
| `ConversationHistory` | Журнал сообщений с метаданными (`LogEntry`) |
| `TokenTracker` | Учёт токенов по запросам, сохранение в `tokens.json` |
| `Config` | Загрузка API-ключей: `local.properties` → env vars |

### RAG-pipeline

```
FileScanner → RepositoryDocumentLoader → Chunker → OllamaEmbeddingGenerator → InMemoryVectorStore
                                                                                      ↓
                                                               JsonVectorIndexPersistence (.indexed/)
```

| Класс | Описание |
|-------|----------|
| `FileScanner` | Рекурсивный поиск файлов по расширениям |
| `RepositoryDocumentLoader` | Загружает файлы репозитория как `Document` |
| `FixedChunker` | Чанкинг фиксированного размера (500 символов, 10% overlap) |
| `StructuredChunker` | Семантический чанкинг по заголовкам/секциям |
| `OllamaEmbeddingGenerator` | HTTP к Ollama `/api/embed` (модель: `nomic-embed-text`) |
| `InMemoryVectorStore` | In-memory векторный поиск (cosine similarity) |
| `JsonVectorIndexPersistence` | Сериализация индекса в JSON |
| `Indexer` | Оркестратор: chunking + embedding + сохранение |
| `DefaultTextNormalizer` | Нормализация текста перед индексированием |

### Tool-calling (agentic loop)

| Класс | Описание |
|-------|----------|
| `ToolCallingAgent` | Точка входа: `handle(query, gateway, model, chatId, options)` |
| `ToolCallingLoop` | Цикл: LLM решает вызвать tool → execute → повтор (до 5 итераций) |
| `ToolCallDecision` | Парсинг JSON-ответа LLM: какие инструменты вызвать |
| `ToolExecutionResult` | Результат вызова: успех / ошибка |
| `ToolFailureEngine` | Обработка ошибок tool-calling |
| `ToolSchemaFormatter` | Форматирует OpenAPI-схему инструментов в промпт |
| `OutputValidator` | Валидация JSON-ответа LLM |

### MCP (Model Context Protocol)

| Класс | Описание |
|-------|----------|
| `McpManager` | Singleton: регистрация и подключение к MCP-серверам |
| `McpSession` | Активная сессия с сервером |
| `McpClient` | Низкоуровневый JSON-RPC клиент |
| `ProcessTransport` | Stdio-транспорт к локальному MCP-процессу |
| `McpHttpTransport` | HTTP-транспорт к удалённому MCP-серверу |
| `McpToolRouter` | Маршрутизация tool calls по серверам |
| `SchemaRenderer` | Схема инструментов → markdown для LLM |
| `ToolResultRenderer` | Форматирование результатов tool-calls |

### Управление контекстом

| Класс | Описание |
|-------|----------|
| `ContextWindowGuard` | Следит чтобы сообщения влезали в `contextWindow` |
| `MessageSummarizer` | Сжимает старые сообщения для экономии токенов |
| `SummaryAgent` | High-level: `compressIfNeeded()`, `forceCompress()` |
| `ProfileAgent` | Обновляет `user_profile.md` каждые 3 сообщения |

### Утилиты

| Класс | Описание |
|-------|----------|
| `NetworkLogger` | Логирует HTTP-трафик в `network.log` (ключи замаскированы) |
| `ApiKeySanitizer` | Очищает ключи от невидимых символов |
| `Spinner` | ANSI-спиннер для CLI |
| `Colors` | ANSI-цвета для вывода в терминал |

## Как добавить модель

В `ModelConfig.kt`:

```kotlin
MY_MODEL(
    shortName = "my-model",
    description = "Описание",
    apiModelId = "provider/model-name",
    apiKeyProperty = "MY_API_KEY",
    url = "https://api.provider.com/v1/chat/completions",
    contextWindow = 128_000,
    aliases = listOf("alias1"),
    isLocal = false   // true для Ollama-моделей
)
```

Ключ нужно добавить в `local.properties`. Модель автоматически появится в `/models` в CLI.

## Тесты

40+ тест-файлов покрывают все слои: чанкинг, индексирование, векторный поиск, MCP, tool-calling, управление контекстом.

```bash
cd smartagent && ./gradlew :llm-client:test
```
