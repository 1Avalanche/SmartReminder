# Index Mode

Офлайн-пайплайн для построения векторного индекса файлов репозитория. Индекс используется режимом `question` для RAG-поиска.

Активируется: `/mode index`

---

## Зачем нужен

`question` mode ищет ответы не в интернете, а в конкретных файлах — документации, коде, заметках. Для этого файлы нужно предварительно проиндексировать: разбить на чанки, получить векторные эмбеддинги и сохранить в JSON-файл. Это и делает `index` mode.

---

## Пайплайн

```
/index-run
    │
    ▼
RepositoryDocumentLoader (FileScanner)
    │  рекурсивный обход директории → List<Document>
    │
    ▼
Chunker
    │  разбивка текста на перекрывающиеся чанки
    │
    ▼
OllamaEmbeddingGenerator
    │  HTTP-запрос к Ollama (localhost) → float[] для каждого чанка
    │
    ▼
InMemoryVectorStore
    │  хранение в памяти во время пайплайна
    │
    ▼
JsonVectorIndexPersistence.save()
    │
    └─ <path>/.indexed/<strategy>.json
```

---

## Стратегии чанкинга

### `fixed` (по умолчанию)

`FixedChunker` — разбивает текст на чанки фиксированного размера с перекрытием.

- Размер чанка: **500 символов**
- Перекрытие: **10% (50 символов)** — чанки перекрываются, чтобы не терять контекст на границах
- Минимальный размер: 125 символов (последний слишком короткий чанк склеивается с предыдущим)
- Граница чанка: ищет ближайший перенос строки или пробел, чтобы не разрывать слова

### `structured`

`StructuredChunker` — разбивает по семантическим секциям (заголовки, блоки кода и т.д.). Лучше сохраняет логическую структуру документов.

---

## Требования

**Ollama** должен быть запущен локально перед `/index-run`. По умолчанию используется стандартный endpoint Ollama. Без него пайплайн упадёт на шаге эмбеддинга.

---

## Команды

| Команда | Действие |
|---------|----------|
| `/index-path <path>` | Установить директорию для индексирования |
| `/index-strategy fixed\|structured` | Выбрать стратегию чанкинга |
| `/index-status` | Показать текущие настройки и путь к выходному файлу |
| `/index-run` | Запустить пайплайн |

### Пример

```
/mode index
/index-path /path/to/docs
/index-strategy structured
/index-status
/index-run
```

---

## Выходной файл

```
<path>/.indexed/<strategy>.json
```

Содержит массив `VectorIndexEntry[]`:
```json
[
  {
    "vector": [0.123, -0.456, ...],
    "chunk": {
      "id": "doc_0",
      "content": "...",
      "metadata": { "documentSource": "README.md", ... }
    }
  }
]
```

---

## Связь с question mode

`question` mode при первом запросе автоматически ищет индекс по путям:
1. `.indexed/structured.json`
2. `smartagent/.indexed/structured.json`

Загружается только `structured.json` — если индекс строился со стратегией `fixed`, для question mode нужно переиндексировать со стратегией `structured`.

---

## Что показывается после /index-run

```
Indexing: /path/to/docs
Strategy: structured | Output: /path/to/docs/.indexed/structured.json

Loading documents... 42 found
Chunking... 187 chunks
Embedding and indexing... done

Index saved: /path/to/docs/.indexed/structured.json
Documents : 42
Chunks    : 187
Index size: 187 entries
Time      : 12.3s
Tokens    : 9450
```

Чанки, у которых не удалось получить эмбеддинг, пропускаются с предупреждением — пайплайн не прерывается.
