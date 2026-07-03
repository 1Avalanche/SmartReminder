# RAG Internals — Внутренности RAG-пайплайна

Дополняет [index.md](index.md) и [question.md](question.md). Там описано ЧТО делают режимы — здесь КАК устроены компоненты внутри.

---

## FileScanner

Рекурсивный обход файловой системы с фильтрацией.

### Игнорируемые директории

```
.git  build  .gradle  node_modules  .idea
__pycache__  out  .dart_tool  .pub-cache  .build
DerivedData  .swiftpm  Pods  .indexed
```

### Определение текстового файла

Файл считается текстовым если:
1. Расширение входит в белый список, **или** имя файла в списке разрешённых имён
2. **Нет null-байта** в содержимом (null-байт = бинарный файл)
3. Успешно декодируется как UTF-8 (ошибки декодирования заменяются, не прерывают)

**Разрешённые расширения** (50+): `kt kts java py js ts jsx tsx go rs cpp c cs swift scala rb sh bash zsh dart`, `xml yaml yml toml csv ini cfg conf properties env`, `md markdown txt html css scss sql ps1 bat` и другие.

**Разрешённые имена** (без расширения): `makefile dockerfile vagrantfile gradle.properties local.properties proguard-rules.pro`

**Всегда бинарные**: `png jpg jpeg gif pdf zip jar class apk ipa so dylib exe bin o a lib`

---

## StructuredChunker vs FixedChunker

### FixedChunker

Простое скользящее окно по символам.

| Параметр | Значение |
|----------|---------|
| Размер чанка | 500 символов |
| Перекрытие | 50 символов (10%) |
| Минимальный размер | 125 символов (25%) |

Граница чанка snap'ится к ближайшему переносу строки или пробелу в пределах нижней половины чанка. Последний чанк, если короче минимума, склеивается с предыдущим.

**Когда использовать**: быстрое индексирование однородного текста (prose, конфиги).

### StructuredChunker

Понимает структуру документа. Параметры: `maxChunkSize=1500`, `minChunkSize=200`, `overlapSize=100`.

Стратегия выбирается по расширению файла:

#### Markdown (`.md`, `.markdown`)
Разбивает по заголовкам (`#`, `##`, ...). Каждая секция — отдельный чанк. Иерархия заголовков сохраняется в `ChunkMetadata.sectionPath`.

#### Код (`.kt .kts .java .py .js .ts .jsx .tsx .go .rs .cpp .c .cs .swift .scala .rb .sh`)
Отслеживает `braceDepth` (глубину вложенности `{}`). Чанк создаётся при:
- Пустая строка + `braceDepth == 0` + достаточный размер
- Экстренный flush при превышении `maxChunkSize` — только когда `braceDepth == 0` (не рвёт функции/классы посередине)
- Сохраняет объявление функции/класса (`declarationRegex`) как заголовок чанка

Размер не ограничивается вторичным сплиттером для кода (в отличие от text/markdown) — чтобы не нарушить баланс скобок.

#### Остальные файлы
Разбивает по абзацам (двойной перенос строки). Если абзац > `maxChunkSize` — дополнительный fixed-size split.

---

## OllamaEmbeddingGenerator

| Параметр | Значение |
|----------|---------|
| Endpoint | `http://localhost:11434/api/embed` |
| Модель | `nomic-embed-text` |
| Размерность | 768 |

Запрос:
```json
{"model": "nomic-embed-text", "input": "текст чанка"}
```

Ответ содержит `embeddings[0]` (float array 768-dim) и `prompt_eval_count` (токены).

При ошибке соединения бросает исключение — `IndexCommandHandler` перехватывает его, логирует и продолжает (чанк пропускается).

---

## InMemoryVectorStore

Хранит пары `(float[], Chunk)` в памяти. Поиск — **косинусное сходство** по всем векторам (brute-force, без индекса).

```
search(queryVector, topK, threshold?) → List<SearchResult(chunk, score)>
```

Score — косинусное сходство в диапазоне [-1, 1]. Результаты отсортированы по убыванию score. Опциональный `threshold` фильтрует результаты ниже порога.

В `question` mode порог `SIMILARITY_THRESHOLD = 0.68` применяется вручную в `QuestionHandler` (не через параметр `threshold`).

---

## Формат `ChunkMetadata`

Каждый чанк несёт метаданные, которые попадают в XML-блок `<source>` при передаче в LLM:

| Поле | Источник | Пример |
|------|----------|--------|
| `documentTitle` | имя файла без пути | `README.md` |
| `documentSource` | относительный путь от корня | `docs/setup/README.md` |
| `extension` | расширение файла | `md` |
| `sectionPath` | иерархия заголовков (Markdown/код) | `["Installation", "Quick Start"]` |
| `chunkIndex` | порядковый номер чанка в документе | `3` |

`sectionPath` полезен для отладки: показывает, из какой секции документа взят чанк.

---

## Формат индекс-файла (`.indexed/structured.json`)

```json
[
  {
    "vector": [0.0234, -0.1823, ...],
    "chunk": {
      "id": "docs_readme_0",
      "content": "текст чанка...",
      "documentId": "docs_readme",
      "chunkIndex": 0,
      "metadata": {
        "documentTitle": "README.md",
        "documentSource": "docs/README.md",
        "extension": "md",
        "sectionPath": ["Installation"],
        "chunkIndex": 0
      }
    }
  }
]
```

Формат — `List<VectorIndexEntry>`, сериализуется через `JsonVectorIndexPersistence`.
