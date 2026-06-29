# smartagent-cli

Интерактивный JVM-чат с LLM. Запускается в терминале, поддерживает несколько режимов работы, MCP-инструменты и встроенный architect-агент для управления задачами.

## Запуск

```bash
# из папки smartagent/
./gradlew :cli:run --console=plain -q
./gradlew :cli:run --console=plain -q --args="--model qwen"
./gradlew :cli:run --console=plain -q --args="--repo /path/to/project"

# через скрипт (из корня SmartReminder/)
./smartagent/run.sh
./smartagent/run.sh --model qwen
```

## Аргументы CLI

| Флаг | Описание |
|------|----------|
| `--model <name>` | Стартовая модель: `deepseek`, `qwen`, `qwen-low` |
| `--repo <path>` | Путь к репозиторию (активирует `/files`, `/tree`, `/read`) |
| `assist` | Стартовый режим ASSIST (по умолчанию) |

Последняя использованная модель сохраняется в `~/.config/smartreminder/last_model` и восстанавливается при следующем запуске.

---

## Режимы работы (`AgentMode`)

Режим определяет, как обрабатывается пользовательский ввод. Переключается командой `/mode <name>`. При смене режима история чата очищается.

### `assist` (по умолчанию)
Agentic loop: LLM получает список инструментов подключённых MCP-серверов и самостоятельно решает, какие из них вызвать. Цикл повторяется до получения финального ответа. Маршрутизация: `ToolCallingAgent.handle()`.

### `chat`
Прямой чат с LLM. Системный промпт — персональный ассистент. Поддерживает скользящее окно истории, file context, автосжатие контекста. Маршрутизация: `ChatClient.sendMessage()`.

### `code-analyzer`
То же, что `chat`, но системный промпт настроен на ревью кода.

### `architect`
Многоагентный режим управления разработкой. Пользовательский ввод уходит в `ArchitectOrchestrator`, который классифицирует намерение и маршрутизирует по агентам. Подробнее — в разделе «Architect mode» ниже.

---

## Сущности и классы

### `Main.kt` — точка входа
`fun main(args)` — разбирает аргументы, инициализирует всё дерево зависимостей вручную (без DI-фреймворка), запускает REPL.

`fun runRepl(...)` — главный цикл: читает строку с stdin, матчит по паттернам команд (`/exit`, `/model`, `/mcp`, ...), для обычного текста маршрутизирует по текущему режиму.

### `ChatClient`
Отвечает за отправку одного сообщения в chat-режиме:

1. Проверяет наличие API-ключа
2. Запускает `Spinner` и `EscCanceller`
3. Оценивает размер контекста, вызывает `SummaryAgent.compressIfNeeded()`
4. Собирает сообщения: system prompt + context summary + история + file context + user input
5. Делает POST-запрос через OkHttp
6. При HTTP 400 с context-overflow — форсирует `SummaryAgent.forceCompress()` и повторяет
7. Парсит `StructuredResponse`, печатает ответ, сохраняет токены и `LogEntry`
8. Каждые 3 сообщения в фоновом потоке обновляет профиль через `ProfileAgent`

### `EscCanceller`
Слушает stdin в отдельном потоке. При нажатии ESC отменяет текущий OkHttp-вызов (`Call.cancel()`). Используется только в `ChatClient`.

---

## Architect mode

Режим для структурированного ведения разработки. Хранит состояние в файлах в папке `architect/` рядом с точкой запуска.

### Сущности предметной области

**`Feature`** (`architect/Feature.kt`)
Проект верхнего уровня. Поля: `id`, `title`, `summary`, `status` (ACTIVE / PAUSED / COMPLETED), временны́е метки. В один момент может быть только один ACTIVE проект.

**`Task`** (`architect/Task.kt`)
Задача внутри Feature. Поля: `id`, `featureId`, `title`, `status`, `stage`, `currentStep`, `expectedAction`, `summary`. Проходит через стадии: `PLANNING → EXECUTION → VALIDATION → DONE`. Паузы переводят в `PAUSED`.

### Репозитории

**`FeatureRepository`** — CRUD для Feature. Хранит в `architect/features/<id>.json`. Методы: `createFeature`, `getActiveFeature`, `switchFeature`, `pauseActiveFeature`, `resumeFeature`.

**`TaskRepository`** — CRUD для Task с FSM-валидацией переходов. Хранит в `architect/tasks/<id>.json`. При создании задачи автоматически паузирует предыдущую активную.

