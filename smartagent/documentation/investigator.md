# Investigator

CLI-агент для анализа data flow: по UI-элементу находит API-метод и источник данных в репозитории канала.

---

## Запуск

```bash
cd smartagent && ./gradlew :investigator:run
```

Требования перед запуском:
- Docker Desktop запущен
- `investigator/.properties` заполнен (см. `.properties.example`)
- `channels.json` в корне проекта

---

## Конфигурация

`smartagent/investigator/.properties`:

```properties
GITHUB_CORP_TOKEN=ghp_...
GITHUB_CORP_HOST=https://github.example.com
INVASTIGATOR_OWNERR=my-org
UI_REPO=my-ui-repo
GPU_STACK_API_KEY=...
GPU_STACK_URL=https://<gpu-stack-host>/v1
```

`channels.json` в корне репозитория — маппинг алиасов каналов на репозитории:

```json
[
  { "alias": ["peach", "pch"], "repoName": "peach-channel" },
  { "alias": ["mango"],        "repoName": "mango-channel" }
]
```

---

## Типы запросов

### DataFlow

Пользователь называет UI-элемент, агент ищет откуда приходят данные.

```
investigator> откуда берётся цена на карточке товара?
```

Пайплайн:
1. `QueryClassifier` → `DataFlow`
2. `UiSearchAgent` ищет в `UI_REPO` string ID и канал
3. `ChannelSearchAgent` ищет дефиницию и поля в репозитории канала
4. `AnswerComposer` формирует ответ

### ChannelSearch

Прямой поиск метода или поля в репозитории канала.

```
investigator> найди метод v4/product в канале peach
investigator> что возвращает v4/catalog?
investigator> поле priceUnit в v4/product канала peach
```

Пайплайн:
1. `QueryClassifier` → `ChannelSearch` (с алиасом или без)
2. Если канал не указан → REPL запрашивает уточнение
3. `ChannelSearchAgent.searchDirect()` ищет в репозитории канала

---

## Диалог и уточнения

Если найдено несколько UI-элементов — REPL предлагает выбрать:

```
Найдено несколько подходящих элементов UI. Уточните, какой именно:
  1. "Недоступно для продажи" (id: not_available_for_sale)
  2. "Недоступно" (id: unavailable)
Введите номер:
```

Если канал не указан в ChannelSearch:

```
Уточните, в каком канале искать:
  1. peach
  2. mango
Введите номер или название канала:
```

### Команды REPL

| Команда | Действие |
|---------|----------|
| `clear` | Очистить историю диалога (с подтверждением) |
| `exit` / `quit` | Выйти |

---

## Сборка дистрибутива

```bash
# Fat JAR
cd smartagent && ./gradlew :investigator:shadowJar
# → investigator/build/libs/investigator.jar

# macOS .dmg (через jpackage, требует JDK 14+)
cd smartagent && ./gradlew :investigator:jpackageDmg
# → investigator/build/dist/Investigator-1.0.0.dmg
```

В `.dmg` автоматически пакуются `.properties` и `channels.json` из корня проекта.

---

## Архитектура агентов

```
InvestigatorOrchestrator
 ├── QueryClassifier        → DataFlow / ChannelSearch / Rejected
 ├── UiSearchAgent          → GitHub MCP, ищет string ID + канал в UI_REPO
 ├── ChannelSearchAgent     → GitHub MCP, ищет дефиниции/поля в репо канала
 ├── RelevanceGuard         → проверяет соответствие UI-элемента запросу
 └── AnswerComposer         → итоговый ответ
```

`InvestigatorToolLoop` — общий tool-call цикл для агентов, работающих с GitHub MCP (до 50 итераций).

`InvestigatorSession` — хранит в рамках сессии:
- `history` — история обменов для контекста AnswerComposer
- `uiFileHints` — файлы из предыдущего UI-поиска (ускоряет повторные запросы)
- `channelFileHints` — путь к дефиниции для каждого канала
- `dataFlowCache` — кэш найденных data flow (LLM решает, использовать кэш или нет)

---

## Модель

`ModelConfig.CORPORATE` — MiniMax через корпоративный GPU Stack. Ключ и URL задаются в `.properties`.
