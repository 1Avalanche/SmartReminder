# Assist Mode — что реализовано

Assist — режим агента с tool-calling через MCP. Агент сам выбирает инструменты, вызывает их и итерирует до финального ответа.

---

## Запуск

```bash
./smartagent/run.sh assist          # сразу в assist mode
# или внутри REPL:
/mode assist
```

При входе в режим: автоматически выполняется `/mcp github-remote init` (подключение remote HTTP-сервера, если задан `MCP_SERVER_URL`).

---

## Архитектура: слои

```
User input
    │
    ▼
ToolCallingAgent          ← точка входа (объект-синглтон)
    │  держит history: List<Message>
    │
    ▼
ToolCallingLoop           ← agentic loop (до 5 итераций)
    │  строит system prompt со схемой инструментов
    │  парсит ответ LLM → ToolCallDecision
    │
    ├──► LLMGateway.chat()        ← вызов LLM API
    │        └── OkHttpLLMGateway  (OkHttp + JSON-RPC-style chat)
    │
    └──► McpSession.callTool()    ← вызов MCP-инструмента
             └── McpClient         (JSON-RPC поверх транспорта)
                     └── McpTransport
                             ├── ProcessTransport   (stdio, subprocess)
                             └── McpHttpTransport   (HTTP POST)
```

---

## Компоненты

### ToolCallingAgent (`agent/toolcalling/ToolCallingAgent.kt`)

- Ищет первый подключённый MCP-сервер через `McpManager`
- Создаёт `ToolCallingLoop`, запускает, получает ответ
- Хранит `history: List<Message>` между сообщениями сессии
- `/clear` сбрасывает историю

### ToolCallingLoop (`agent/toolcalling/ToolCallingLoop.kt`)

Главный agentic loop:

1. Запрашивает список инструментов у сервера (`session.listTools()`)
2. Форматирует схему инструментов в текст для system prompt (`formatToolsForPrompt`)
3. Строит сообщения: `[system, ...priorHistory, user]`
4. Вызывает `gateway.chat()` → парсит ответ LLM → `ToolCallDecision`
5. Если `CallTool` — вызывает инструмент, добавляет результат в messages, повторяет
6. Если `FinalAnswer` — возвращает текст пользователю
7. Лимит: 5 итераций (hardcoded в `maxIterations`)

**System prompt** объясняет LLM два возможных формата ответа:
```
TOOL_CALL
tool=<name>
arguments={"key": "value"}

FINAL_ANSWER
<текст ответа>
```

### ToolCallDecision (`agent/toolcalling/ToolCallDecision.kt`)

Sealed class — результат парсинга LLM-ответа:
- `CallTool(toolName, arguments: JsonObject)` — нужен вызов инструмента
- `FinalAnswer(text)` — готовый ответ
- `ParseError(raw)` — не распознан формат → трактуется как финальный ответ

---

## MCP-слой

### McpManager (`mcp_handler/McpManager.kt`)

Синглтон-реестр. Хранит конфигурации и активные сессии.

**Встроенные серверы:**
| Имя | Транспорт | Команда / URL |
|-----|-----------|---------------|
| `filesystem` | PROCESS | `npx -y @modelcontextprotocol/server-filesystem <cwd>` |
| `github-remote` | HTTP | `MCP_SERVER_URL` из `local.properties` или env |

`github-remote` регистрируется только если `MCP_SERVER_URL` задан.

Методы: `initServer(name)`, `getSession(name)`, `isConnected(name)`, `shutdown()`.

### McpSession (`mcp_handler/McpSession.kt`)

Обёртка над `McpClient` + `McpTransport`. Lifecycle: DISCONNECTED → CONNECTING → CONNECTED.

- `connect()` — создаёт транспорт, вызывает `McpClient.initialize()` (MCP handshake)
- `listTools()` / `callTool(name, args)` — делегирует в `McpClient`
- `drainServerOutput()` — читает stderr после старта процесса (для `ProcessTransport`)

### McpClient (`mcp_handler/McpClient.kt`)

JSON-RPC клиент поверх `McpTransport`.

- `initialize()` — MCP handshake: `initialize` запрос + `notifications/initialized` уведомление
- `listTools()` → `tools/list`
- `callTool(name, args)` → `tools/call`
- `waitForResponse(id)` — блокирует до ответа с нужным id (таймаут 15 сек), пропускает notifications

### McpTransport (интерфейс)

```kotlin
interface McpTransport : AutoCloseable {
    fun send(message: String)
    fun pollLine(timeoutMs: Long): String?
}
```

**ProcessTransport** — stdio-subprocess:
- Запускает процесс через `ProcessBuilder`
- stdout сервера читается в фоновом треде → `responseQueue` (LinkedBlockingQueue)
- stderr буферизуется в `stderrQueue`, дренируется после коннекта
- `send()` пишет в stdin процесса

**McpHttpTransport** — HTTP:
- Каждый JSON-RPC запрос = HTTP POST на `serverUrl`
- `Authorization: Bearer <apiKey>` (если задан `MCP_API_KEY`)
- Notifications (без `id`) — fire-and-forget, ответ не ожидается
- Ответы кладёт в `responseQueue` — тот же интерфейс, что и у `ProcessTransport`

### AssistRepl (`mcp_handler/AssistRepl.kt`)

REPL для ручного управления серверами. Доступен в любом режиме через `/mcp`.

| Команда | Действие |
|---------|---------|
| `/mcp list` | Список серверов и их статус (connected/disconnected, transport) |
| `/mcp <name> init` | Подключить сервер |
| `/mcp <name> tools` | Список инструментов с параметрами |
| `/mcp <name> tool <tool> [key=value]` | Вызвать инструмент вручную |
| `/mcp <name> stop` | Отключить сервер |

---

## LLM API

### LLMGateway (интерфейс)

```kotlin
interface LLMGateway {
    data class Response(val content: String, val usage: Usage? = null)
    fun chat(messages: List<Message>, model: ModelConfig, source: String = ""): Response?
}
```

### OkHttpLLMGateway

Реализация: OkHttp, POST на `model.url` с `Authorization: Bearer <apiKey>`.

- Таймауты: connect 60s, read 120s, write 60s
- Логирует каждый запрос в `smartagent/network.log` (API-ключ маскируется)
- Возвращает `null` при сетевой ошибке или не-2xx ответе

**Модели для Assist mode** — любая из `ModelConfig`. По умолчанию DeepSeek (или последняя использованная, сохранённая в `~/.config/smartagent/`).

---

## Конфигурация

В `local.properties` (корень проекта или `~/.config/smartagent/`):

```properties
DEEPSEEK_STUDY_API_KEY=sk-...
OPENROUTER_STUDY_API_KEY=sk-...
MCP_SERVER_URL=https://your-server/mcp   # для github-remote
MCP_API_KEY=...                          # Bearer token для HTTP MCP
```

---

## Что НЕ реализовано

- Автоматический выбор сервера (берётся первый подключённый)
- Параллельные tool calls (итерации строго последовательны)
- Streaming LLM-ответов
- Передача аргументов инструментов сложных типов (только `Map<String, String>`)
- Автоматический reconnect при обрыве соединения
