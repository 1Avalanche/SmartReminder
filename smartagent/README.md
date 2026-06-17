# SmartAgent

CLI-чат-агент на Kotlin/JVM с поддержкой нескольких моделей и режимов работы.

## Запуск

```bash
# через скрипт (модель по умолчанию: deepseek)
./smartagent/run.sh

# с указанием модели
./smartagent/run.sh --model qwen

# с привязкой репозитория
./smartagent/run.sh --repo /path/to/project

# напрямую через Gradle
./gradlew :smartagent:run --args="--model deepseek --repo ."
```

## Настройка API-ключей

При первом запуске агент предложит ввести ключи интерактивно — они сохранятся в `~/.config/smartagent/local.properties`.

Либо задать вручную в `local.properties` в корне проекта:

```properties
DEEPSEEK_STUDY_API_KEY=sk-...
OPENROUTER_STUDY_API_KEY=sk-...
```

Поиск ключей: `local.properties` → `../local.properties` → `~/.config/smartagent/local.properties` → переменные окружения.

## Модели

| Имя        | Модель               | Контекст      | API        |
|------------|----------------------|---------------|------------|
| `deepseek` | deepseek-v4-pro      | 1 000 000     | DeepSeek   |
| `qwen`     | qwen/qwen3.7-plus    | 1 000 000     | OpenRouter |
| `qwen-low` | qwen/qwen3-8b        | 131 000       | OpenRouter |

## Режимы работы

Переключаются командой `/mode <name>`. При смене режима история очищается.

| Режим          | Описание                                                        |
|----------------|-----------------------------------------------------------------|
| `chat`         | Обычный чат-ассистент (режим по умолчанию)                      |
| `code-analyzer`| Анализ кода: баги, уязвимости, рефакторинг, code review        |
| `architect`    | Архитектурные решения с онбордингом и долговременной памятью    |

## Команды

| Команда                     | Описание                                                  |
|-----------------------------|-----------------------------------------------------------|
| `/help`                     | Показать справку                                          |
| `/exit`, `/quit`            | Выйти                                                     |
| `/models`                   | Список доступных моделей                                  |
| `/model <name>`             | Переключить модель                                        |
| `/mode`                     | Показать текущий режим                                    |
| `/mode <name>`              | Переключить режим (chat, code-analyzer, architect)        |
| `/history`, `/hist`         | История сессии (userInput → payload → response)           |
| `/clear`, `/new`            | Очистить историю, файловый контекст и network.log         |
| `/clearAll`                 | Удалить все данные architect-режима (с подтверждением)    |
| `/repo`                     | Показать текущий путь к репозиторию                       |
| `/repo <path>`              | Привязать репозиторий                                     |
| `/files [pattern]`          | Список файлов репозитория (опциональная фильтрация)       |
| `/tree [depth]`             | Дерево файлов (глубина по умолчанию: 3)                   |
| `/read <file>`              | Загрузить файл в контекст (относительно корня репо)       |
| `/context`                  | Показать файлы в текущем контексте                        |
| `/context clear`            | Очистить файловый контекст                                |
| `/analyze <path> [prompt]`  | Собрать все текстовые файлы из пути и отправить на анализ |
| `/totalTokens`              | Статистика токенов по запросам                            |
| `/memory`                   | Показать arch_settings.md и arch_tasks.json (architect)   |

## Архитектура

```
smartagent/src/main/kotlin/smartagent/
├── Main.kt               — точка входа, парсинг аргументов, REPL-цикл, все команды
├── ChatClient.kt         — HTTP-клиент для режимов chat и code-analyzer
├── ArchitectClient.kt    — HTTP-клиент для architect-режима
├── ArchitectOnboarding.kt— онбординг, долговременная и рабочая память
├── ChatSession.kt        — состояние сессии: история, режим, модель, файловый контекст
├── ModelConfig.kt        — enum моделей + enum AgentMode
├── RepoContext.kt        — работа с файловой системой репозитория
├── ApiDtos.kt            — @Serializable DTO, Json-инстансы
├── Config.kt             — чтение local.properties, хранение последней модели
├── NetworkLogger.kt      — логирование HTTP-вызовов в network.log
├── Spinner.kt            — анимация загрузки в терминале
└── Colors.kt             — ANSI-константы
```

## Как это работает

### Контекст (chat / code-analyzer)

Каждый запрос содержит system-prompt режима и полную историю сессии: для каждого обмена передаётся вопрос пользователя (`userInput`) и ответ модели (`content`). Файлы, загруженные через `/read`, передаются отдельным user-сообщением.

Формат ответа модели:
```json
{ "content": "..." }
```
Пользователю показывается `content`.

### Architect-режим

При первом запуске проходит онбординг (вопросы из `prompts/architect/questions.json`). Ответы сохраняются в `arch_settings.md` и включаются в system-prompt каждого запроса.

Модель возвращает JSON с полями:
```json
{ "content": "...", "decision": "...", "currentTask": "название: описание" }
```
- `decision` — автоматически дописывается в `arch_settings.md`  
- `currentTask` — upsert в `arch_tasks.json`

История не сбрасывается между сообщениями в рамках сессии; `/clear` сбрасывает сессию, `/clearAll` удаляет оба файла памяти.

### Персистентность сессии

- `context.json` — история и текущий режим (восстанавливается при следующем запуске)
- `tokens.json` — статистика токенов
- `network.log` — полный лог HTTP-вызовов (API-ключ маскируется, файл в `.gitignore`)
- `~/.config/smartreminder/last_model` — последняя использованная модель

## Требования

- Java 11+
- Gradle wrapper (`./gradlew`)
