# Architect Mode

Многоагентный режим для структурированного ведения разработки. Помогает создавать проекты (Feature), декомпозировать их на задачи (Task) и вести задачи по стадиям до завершения.

Активируется: `/mode architect`

---

## Концепции предметной области

### Feature — проект

Контейнер верхнего уровня для группы связанных задач.

| Поле | Описание |
|------|----------|
| `id` | UUID |
| `title` | Название проекта |
| `summary` | Описание (генерируется LLM) |
| `status` | `ACTIVE` / `PAUSED` / `COMPLETED` |

В один момент только один Feature может быть `ACTIVE`. Создание нового или переключение на другой автоматически паузирует текущий.

### Task — задача

Единица работы внутри Feature. Проходит через 4 стадии:

```
PLANNING → EXECUTION → VALIDATION → DONE
```

В любой момент задача может перейти в `PAUSED`.

| Поле | Описание |
|------|----------|
| `id` | UUID |
| `featureId` | Ссылка на Feature |
| `title` | Название задачи |
| `status` | `ACTIVE` / `PAUSED` / `DONE` |
| `stage` | `PLANNING` / `EXECUTION` / `VALIDATION` / `DONE` |
| `currentStep` | Текущий шаг внутри стадии |
| `expectedAction` | Что ожидается от пользователя |
| `summary` | Текущее состояние задачи |

---

## Pipeline обработки запроса

```
Пользователь вводит текст
        │
        ▼
InvariantAgent
        │  проверяет на нарушение ограничений (system.md + user.md)
        │
        ▼
IntentClassifier
        │  определяет намерение → ArchitectThought
        │
        ▼
ArchitectOrchestrator
        │
        ├─ NEW_FEATURE   → создать Feature, переключить на неё
        ├─ NEW_TASK      → создать Task в активном Feature
        ├─ TASK_UPDATE   → передать в агент текущей стадии Task
        │       ├─ PLANNING   → PlanningAgent
        │       ├─ EXECUTION  → ExecutionAgent
        │       └─ VALIDATION → ValidationAgent
        ├─ SWITCH_FEATURE → переключить активный Feature
        ├─ QUESTION      → ответить напрямую через LLM
        └─ APPROVAL      → подтвердить завершение текущего шага
```

---

## Агенты

### IntentClassifier
Классифицирует намерение пользователя. Типы намерений:
`NEW_FEATURE`, `NEW_TASK`, `TASK_UPDATE`, `SWITCH_FEATURE`, `QUESTION`, `APPROVAL`

Промпт: `prompts/architect/intent_classifier.txt`

### InvariantAgent
Хранит пользовательские ограничения в `architect/invariants/user.md`. При каждом запросе проверяет, не нарушает ли действие существующие ограничения. Если пользователь формулирует новое ограничение — сохраняет его.

Промпт: `prompts/architect/invariant_agent.txt`

### PlanningAgent (стадия PLANNING)
Получает описание задачи, генерирует пошаговый план и сохраняет его в Task. Переводит задачу в стадию EXECUTION.

Промпт: `prompts/architect/planning_agent.txt`

### ExecutionAgent (стадия EXECUTION)
Ведёт пользователя по шагам плана. Подтверждает выполнение каждого шага. После последнего шага переводит задачу в VALIDATION.

Промпт: `prompts/architect/execution_agent.txt`

### ValidationAgent (стадия VALIDATION)
Проверяет результат. Если результат достаточен — переводит задачу в DONE. Если нет — возвращает в EXECUTION с комментарием.

Промпт: `prompts/architect/validation_agent.txt`

### ArchitectOnboarding
Управляет долгосрочной памятью (`arch_settings.md`) и выводит приветственные сообщения при старте сессии.

---

## Инварианты

Ограничения — это правила, которые нельзя нарушать при принятии архитектурных решений.

- **Системные** (`architect/invariants/system.md`) — встроены, не меняются
- **Пользовательские** (`architect/invariants/user.md`) — формулируются в диалоге и сохраняются автоматически

Просмотр: `/invariants`

---

## Хранение данных

| Путь | Содержимое |
|------|-----------|
| `architect/features/<id>.json` | Feature-сущности |
| `architect/active_feature.txt` | ID активного Feature |
| `architect/tasks/<id>.json` | Task-сущности |
| `architect/tasks/<id>_history.json` | История задачи |
| `architect/tasks/<id>_{plan\|architecture\|review}.txt` | Артефакты стадий |
| `architect/invariants/system.md` | Системные ограничения |
| `architect/invariants/user.md` | Пользовательские ограничения |
| `arch_settings.md` | Долгосрочная память архитектора |

Файлы создаются в рабочей директории при запуске (обычно `smartagent/`).

---

## Команды

### Управление проектами (Feature)

| Команда | Действие |
|---------|----------|
| `/features` | Список всех Feature со статусами |
| `/feature create <title>` | Создать Feature и сделать активной |
| `/feature current` | Показать активный Feature |
| `/feature switch <id>` | Переключить активный Feature |
| `/feature state` | Обзор: активный Feature + список задач |
| `/feature info` | Подробная информация о Feature |
| `/feature pause` | Поставить активный Feature на паузу |
| `/feature resume` | Возобновить Feature |

### Прочие команды режима

| Команда | Действие |
|---------|----------|
| `/invariants` | Показать пользовательские ограничения |
| `/memory` | Показать arch_settings.md |
| `/status` | Текущее состояние Feature и Task |
| `/clearAll` | Удалить все данные проекта (с подтверждением) |

### Диагностика

| Команда | Действие |
|---------|----------|
| `/classify <message>` | Прогнать через IntentClassifier без изменений |
| `/debug tasks` | Все задачи в JSON |
| `/debug task current` | Текущая задача в JSON |
| `/debug task history` | История текущей задачи |
| `/debug task artifact` | Артефакт текущей задачи |
| `/debug task review` | Результат валидации текущей задачи |
