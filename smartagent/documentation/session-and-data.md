# Session & Data — Сессия и персистентность

## Состояние сессии (`ChatSession`)

`ChatSession` — центральный объект, который собирает всё состояние и восстанавливается из файлов при запуске.

| Компонент | Класс | Что хранит |
|-----------|-------|-----------|
| `config` | `SessionConfig` | текущая модель, режим, путь к репо |
| `tokens` | `TokenTracker` | история токенов, `lastPromptTokens` |
| `history` | `ConversationHistory` | список `LogEntry`, текущий summary, счётчик сообщений |
| `profile` | `UserProfileStore` | `user_profile.md` |

При запуске `ChatSession` читает `context.json` и восстанавливает историю, summary и режим.

---

## Файлы данных

Все файлы создаются в рабочей директории при запуске (обычно `smartagent/`).

### `context.json`

История текущей сессии. Формат — `ContextFile`:

```json
{
  "history": [
    {
      "userInput": "Объясни как работает авторизация",
      "requestPayload": "{\"model\":\"deepseek-v4-pro\",\"messages\":[...]}",
      "apiResponse": "{\"content\":\"...\"}",
      "id": "uuid"
    }
  ],
  "summary": "Краткое резюме предыдущих обменов...",
  "currentMode": "chat",
  "totalTokens": 1234
}
```

**Что сохраняется между сессиями:** история (`history`), summary, режим. Модель восстанавливается из `~/.config/smartreminder/last_model`.  
**`/clear`** — очищает историю и summary в памяти и перезаписывает `context.json`.

### `tokens.json`

Список всех токен-расходов за сессию. Формат — `List<TokenEntry>`:

```json
[
  {"request": 1, "prompt": 1500, "completion": 320, "total": 1820},
  {"request": 2, "prompt": 2100, "completion": 180, "total": 2280}
]
```

Просмотр: `/totalTokens` — показывает каждую запись + суммарный итог.

### `user_profile.md`

Markdown-файл с профилем пользователя. Обновляется автоматически `ProfileAgent` каждые 3 сообщения в фоновом потоке. Содержимое — то, что LLM извлёк из последних 3 реплик пользователя (цели, предпочтения, контекст работы).

Просмотр: `/profile`  
Промпт обновления: `prompts/profile/profile_system.md`

### `network.log`

Полный лог всех HTTP-вызовов к LLM API. Формат записи:

```
[MAIN_AGENT]
=== 2026-07-03 17:45:12 ===
URL: https://api.deepseek.com/v1/chat/completions  [1823ms]
Tokens: prompt=1500 completion=320 total=1820
--- Request headers ---
  Authorization: Bearer ***
  Content-Type: application/json
--- Request body ---
{"model":"deepseek-v4-pro","messages":[...]}
--- Response: 200 ---
--- Response headers ---
  content-type: application/json
--- Response body ---
{"choices":[{"message":{"content":"..."}}],"usage":{...}}
========================================
```

**Source tags:** `[MAIN_AGENT]` — основной чат, `[SUMMARY_AGENT]` — сжатие контекста.  
**API-ключи всегда замаскированы:** `Bearer ***`.  
Путь: `cli/network.log` (если папка `cli/` существует) или `network.log`.  
`/clear` не очищает `network.log`. Очистить вручную или через `NetworkLogger.clear()`.

---

## Автосжатие контекста (`SummaryAgent`)

Когда история разрастается и начинает занимать значительную часть контекстного окна, `SummaryAgent` сжимает её в резюме.

### Порог срабатывания

```
shouldCompress = maxOf(lastPromptTokens, estimatedChars / 4) >= contextWindow * 0.04
```

| Модель | contextWindow | Порог (~4%) |
|--------|-------------|-------------|
| `deepseek`, `qwen` | 1 000 000 | ~40 000 токенов |
| `qwen-low` | 131 000 | ~5 240 токенов |

`estimatedChars / 4` — грубая оценка токенов по символам (1 токен ≈ 4 символа).

### Два сценария сжатия

**`compressIfNeeded`** — вызывается перед каждым запросом. Если порог не превышен — ничего не делает.

**`forceCompress`** — вызывается при HTTP 400 с ошибкой context overflow (когда API отклонил запрос). Гарантирует сжатие независимо от порога.

### Что происходит при сжатии

1. Берётся текущее summary + несколько последних `LogEntry` из истории
2. Отправляется в LLM с промптом из `prompts/summary/summary_system.md`
3. Новый summary заменяет старый в сессии
4. Сжатые `LogEntry` удаляются из истории
5. Изменения сохраняются в `context.json`

---

## Карта всех файлов

| Файл | Создаётся | Очищается |
|------|----------|----------|
| `context.json` | автоматически | `/clear` |
| `tokens.json` | автоматически | `/clear` |
| `user_profile.md` | автоматически | вручную |
| `network.log` | автоматически | вручную |
| `architect/features/*.json` | в architect режиме | `/clearAll` |
| `architect/tasks/*.json` | в architect режиме | `/clearAll` |
| `architect/invariants/` | в architect режиме | `/clearAll` |
| `arch_settings.md` | в architect режиме | `/clearAll` |
| `.indexed/*.json` | после `/index-run` | вручную |
| `~/.config/smartagent/local.properties` | при первом запуске | вручную |
| `~/.config/smartreminder/last_model` | при смене модели | вручную |
