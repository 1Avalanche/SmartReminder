# Architect — режим проектирования архитектуры

## Часть 1. Как это работает (бизнес-уровень)

Architect — это режим SmartAgent для структурированного проектирования архитектуры.
Пользователь общается в терминале на естественном языке; система сама ведёт его через этапы.

### Сущности предметной области

**Фича (Feature)** — проект верхнего уровня. Описывается свободным текстом:
`"хочу сделать мобильное приложение для владельцев котов-диабетиков"`.
В один момент времени одна фича — активная; остальные ставятся на паузу.

**Задача (Task)** — конкретная архитектурная работа в рамках фичи.
Одна задача — одна ветка диалога. Каждая задача проходит три этапа:

| Этап | Что происходит |
|------|----------------|
| **Планирование** | Агент уточняет требования, задаёт вопросы. Когда достаточно — фиксирует план в файл. |
| **Проектирование** | Агент создаёт архитектурный документ на основе плана. |
| **Проверка** | Агент ищет пробелы и несоответствия. Может вернуть задачу на проектирование. |

Переходы между этапами **автоматические**: как только агент решает, что этап завершён, система
немедленно запускает следующий без участия пользователя.

**Инварианты** — жёсткие ограничения, которые нельзя нарушать. Делятся на:
- **системные** (`architect/invariants/system.md`) — заданы заранее, неизменяемы
- **пользовательские** (`architect/invariants/user.md`) — пользователь объявляет в диалоге,
  сохраняются между сессиями

Каждое сообщение пользователя и каждый ответ агента проверяются на соответствие инвариантам.
При нарушении — запрос отклоняется или агент получает повторный запрос с пометкой `[INVARIANT VIOLATION]`.

### Типичный сценарий

```
Пользователь: "хочу сделать мобильное приложение для котов-диабетиков"
  → InvariantAgent проверяет сообщение
  → система создаёт фичу + задачу, запускает планирование

[PlanningAgent задаёт уточняющие вопросы]
  → InvariantAgent проверяет ответ агента
  → пользователь отвечает несколько раз

[PlanningAgent решает: достаточно]
  → автоматически: сохраняет plan.md, запускает ExecutionAgent

[ExecutionAgent создаёт архитектурный документ]
  → InvariantAgent проверяет ответ агента
  → автоматически: сохраняет architecture.md, запускает ValidationAgent

[ValidationAgent находит пробел в offline-режиме]
  → возвращает задачу на Проектирование

[ExecutionAgent дорабатывает документ]
  → ValidationAgent принимает → задача DONE
```

### Управление проектами

- Можно вести несколько фич параллельно (`/feature switch <id>`).
- При переключении текущая фича и её активная задача ставятся на паузу.
- Все данные персистируются на диске — сессия восстанавливается после перезапуска.

### Команды

```
/features                  список всех фич
/feature create <title>    создать фичу вручную
/feature switch <id>       переключить активную фичу
/feature state             обзор фичи и её задач
/feature info              детали: даты, summary
/feature pause/resume      пауза / возобновление
/invariants                показать пользовательские инварианты
/status                    что сейчас активно и что ожидается от пользователя
/classify <message>        диагностика: какой intent определит классификатор
/memory                    показать arch_settings.md и arch_tasks.json
/clearAll                  удалить все данные (с подтверждением)
```

---

## Часть 2. Сущности кода

### Карта сущностей

```
Main.kt
  ├── создаёт все объекты
  ├── запускает REPL
  └── при AgentMode.ARCHITECT:
        user input → ArchitectOrchestrator.process()
                       ├── InvariantAgent.check(userInput)    — до роутинга
                       ├── IntentClassifier.classify()        — определяет намерение
                       └── dispatchToAgent()
                             ├── [PLANNING]   InvariantAgent.check(agentResponse) × до 3 попыток
                             ├── [EXECUTION]  InvariantAgent.check(agentResponse) × до 3 попыток
                             └── [VALIDATION] ValidationAgent (без retry)

ArchitectOrchestrator
  ├── InvariantAgent        — проверка инвариантов (вход пользователя + ответы агентов)
  ├── IntentClassifier      — классифицирует намерение
  ├── FeatureRepository     — CRUD фич
  ├── TaskRepository        — CRUD задач + артефакты
  ├── PlanningAgent         — этап PLANNING
  ├── ExecutionAgent        — этап EXECUTION
  └── ValidationAgent       — этап VALIDATION

ArchitectOnboarding
  └── управляет долгосрочной памятью (arch_settings.md, arch_tasks.json)
```

---

### Сущности и их ответственность

#### `Main.kt`
Точка входа и REPL. Инстанциирует все компоненты, обрабатывает slash-команды.
При обычном сообщении в architect-режиме вызывает `architectOrchestrator.process(input)`.
После обработки в `finally`-блоке добавляет запись в лог и при необходимости асинхронно
запускает `ProfileAgent`.

