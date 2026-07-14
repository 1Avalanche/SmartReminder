# smartagent

JVM-агент с LLM, MCP и RAG. Composite Gradle-build, три модуля.

## Модули

| Модуль | Описание |
|--------|----------|
| `:llm-client` | Shared-библиотека: LLMGateway, ChatSession, RAG, MCP, tool-calling |
| `:cli` | Интерактивный REPL-чат в терминале |
| `:telegram` | Telegram-бот (assist-режим через MCP) |

## Быстрый старт

```bash
# CLI — запуск из корня SmartReminder/
./smartagent/run.sh
./smartagent/run.sh --model qwen
./smartagent/run.sh --repo /path/to/project

# CLI — установить как команду smartreminder
bash smartagent/install.sh

# Telegram-бот
TELEGRAM_BOT_TOKEN=xxx TELEGRAM_AUTH_KEY=yyy \
  cd smartagent && ./gradlew :telegram:run

# Shadow JAR-ы
cd smartagent && ./gradlew :cli:shadowJar      # → cli/build/libs/smartagent-cli.jar
cd smartagent && ./gradlew :telegram:shadowJar # → telegram/build/libs/smartagent-telegram.jar
```

Требует Java 17+.

## API-ключи

В `local.properties` в корне проекта или в `smartagent/`:

```properties
DEEPSEEK_STUDY_API_KEY=sk-...
OPENROUTER_STUDY_API_KEY=sk-...
```

При первом запуске CLI предложит ввести ключи интерактивно и сохранит в `~/.config/smartagent/local.properties`.

Локальные модели (`qwen-local`, `gemma-local`) требуют Ollama — ключи не нужны.

## Режимы CLI

| Режим | Описание |
|-------|----------|
| `question` | RAG-поиск по индексу + LLM (по умолчанию) |
| `chat` | Прямой диалог с LLM |
| `code-analyzer` | Chat с промптом для ревью кода |
| `assist` | RAG doc Q&A по проиндексированному репозиторию + agentic loop |
| `index` | Индексирование файлов для RAG |
| `architect` | Многоагентный менеджмент задач |

## Документация

| Файл | Тема |
|------|------|
| [cli/README.md](cli/README.md) | Полный справочник команд REPL |
| [documentation/setup.md](documentation/setup.md) | API-ключи, Ollama, MCP, установка |
| [documentation/models.md](documentation/models.md) | Модели, псевдонимы, добавление новых |
| [documentation/chat.md](documentation/chat.md) | Chat-режим, chat-setting, ScenarioRunner |
| [documentation/question.md](documentation/question.md) | RAG, стратегии поиска |
| [documentation/rag-internals.md](documentation/rag-internals.md) | Чанкинг, векторный поиск, rerank |
| [documentation/assist.md](documentation/assist.md) | Tool-calling, MCP-инструменты |
| [documentation/architect.md](documentation/architect.md) | Architect-режим, Feature/Task, агенты |
| [documentation/telegram.md](documentation/telegram.md) | Telegram-бот, авторизация, ограничения |
| [documentation/session-and-data.md](documentation/session-and-data.md) | Файлы данных, история, токены |
