# Models — Модели и их конфигурация

## Доступные модели

| Имя | Модель API | Контекст | API | Ключ |
|-----|-----------|----------|-----|------|
| `deepseek` | `deepseek-v4-pro` | 1 000 000 | DeepSeek | `DEEPSEEK_STUDY_API_KEY` |
| `qwen` | `qwen/qwen3.7-plus` | 1 000 000 | OpenRouter | `OPENROUTER_STUDY_API_KEY` |
| `qwen-low` | `qwen/qwen3-8b` | 131 000 | OpenRouter | `OPENROUTER_STUDY_API_KEY` |
| `qwen-local` | `qwen2.5:14b` | 32 000 | Ollama (localhost:11434) | — |
| `gemma-local` | `gemma3:12b` | 128 000 | Ollama (localhost:11434) | — |

Локальные модели (`isLocal = true`) требуют запущенного Ollama. API-ключ не нужен.

Модель `rerank` (Nvidia Llama Nemotron) — **внутренняя**, используется только `RerankerClient` в question mode. Не подходит для чата.

---

## Когда что выбирать

**`deepseek`** — дефолт. Большой контекст (1M), хорошо справляется с кодом и длинными диалогами. Отдельный API без OpenRouter.

**`qwen`** — альтернатива если DeepSeek недоступен. Тот же контекст, через OpenRouter.

**`qwen-low`** — быстрее и дешевле, контекст 131K. Подходит для коротких запросов и быстрых ответов когда скорость важнее глубины.

**`qwen-local` / `gemma-local`** — работают без API-ключей. Нужен запущенный Ollama (`ollama serve`). `gemma-local` больший контекст (128K). Telegram-бот использует `gemma-tunnel-local` (порт 11435).

---

## Переключение модели

```
/model deepseek
/model qwen
/model qwen-low
```

Модель применяется сразу к следующему запросу. История не сбрасывается.

### Псевдонимы

Каждая модель принимает несколько имён:

| Модель | Псевдонимы |
|--------|-----------|
| `deepseek` | `deepseek-v4-pro` |
| `qwen` | `qwen3`, `qwen3.7-plus` |
| `qwen-low` | `qwen3-8b` |

### Сохранение выбора

Последняя использованная модель сохраняется в `~/.config/smartreminder/last_model` и восстанавливается при следующем запуске.

```bash
# Просмотр текущей модели
/models
```

---

## Запуск с конкретной моделью

```bash
./smartagent/run.sh --model qwen
./gradlew :cli:run --console=plain -q --args="--model qwen-low"
smartreminder --model qwen
```

---

## Как добавить новую модель

Добавить запись в enum `ModelConfig` в файле:
```
smartagent/llm-client/src/main/kotlin/smartagent/ModelConfig.kt
```

Шаблон:
```kotlin
MY_MODEL(
    shortName = "my-model",
    description = "Описание модели",
    apiModelId = "provider/model-name",
    apiKeyProperty = "PROVIDER_STUDY_API_KEY",
    url = "https://api.provider.com/v1/chat/completions",
    contextWindow = 128_000,
    aliases = listOf("alternative-name")
)
```

После добавления:
- Модель автоматически появится в `/models`
- Ключ нужно добавить в `local.properties`
- Если нужен новый ключ — добавить соответствующую запись в Setup

---

## Влияние модели на сжатие контекста

`SummaryAgent` использует порог `contextWindow * 0.04`. Для `qwen-low` (131K) сжатие начинается при ~5240 токенах в промпте, для `deepseek`/`qwen` (1M) — при ~40000. Смена модели меняет этот порог.