---

#### `ArchitectOrchestrator`
**Главный координатор.** На каждое сообщение пользователя:

1. Вызывает `InvariantAgent.check(userInput)`:
   - `INVALID` → отклоняет сообщение, логирует в историю задачи, возвращает
   - `NEW_INVARIANT` → сохраняет инвариант, продолжает обработку
   - `VALID` → продолжает
2. Вызывает `IntentClassifier` → получает `IntentResult`
3. Обрабатывает намерение:
   - `NEW_FEATURE` → создаёт фичу + задачу, добавляет в историю
   - `NEW_TASK` → создаёт задачу для активной фичи (предыдущая задача ставится на паузу)
   - `SWITCH_FEATURE` → переключает активную фичу, возобновляет последнюю задачу
   - `TASK_UPDATE` → переключает активную задачу (если указан `taskId`)
   - иначе → добавляет сообщение в историю задачи
4. Находит активную фичу + задачу → `dispatchToAgent()`

`dispatchToAgent()` запускает агента для текущего `Stage`:
- `PLANNING` → цикл до 3 попыток: `PlanningAgent.fetch()` → `InvariantAgent.check(response)`;
  при нарушении — повтор с `[INVARIANT VIOLATION]`; при успехе — `PlanningAgent.apply()`.
  Если `planningComplete=true` — переключает на `EXECUTION` и вызывает `dispatchToAgent()` снова.
- `EXECUTION` → аналогичный цикл с `ExecutionAgent.fetch()` + `apply()`.
  Если `executionComplete=true` — переключает на `VALIDATION` и повторяет.
- `VALIDATION` → одиночный вызов `ValidationAgent.fetch()` + `apply()` (без retry-цикла).
  Агент сам решает: `DONE` или назад в `EXECUTION`.

---

#### `InvariantAgent`
Проверяет текст на соответствие инвариантам. Используется дважды на каждое сообщение:
один раз для входа пользователя, один раз для ответа агента.

**Источники инвариантов:**
- `architect/invariants/system.md` — системные запреты (неизменяемы)
- `architect/invariants/user.md` — пользовательские запреты (append-only через `saveUserInvariant`)

**Выход:** `InvariantResult`:
```json
{ "status": "VALID|INVALID|NEW_INVARIANT", "reason": "...", "invariant": "..." }
```

- `VALID` — текст соответствует инвариантам
- `INVALID` — нарушение; `reason` объясняет что именно
- `NEW_INVARIANT` — пользователь объявляет новый запрет; `invariant` — текст запрета

---

#### `IntentClassifier`
LLM-вызов для классификации намерения.

**Вход:** текущий контекст (активная фича, открытые задачи) + сообщение пользователя  
**Выход:** `IntentResult` — JSON с полями `intent`, `featureId?`, `taskId?`, `confidence`, `reason`

Интенты: `NEW_FEATURE` | `NEW_TASK` | `TASK_UPDATE` | `SWITCH_FEATURE` | `QUESTION` | `APPROVAL`

Промпт загружается из `prompts/architect/intent_classifier.txt`; при отсутствии — встроенный fallback.

---

#### `PlanningAgent`
Управляет этапом **PLANNING**.

**API:** `fetch()` — получает ответ от LLM; `apply()` — сохраняет результат в TaskRepository.
`run()` = `fetch()` + `apply()` (удобный метод, не используется в `ArchitectOrchestrator` напрямую).

**Контекст в запросе:** фича, задача, история диалога (`{taskId}-history.md`), инварианты  
**Выход:** `PlanningAgentResponse`:

```json
{
  "planningComplete": false,
  "currentStep": "Уточнение требований",
  "expectedAction": "Описать offline-сценарии",
  "summary": "...",
  "response": "текст пользователю",
  "plan": null
}
```

**Side effects при `planningComplete=true` (в `ArchitectOrchestrator`):**
- `ArchitectOrchestrator` сохраняет `plan` в `{taskId}-plan.md` и переводит задачу в `Stage.EXECUTION`

---

#### `ExecutionAgent`
Управляет этапом **EXECUTION**.

**API:** аналогично `PlanningAgent` — `fetch()` / `apply()` / `run()`.

**Контекст:** фича, задача, `{taskId}-plan.md`, текущий `{taskId}-architecture.md`, история, инварианты  
**Выход:** `ExecutionAgentResponse`:

```json
{
  "executionComplete": false,
  "currentStep": "Проектирование слоя данных",
  "expectedAction": "Подтвердить результат",
  "artifact": "# Architecture\n...",
  "response": "текст пользователю"
}
```

`artifact` — полный markdown архитектуры; не показывается пользователю, только сохраняется.