### Агенты Architect-режима

Все агенты принимают `SessionConfig`, `TokenTracker` и `LLMGateway`. Системные промпты загружаются из `prompts/architect/*.txt`.

**`IntentClassifier`** — определяет намерение пользователя: создать задачу, обновить текущую, переключить задачу или задать вопрос. Возвращает `ArchitectThought`.

**`InvariantAgent`** — хранит пользовательские ограничения (`architect/invariants/`). Проверяет каждый запрос на нарушение; при новом ограничении сохраняет его.

**`PlanningAgent`** — получает описание задачи, генерирует пошаговый план и сохраняет его в Task.

**`ExecutionAgent`** — ведёт пользователя по шагам плана, подтверждает выполнение каждого шага.

**`ValidationAgent`** — после завершения шагов валидирует результат, при необходимости возвращает на доработку.

**`ArchitectOrchestrator`** — центральный координатор: вызывает `InvariantAgent` → `IntentClassifier` → нужный агент → логирует в `ConversationHistory`.

**`ArchitectOnboarding`** — управляет долгосрочной памятью архитектора (`arch_settings.md`) и приветственными сообщениями при старте сессии.

---

## Команды REPL

### Чат и история
| Команда | Действие |
|---------|----------|
| `/clear`, `/new` | Очистить историю чата и file context |
| `/clearAll` | Удалить все данные проекта (с подтверждением) |
| `/history` | Показать историю в JSON |
| `/totalTokens` | Статистика токенов по запросам |
| `/profile` | Показать `user_profile.md` |

### Модели и режимы
| Команда | Действие |
|---------|----------|
| `/models` | Список доступных моделей с размером контекста |
| `/model <name>` | Переключить модель, сохранить как последнюю |
| `/mode` | Показать текущий режим |
| `/mode <name>` | Переключить режим (сбрасывает историю) |

### Репозиторий и анализ кода
| Команда | Действие |
|---------|----------|
| `/repo <path>` | Установить корень репозитория |
| `/files [pattern]` | Список файлов (опциональный фильтр) |
| `/tree [depth]` | ASCII-дерево файлов (по умолчанию глубина 3) |
| `/read <file>` | Загрузить файл в file context |
| `/context` | Список загруженных файлов |
| `/context clear` | Очистить file context |
| `/analyze <path> [prompt]` | Собрать все текстовые файлы из пути и отправить в LLM |

### Проект (Architect mode)
| Команда | Действие |
|---------|----------|
| `/features` | Список всех Feature |
| `/feature create <title>` | Создать Feature и сделать активной |
| `/feature switch <id>` | Переключить активный проект |
| `/feature state` | Обзор: активная Feature + задачи |
| `/feature pause` / `/feature resume` | Пауза/возобновление |

### MCP (любой режим)
| Команда | Действие |
|---------|----------|
| `/mcp list` | Список серверов и их статус |
| `/mcp <name> init` | Подключиться к серверу |
| `/mcp <name> tools` | Список инструментов сервера |
| `/mcp <name> stop` | Отключиться от сервера |

### Диагностика
| Команда | Действие |
|---------|----------|
| `/status` | Текущее состояние Feature и Task |
| `/classify <message>` | Прогнать через IntentClassifier без изменений |
| `/memory` | Показать `arch_settings.md` и рабочую память |
| `/debug tasks` | Все задачи в JSON |

---

## Хранение данных

| Файл | Содержимое |
|------|-----------|
| `context.json` | История чата и summary текущей сессии |
| `tokens.json` | История использования токенов |
| `user_profile.md` | Профиль пользователя (обновляется автоматически) |
| `architect/features/*.json` | Feature-сущности |
| `architect/tasks/*.json` | Task-сущности |
| `architect/invariants/` | Ограничения пользователя |
| `arch_settings.md` | Долгосрочная память architect-агента |
| `network.log` | Лог всех HTTP-запросов (API-ключи замаскированы) |

Рабочая директория при запуске через Gradle — `smartagent/`. Файлы создаются там же.

---

## Зависимости

`:cli` зависит от `:llm-client`, который предоставляет: `LLMGateway`, `OkHttpLLMGateway`, `ChatSession`, `ModelConfig`, `AgentMode`, `ToolCallingAgent`, `McpManager`, `SummaryAgent`, `ProfileAgent` и все DTO.
