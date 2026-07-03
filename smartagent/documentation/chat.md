# Chat Mode

Прямой диалог с LLM. Поддерживает многоходовую историю, автосжатие контекста, загрузку файлов и профиль пользователя.

Активируется: `/mode chat`

---

## Системный промпт

Определён в `ModelConfig.kt` как `CHAT_BASE_PROMPT`:

> Ты — персональный ассистент. Твоя задача — помочь пользователю решить любую его задачу. Отвечай по делу, не фантазируй. Предоставляй порядок своих рассуждений.

---

## Жизненный цикл сообщения

```
Пользователь вводит текст
        │
        ▼
ChatClient.sendMessage()
        │
        ├─ Проверить API-ключ (Config.apiKey)
        ├─ Запустить Spinner + EscCanceller
        │
        ├─ Оценить размер контекста (system + history + file context + input)
        ├─ SummaryAgent.compressIfNeeded(estimatedChars)
        │
        ├─ Сформировать messages: [system, assistant(history), timestamp, user]
        ├─ POST → model.url (OkHttp: connect 60s, read 120s, write 60s)
        │
        ├─ HTTP 400 + context overflow?
        │       └─ SummaryAgent.forceCompress() → retry (один раз)
        │
        ├─ Парсинг ответа: ChatResponse → StructuredResponse
        ├─ Вывод content с ANSI bold-форматированием
        ├─ Логирование: NetworkLogger → network.log
        ├─ Сохранение LogEntry в session + токенов в tokens.json
        │
        └─ Каждые 3 сообщения: ProfileAgent.update() (фоновый поток)
```

---

## Автосжатие контекста

Когда накопленный контекст приближается к лимиту, `SummaryAgent` сжимает историю в краткое резюме. Резюме подставляется в следующий запрос вместо полной истории.

- **compressIfNeeded** — срабатывает превентивно перед отправкой
- **forceCompress** — срабатывает при HTTP 400 с ошибкой context length

Системный промпт для сжатия: `prompts/summary/summary_system.md`

---

## Профиль пользователя

`ProfileAgent` обновляет `user_profile.md` каждые 3 сообщения в фоновом потоке. Профиль включается в system prompt как часть персонализации.

Промпт: `prompts/profile/profile_system.md`  
Файл: `~/.config/smartagent/user_profile.md` (просмотр: `/profile`)

---

## File Context

Файлы загружаются в контекст и передаются с каждым запросом до очистки.

| Команда | Действие |
|---------|----------|
| `/read <file>` | Загрузить файл (путь относительно `/repo`) |
| `/context` | Список загруженных файлов |
| `/context clear` | Очистить file context |

Файлы передаются отдельным user-сообщением перед вводом пользователя.

---

## Отмена запроса

`EscCanceller` слушает stdin в отдельном потоке. При нажатии ESC вызывает `OkHttpCall.cancel()` — запрос прерывается немедленно.

---

## Хранение данных

| Файл | Содержимое |
|------|-----------|
| `context.json` | История сессии (LogEntry[]) + текущий summary |
| `tokens.json` | Статистика токенов по запросам |
| `network.log` | Полный лог HTTP-вызовов (API-ключ замаскирован) |
| `user_profile.md` | Профиль пользователя (обновляется автоматически) |

---

## Команды

| Команда | Действие |
|---------|----------|
| `/clear`, `/new` | Очистить историю и file context |
| `/history` | Показать историю в JSON (userInput → payload → response) |
| `/totalTokens` | Статистика токенов |
| `/profile` | Показать user_profile.md |
