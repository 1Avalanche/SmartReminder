# Question Mode

RAG-режим: ответы строго на основе проиндексированных файлов. Режим по умолчанию при запуске.

Активируется: запуск без аргументов, или `/mode question`

---

## Как работает

Каждый вопрос обрабатывается независимо — история не накапливается между вопросами.

```
Вопрос пользователя
        │
        ▼
OllamaEmbeddingGenerator.embed(query) — векторизация вопроса
        │
        ▼
VectorStore.search(vector, topK) — поиск похожих чанков
        │
        ├─── RagMode.SIMPLE ───────────────────────────────────┐
        │    top-8 чанков по схожести → контекст               │
        │                                                       │
        └─── RagMode.RERANK ──────────────────────────────────►│
             top-30 → фильтр similarity ≥ 0.68                 │
             → RerankerClient.rerank(query, texts, top=5)       │
             → фильтр rerank score ≥ 0.1                       │
             → итоговые чанки → контекст                       │
                                                               │
        ◄──────────────────────────────────────────────────────┘
        │
        ▼
Форматирование контекста в XML-блоки <chunk>
        │
        ▼
ChatClient.sendMessage(question, systemPromptOverride, includeHistory=false)
        │
        ▼
LLM отвечает строго по контексту
```

---

## Режимы RAG (`/rag-mode`)

| Режим | Описание | Когда использовать |
|-------|----------|-------------------|
| `no` | Только LLM, без поиска по индексу | Общие вопросы не по документам |
| `simple` | Поиск top-8 чанков → LLM | Быстро, без reranker |
| `rerank` | Поиск → порог → reranker → top-3 → LLM | Точнее, требует OPENROUTER_STUDY_API_KEY |

Переключение сбрасывает историю сессии.

```
/rag-mode           — показать текущий режим
/rag-mode simple    — переключить
```

---

## Параметры фильтрации

| Константа | Значение | Смысл |
|-----------|----------|-------|
| `SEARCH_TOP_K` | 30 | Сколько чанков берётся из индекса для rerank-пайплайна |
| `SIMPLE_TOP_K` | 8 | Сколько чанков берётся для simple-пайплайна |
| `FINAL_TOP_K` | 5 | Сколько чанков передаётся в reranker |
| `SIMILARITY_THRESHOLD` | 0.68 | Порог косинусного расстояния до reranker |
| `RERANK_SCORE_THRESHOLD` | 0.1 | Порог оценки reranker для финального фильтра |

---

## Reranker

Использует Nvidia Llama Nemotron Rerank через OpenRouter (модель `nvidia/llama-nemotron-rerank-vl-1b-v2:free`).

Требует `OPENROUTER_STUDY_API_KEY`. Если ключа нет — автоматически падает обратно на `simple` с предупреждением:
```
(reranker not configured, falling back to simple)
```

---

## Формат контекста для LLM

Каждый чанк передаётся в XML-обёртке:

```xml
<chunk>
  <source file="docs/README.md" title="README" ext="md" section="Installation" index="2"/>
  <score>0.8743</score>
  <content>
  текст чанка...
  </content>
</chunk>
```

Несколько чанков объединяются в один блок `<context>`.

---

## Системный промпт

Загружается из `prompts/question/system.md`. Главное правило: отвечать **строго по контексту**, не использовать собственные знания.

Если контекст не содержит релевантной информации — ответить ровно одной фразой: «В контексте не найдено подходящей информации».

---

## Загрузка индекса

При первом вопросе `QuestionHandler` ищет индекс по путям (в порядке приоритета):
1. `.indexed/structured.json`
2. `smartagent/.indexed/structured.json`

Загружается только `structured.json`. Если индекса нет:
```
Index file not found. Run /index-run first.
```

Индекс загружается один раз и держится в памяти до конца сессии.

---

## Требования

- **Ollama** — запущен локально, нужен для векторизации вопроса
- **Индекс** — построен заранее через `index` mode (strategy: structured)
- **OPENROUTER_STUDY_API_KEY** — нужен только для `rerank` режима

---

## Команды

| Команда | Действие |
|---------|----------|
| `/rag-mode` | Показать текущий режим |
| `/rag-mode no\|simple\|rerank` | Переключить стратегию (сбрасывает историю) |
| `<вопрос>` | Задать вопрос — запускает RAG-пайплайн |
