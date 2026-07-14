# Setup — Первоначальная настройка

## Требования

- Java 17+
- Gradle wrapper (`./gradlew`) — входит в репозиторий
- Ollama — только для режимов `index` и `question`

---

## API-ключи

### Цепочка поиска (`Config.kt`)

При каждом запросе ключ ищется в порядке:

1. `./local.properties` — рядом с точкой запуска
2. `../local.properties` — корень проекта (если запуск из `smartagent/`)
3. `~/.config/smartagent/local.properties` — пользовательский конфиг
4. Переменная окружения с тем же именем (`DEEPSEEK_STUDY_API_KEY`)
5. Переменная окружения без `_STUDY_` (`DEEPSEEK_API_KEY`)

Используется **первый найденный** файл целиком — остальные не читаются.

### Формат `local.properties`

```properties
DEEPSEEK_STUDY_API_KEY=sk-...
OPENROUTER_STUDY_API_KEY=sk-...
```

### Интерактивный ввод при первом запуске

Если ни один ключ не найден, агент предложит ввести их в терминале. Введённые значения проходят через `ApiKeySanitizer` (удаляет невидимые символы и не-ASCII) и сохраняются в `~/.config/smartagent/local.properties`.

```
No API keys found.
Enter DEEPSEEK_STUDY_API_KEY (or press Enter to skip): sk-...
Enter OPENROUTER_STUDY_API_KEY (or press Enter to skip): sk-...
Keys saved to ~/.config/smartagent/local.properties
```

### Какой ключ для чего

| Ключ | Используется |
|------|-------------|
| `DEEPSEEK_STUDY_API_KEY` | Модель `deepseek` (deepseek-v4-pro) |
| `OPENROUTER_STUDY_API_KEY` | Модели `qwen`, `qwen-low`, reranker (`rerank`) |

---

## Ollama (для RAG-режимов)

Нужен только если используются режимы `index` или `question`.

Ollama запускает модель эмбеддингов локально. По умолчанию:
- Endpoint: `http://localhost:11434/api/embed`
- Модель: `nomic-embed-text` (768-мерные векторы)

Если Ollama не запущен при `/index-run` или при вопросе в `question` режиме:
```
Embedding unavailable: Connection refused
```
Индексирование прерывается. Для question mode — ответ без контекста (или пустой список чанков).

---

## MCP-серверы (для assist режима)

Три HTTP MCP-сервера настраиваются через `local.properties`. Все опциональны.

| Сервер | Переменные | Запуск |
|--------|-----------|--------|
| `github` | `GITHUB_PERSONAL_ACCESS_TOKEN` | авто при старте CLI |
| `my-mcp` | `MCP_SERVER_URL_MY`, `MCP_API_KEY_MY` | вручную `/mcp my-mcp init` |
| `tavily-mcp` | `MCP_SERVER_URL_TAVILY`, `TAVILY_API_KEY` | вручную `/mcp tavily-mcp init` |

```properties
GITHUB_PERSONAL_ACCESS_TOKEN=ghp_...

MCP_SERVER_URL_MY=https://your-server.example.com/mcp
MCP_API_KEY_MY=your-bearer-token

MCP_SERVER_URL_TAVILY=https://mcp.tavily.com/mcp/
TAVILY_API_KEY=tvly-...
```

`github` требуется для `/init` в assist mode (клонирование репозитория). Сервер регистрируется и подключается автоматически при наличии токена.

---

## Зависимости по режимам

| Режим | DEEPSEEK ключ | OPENROUTER ключ | Ollama | MCP_SERVER_URL |
|-------|:---:|:---:|:---:|:---:|
| `question` | опционально | опционально | ✓ | — |
| `chat` | или/или | или/или | — | — |
| `code-analyzer` | или/или | или/или | — | — |
| `assist` | или/или | или/или | ✓ (для `/init`) | опционально |
| `index` | — | — | ✓ | — |
| `architect` | или/или | или/или | — | — |

"или/или" — нужен хотя бы один ключ, соответствующий выбранной модели.

---

## Быстрая установка CLI

```bash
bash smartagent/install.sh
```

Собирает fat JAR и устанавливает команду `smartreminder` в `~/.local/bin/`.

```bash
# Добавить в PATH (если ещё нет)
export PATH="$HOME/.local/bin:$PATH"

# Запуск
smartreminder
smartreminder --model qwen
smartreminder --repo /path/to/project
```

Удаление: `bash smartagent/uninstall.sh`
