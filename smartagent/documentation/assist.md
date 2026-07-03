# Assist Mode

Agentic loop с MCP-инструментами. LLM самостоятельно выбирает инструменты, вызывает их и итерирует до получения финального ответа.

Активируется: `/mode assist` или `./smartagent/run.sh assist`

---

## Как работает agentic loop

```
Пользователь вводит запрос
        │
        ▼
ToolCallingAgent.handle()
        │
        ▼
ToolCallingLoop (до 5 итераций)
        │
        ├─ session.listTools() — получить схемы инструментов от MCP-сервера
        ├─ Сформировать system prompt с описанием инструментов
        ├─ gateway.chat([system, ...history, user]) → LLM-ответ
        │
        ├─ Парсинг ответа → ToolCallDecision:
        │       ├─ CallTool(name, args) → McpSession.callTool() → результат в messages → следующая итерация
        │       ├─ FinalAnswer(text)   → вернуть пользователю
        │       └─ ParseError(raw)     → трактовать как FinalAnswer
        │
        └─ Если 5 итераций исчерпаны — вернуть последний ответ
```

**Формат ответа LLM** (две разрешённые формы):
```
TOOL_CALL
tool=<name>
arguments={"key": "value"}
```
```
FINAL_ANSWER
<текст ответа>
```

---

## MCP-серверы

### Встроенные серверы

| Имя | Транспорт | Запуск |
|-----|-----------|--------|
| `filesystem` | PROCESS (stdio) | `npx -y @modelcontextprotocol/server-filesystem <cwd>` |
| `github-remote` | HTTP | `MCP_SERVER_URL` из local.properties или env |

`github-remote` регистрируется только если `MCP_SERVER_URL` задан.

### Транспорты

**ProcessTransport** — запускает подпроцесс. stdout сервера читается в фоновом потоке в `responseQueue`. Stderr дренируется после коннекта.

**McpHttpTransport** — каждый JSON-RPC запрос = HTTP POST. `Authorization: Bearer <MCP_API_KEY>` (если задан). Notifications (без `id`) — fire-and-forget.

### Lifecycle подключения

```
DISCONNECTED → CONNECTING → CONNECTED
```
- `connect()` — MCP handshake: `initialize` + `notifications/initialized`
- `listTools()` → `tools/list`
- `callTool(name, args)` → `tools/call`
- Таймаут ответа: 15 секунд

---

## История и состояние

`ToolCallingAgent` хранит `history: List<Message>` между сообщениями сессии. `/clear` сбрасывает эту историю. Состояние не персистируется между запусками.

---

## Конфигурация

В `local.properties` (корень проекта или `~/.config/smartagent/`):

```properties
MCP_SERVER_URL=https://your-server/mcp   # для github-remote
MCP_API_KEY=secret                       # Bearer token для HTTP MCP
```

---

## Команды управления серверами (`/mcp`)

Доступны в любом режиме.

| Команда | Действие |
|---------|----------|
| `/mcp list` | Список серверов, статус (connected/disconnected) и транспорт |
| `/mcp <name> init` | Подключиться к серверу |
| `/mcp <name> tools` | Список инструментов с параметрами |
| `/mcp <name> tool <tool> [key=value ...]` | Вызвать инструмент вручную |
| `/mcp <name> stop` | Отключиться от сервера |

---

## Ограничения

- Автоматически берётся первый подключённый сервер (нет выбора сервера)
- Tool calls строго последовательны (нет параллельных вызовов)
- Аргументы инструментов только `Map<String, String>` (нет вложенных объектов)
- Нет автоматического reconnect при обрыве
- Нет streaming LLM-ответов