**Side effects при `executionComplete=true` (в `ArchitectOrchestrator`):**
- сохраняет `artifact` в `{taskId}-architecture.md`
- переводит задачу в `Stage.VALIDATION`

---

#### `ValidationAgent`
Управляет этапом **VALIDATION**.

**Контекст:** фича, задача, план, архитектура, текущий review, история, инварианты  
**Выход:** `ValidationAgentResponse`:

```json
{
  "validationPassed": false,
  "returnToExecution": true,
  "currentStep": "Найдены пробелы",
  "expectedAction": "Доработать offline-режим",
  "review": "# Review\n...",
  "response": "текст пользователю"
}
```

**Side effects:**
- сохраняет `review` в `{taskId}-review.md`
- `validationPassed=true` → `completeTask()` → статус `DONE`
- `returnToExecution=true` → `Stage.EXECUTION`

---

#### `FeatureRepository`
Персистирует `Feature` в `architect/features/{id}.json`.
Хранит указатель на активную фичу в `architect/active_feature.txt`.

При `setActiveFeature(id)` предыдущая активная фича автоматически переходит в `PAUSED`.

---

#### `TaskRepository`
Персистирует `Task` в `architect/tasks/{id}.json`.

Дополнительно хранит side-файлы для каждой задачи:

| Файл | Содержимое |
|------|------------|
| `{id}-history.md` | лог диалога (User / PlanningAgent / ExecutionAgent / ValidationAgent) |
| `{id}-plan.md` | план от PlanningAgent |
| `{id}-architecture.md` | архитектурный документ от ExecutionAgent |
| `{id}-review.md` | обзор от ValidationAgent |

При `createTask()` предыдущая активная задача фичи автоматически ставится на паузу.

---

#### `ArchitectOnboarding`
Управляет двумя файлами долгосрочной памяти:

| Файл | Назначение |
|------|------------|
| `arch_settings.md` | накопленные решения проекта (append-only) |
| `arch_tasks.json` | рабочие задачи с последними решениями (upsert по имени) |

Строит system-prompt: `prompts/architect/system.md` + содержимое `arch_settings.md`.
Используется в `Main.kt` для `startSession()` и в команде `/memory`.

---

### Схема переходов состояний задачи

```
                        createTask()
                             │
                             ▼
                      ┌─────────────┐
                      │   PLANNING  │◄────────────────────────────────┐
                      └──────┬──────┘                                 │
                             │ planningComplete=true                  │
                             │ (PlanningAgent сохраняет plan.md)      │
                             ▼                                        │
                      ┌─────────────┐                                 │
               ┌─────►│  EXECUTION  │◄──── returnToExecution=true ───┐│
               │      └──────┬──────┘                                ││
               │             │ executionComplete=true                ││
               │             │ (ExecutionAgent сохраняет arch.md)    ││
               │             ▼                                        ││
               │      ┌─────────────┐                                 │
               │      │  VALIDATION │─────────────────────────────────┘
               │      └──────┬──────┘
               │             │ validationPassed=true
               │             ▼
               │      ┌─────────────┐
               │      │    DONE     │  (TaskStatus=COMPLETED)
               │      └─────────────┘
               │
               │  Параллельно в любой момент:
               │
               │  pauseTask() ──► PAUSED ──► activateTask() ──► (тот же Stage)
               └──────────────────────────────────────────────────────────────┘
```

#### Переходы Feature

```
                  createFeature()
                       │
                       ▼
                ┌────────────┐
                │   ACTIVE   │◄──── setActiveFeature(id) ────┐
                └─────┬──────┘                               │
                      │ setActiveFeature(другой id)          │
                      ▼                                       │
                ┌────────────┐                               │
                │   PAUSED   │───────────────────────────────┘
                └─────┬──────┘
                      │ (вручную через /feature state или updateFeature)
                      ▼
                ┌────────────┐
                │ COMPLETED  │  (финальный, нельзя сделать активной)
                └────────────┘
```

---

### Структура файлов на диске

```
smartagent/
├── arch_settings.md              ← долгосрочные решения проекта
├── arch_tasks.json               ← рабочие задачи с решениями
└── architect/
    ├── active_feature.txt        ← id активной фичи
    ├── features/
    │   ├── feature-001.json      ← Feature: id, title, status, createdAt, updatedAt
    │   └── feature-002.json
    ├── invariants/
    │   ├── system.md             ← системные запреты (неизменяемы)
    │   └── user.md               ← пользовательские запреты (append-only)
    └── tasks/
        ├── task-001.json         ← Task: id, featureId, stage, status, currentStep, ...
        ├── task-001-history.md   ← лог диалога
        ├── task-001-plan.md      ← артефакт планирования
        ├── task-001-architecture.md  ← архитектурный документ
        └── task-001-review.md    ← результат проверки
```
