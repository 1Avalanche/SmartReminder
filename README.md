# SmartReminder

Учебный Kotlin Multiplatform проект. Android и iOS приложение + самостоятельный JVM CLI-чат с LLM.

## Модули

| Модуль | Описание |
|---|---|
| `:androidApp` | Android-приложение, UI на Compose — [README](androidApp/README.md) |
| `:shared` | Общий Compose Multiplatform код (Android + iOS) |
| `:llm-client` | Shared JVM-библиотека: LLM, RAG, MCP, tool-calling — [README](smartagent/llm-client/README.md) |
| `:cli` | Консольный LLM-чат, работает независимо от мобильных модулей |
| `:telegram` | Telegram-бот, assist-режим через MCP |

## CLI — установка и запуск

### Быстрая установка

Требует Java 17+.

Полный список команд и описание режимов — в [smartagent/cli/README.md](smartagent/cli/README.md).

```bash
bash smartagent/install.sh
```

Скрипт собирает fat JAR и устанавливает `smartreminder` в `~/.local/bin/`.

Если `~/.local/bin` нет в `PATH`, добавить в `~/.zshrc` или `~/.bashrc`:

```bash
export PATH="$HOME/.local/bin:$PATH"
```

### Запуск после установки

```bash
smartreminder                          # deepseek по умолчанию
smartreminder --model qwen             # выбрать модель
smartreminder --repo /path/to/repo     # анализ кода в репозитории
```

### Удаление

```bash
bash smartagent/uninstall.sh
```

### Запуск без установки (из репозитория)

```bash
./smartagent/run.sh
./smartagent/run.sh --model qwen
./smartagent/run.sh --repo /path/to/repo
```

### API-ключи

Добавить в `local.properties` в корне проекта:

```properties
DEEPSEEK_STUDY_API_KEY=sk-...
OPENROUTER_STUDY_API_KEY=sk-...
```

Или передать через переменные окружения с теми же именами.

### Модели

| Имя | Описание |
|---|---|
| `deepseek` | deepseek-v4-pro (по умолчанию) |
| `qwen` | qwen/qwen3.7-plus |
| `qwen-low` | qwen/qwen3-8b, быстрее и дешевле |
| `qwen-local` | qwen2.5:14b — локально через Ollama |
| `gemma-local` | gemma3:12b — локально через Ollama |

### Режимы работы

| Режим | Описание |
|---|---|
| `question` | RAG-поиск по индексу + LLM (по умолчанию) |
| `chat` | Прямой чат с LLM |
| `code-analyzer` | Ревью кода, поиск багов, рефакторинг |
| `assist` | Agentic loop с MCP-инструментами |
| `index` | Индексирование файлов репозитория для RAG |
| `architect` | Управление задачами через многоагентный pipeline |

### Основные команды

```
/help                  — полная справка
/mode <name>           — сменить режим
/models                — список моделей
/model <name>          — сменить модель
/repo <path>           — установить репозиторий
/files [pattern]       — список файлов в репозитории
/tree [depth]          — дерево файлов (по умолчанию глубина 3)
/read <file>           — загрузить файл в контекст
/clear                 — очистить историю и контекст
/exit                  — выход
```

### Анализ кода

```bash
smartreminder --repo ~/projects/my-app
```

```
> /tree
> /files .kt
> /read src/main/kotlin/App.kt
> /read src/main/kotlin/Database.kt
> объясни как работает авторизация
```

## Telegram-бот

Предоставляет доступ к `assist`-режиму (ToolCallingAgent + MCP) через Telegram.

### Запуск

```bash
# Через Gradle
TELEGRAM_BOT_TOKEN=xxx TELEGRAM_AUTH_KEY=yyy cd smartagent && ./gradlew :telegram:run

# Shadow JAR
cd smartagent && ./gradlew :telegram:shadowJar
TELEGRAM_BOT_TOKEN=xxx TELEGRAM_AUTH_KEY=yyy java -jar telegram/build/libs/smartagent-telegram.jar
```

Обе переменные окружения обязательны. Авторизация: при первом сообщении бот запрашивает ключ (значение `TELEGRAM_AUTH_KEY`).

### Ограничения

- Нет REPL-команд: `/model`, `/mode`, `/clear` не работают
- История общая для всех чатов, не персистируется между рестартами
- Только текст, нет streaming

## Мобильные приложения

### Сборка и запуск

```bash
# Android debug APK
./gradlew :androidApp:assembleDebug

# iOS — открыть iosApp/ в Xcode и запустить оттуда
```

### Тесты

```bash
./gradlew :shared:testAndroidHostTest      # Android (JVM)
./gradlew :shared:iosSimulatorArm64Test    # iOS симулятор
./gradlew :shared:allTests                 # все таргеты
```

---

[Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)
