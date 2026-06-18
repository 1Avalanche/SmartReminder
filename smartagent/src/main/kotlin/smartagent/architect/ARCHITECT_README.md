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

### Типичный сценарий

```
Пользователь: "хочу сделать мобильное приложение для котов-диабетиков"
  → система создаёт фичу + задачу, запускает планирование

[PlanningAgent задаёт уточняющие вопросы]
  → пользователь отвечает несколько раз

[PlanningAgent решает: достаточно]
  → автоматически: сохраняет plan.md, запускает ExecutionAgent

[ExecutionAgent создаёт архитектурный документ]
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
        user input → FeatureOrchestrator.process()
                       └── если нужен LLM-ответ → ArchitectClient.sendMessage()

FeatureOrchestrator
  ├── IntentClassifier          — классифицирует намерение
  ├── FeatureRepository         — CRUD фич
  ├── TaskRepository            — CRUD задач + артефакты
  ├── PlanningAgent             — этап PLANNING
  ├── ExecutionAgent            — этап EXECUTION
  └── ValidationAgent           — этап VALIDATION

ArchitectClient
  └── ArchitectOnboarding       — память сессии (arch_settings.md, arch_tasks.json)
```

---

### Сущности и их ответственность

#### `Main.kt`
Точка входа и REPL. Инстанциирует все компоненты, обрабатывает slash-команды.
При обычном сообщении в architect-режиме:
1. `featureOrchestrator.process(input)` — обрабатывает FSM, возвращает `Boolean`
2. Если `true` — дополнительно вызывает `architectClient.sendMessage(input)` для LLM-ответа

---

#### `FeatureOrchestrator`
**Главный координатор.** На каждое сообщение пользователя:

1. Вызывает `IntentClassifier` → получает `IntentResult`
2. Обрабатывает намерение:
   - `NEW_FEATURE` → создаёт фичу + задачу, добавляет в историю
   - `NEW_TASK` → создаёт задачу для активной фичи (предыдущая задача ставится на паузу)
   - `SWITCH_FEATURE` → переключает активную фичу
   - `TASK_UPDATE` → переключает активную задачу (если указан `taskId`)
   - иначе → добавляет сообщение в историю задачи
3. Находит активную фичу + задачу → `dispatchToAgent()`

`dispatchToAgent()` запускает агента для текущего `Stage`:
- `PLANNING` → `PlanningAgent.run()`; если `planningComplete=true` — переключает на `EXECUTION` и вызывает `dispatchToAgent()` снова
- `EXECUTION` → `ExecutionAgent.run()`; если `executionComplete=true` — переключает на `VALIDATION` и повторяет
- `VALIDATION` → `ValidationAgent.run()`; агент сам решает: `DONE` или назад в `EXECUTION`

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

**Контекст в запросе:** фича, задача, история диалога (`{taskId}-history.md`)  
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

**Side effects при `planningComplete=true`:**
- сохраняет `plan` в `{taskId}-plan.md`
- переводит задачу в `Stage.EXECUTION`
- логирует FSM-переход

---

#### `ExecutionAgent`
Управляет этапом **EXECUTION**.

**Контекст:** фича, задача, `{taskId}-plan.md`, текущий `{taskId}-architecture.md`, история  
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

**Side effects при `executionComplete=true`:**
- сохраняет `artifact` в `{taskId}-architecture.md`
- переводит задачу в `Stage.VALIDATION`

---

#### `ValidationAgent`
Управляет этапом **VALIDATION**.

**Контекст:** фича, задача, план, архитектура, текущий review, история  
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

#### `ArchitectClient`
LLM-клиент для "главного архитектора" — ответы вне FSM (пока `FeatureOrchestrator` вернул `true`).

Строит сообщения: system-prompt + ассистент-контекст (история сессии + `arch_tasks.json`) + user.

Парсит `ArchitectResponse`:
```json
{ "content": "...", "decision": "...", "currentTask": "название: описание" }
```
- `decision` → дописывается в `arch_settings.md`
- `currentTask` → upsert в `arch_tasks.json`

---

#### `ArchitectOnboarding`
Управляет двумя файлами долгосрочной памяти:

| Файл | Назначение |
|------|------------|
| `arch_settings.md` | накопленные решения проекта (append-only) |
| `arch_tasks.json` | рабочие задачи с последними решениями (upsert по имени) |

Строит system-prompt: `prompts/architect/system.md` + содержимое `arch_settings.md`.

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
    └── tasks/
        ├── task-001.json         ← Task: id, featureId, stage, status, currentStep, ...
        ├── task-001-history.md   ← лог диалога
        ├── task-001-plan.md      ← артефакт планирования
        ├── task-001-architecture.md  ← архитектурный документ
        └── task-001-review.md    ← результат проверки
```
