# Telegram Bot

Telegram-бот, который предоставляет доступ к `assist` режиму (ToolCallingAgent + MCP) через Telegram-сообщения.

---

## Запуск

```bash
# Через Gradle
TELEGRAM_BOT_TOKEN=xxx TELEGRAM_AUTH_KEY=yyy cd smartagent && ./gradlew :telegram:run

# Через shadow JAR
cd smartagent && ./gradlew :telegram:shadowJar
TELEGRAM_BOT_TOKEN=xxx TELEGRAM_AUTH_KEY=yyy java -jar telegram/build/libs/smartagent-telegram.jar
```

Обе переменные обязательны. При первом сообщении бот запрашивает ключ авторизации (значение `TELEGRAM_AUTH_KEY`).

---

## Как работает

```
Telegram API
    │  long-polling /getUpdates
    ▼
TelegramBotRunner
    │  для каждого update.message.text
    ├─ ToolCallingAgent.handle(query, gateway, model, chatId)
    │       └─ agentic loop (до 5 итераций с MCP-инструментами)
    └─ TelegramApiClient.sendMessage(chatId, answer)
```

Бот использует **long-polling** — постоянно опрашивает Telegram API (`/getUpdates`) в бесконечном цикле. Streaming нет — ответ отправляется целиком после завершения agentic loop.

Сообщения обрабатываются **последовательно** (не параллельно). Это гарантирует согласованность общей истории `ToolCallingAgent`.

---

## Модель и конфигурация

Жёстко задан `ModelConfig.TG_TUNNEL` (gemma3:12b через Ollama на порту 11435). Модель нельзя переключить через Telegram — нет REPL-команд.

API-ключи читаются из `local.properties` по той же цепочке, что и в CLI (`./` → `../` → `~/.config/smartagent/`).

MCP-серверы инициализируются при запуске `TelegramMain` (через `McpManager.initRemoteServers()`).

---

## Ограничения

- **Нет REPL-команд**: `/model`, `/mode`, `/clear`, `/mcp` — не работают. Боту можно только писать сообщения.
- **Один глобальный `ToolCallingAgent`**: история общая для всех чатов. Разные пользователи видят одну и ту же историю инструментальных вызовов. `chatId` передаётся в `ToolCallingLoop` для логирования, но не создаёт изолированных историй.
- **Нет персистентности**: история хранится в памяти. После рестарта бота — обнуляется.
- **Только текст**: медиа, файлы и inline-клавиатуры не поддерживаются.
- **Нет streaming**: пользователь ждёт полного ответа (в том числе всех итераций tool-calling).

---

## Модули

| Файл | Описание |
|------|----------|
| `TelegramMain.kt` | Точка входа, читает `TELEGRAM_BOT_TOKEN` + `TELEGRAM_AUTH_KEY`, запускает `TelegramBotRunner` |
| `bot/TelegramBotRunner.kt` | Основной цикл: long-polling + авторизация + маршрутизация в `ToolCallingAgent` |
| `auth/AuthManager.kt` | Авторизация по ключу: `requestAuth`, `tryAuthorize`, `isAuthorized` |
| `client/TelegramApiClient.kt` | HTTP-клиент к Telegram Bot API (`getUpdates`, `sendMessage`) |
| `client/TelegramDtos.kt` | DTO: `TelegramUpdate`, `TelegramMessage`, `TelegramChat`, `GetUpdatesResponse`, `SendMessageRequest` |
