# Codestyle

Kotlin-код для всех модулей проекта (`:shared`, `:androidApp`, `smartagent`).

---

## Именование

| Сущность | Стиль | Пример |
|---|---|---|
| Классы, объекты, интерфейсы | `PascalCase` | `ChatSession`, `ApiSample` |
| Функции, переменные | `camelCase` | `sendMessage`, `userInput` |
| Константы | `SCREAMING_SNAKE_CASE` | `KEEP_RECENT`, `MAX_TOKENS` |
| Composable-функции | `PascalCase` | `ApiDemoScreen`, `RestrictionSelector` |
| Тест-методы | backtick + поведение | `` `returns null when token is empty` `` |

Константы объявляются как `private const val` — top-level или в `companion object`.

---

## Видимость

- `public` — дефолт, не писать явно.
- `private` — всегда явно.
- `internal` — для деталей реализации модуля, не утечёт в соседние модули.

---

## Классы

- **`data class`** — только для DTO и snapshot-состояний без логики.
- **`object`** — синглтоны с состоянием или утилиты без инстанциирования.
- **`companion object`** — factory-методы и константы, связанные с классом.
- **`sealed interface` / `sealed class`** — алгебраические типы состояний (`UiState`, `ToolResult`).
- **`enum class`** — перечисления с конфигурацией; логику выносить в `when`, а не в методы каждого варианта.

---

## Null Safety

```kotlin
// предпочтительно
value?.let { process(it) }
runCatching { parse(raw) }.getOrNull()
require(id.isNotBlank()) { "id must not be blank" }

// избегать
value!!.process()
if (value != null) process(value)
```

`!!` допустим только если null физически невозможен по контракту API и это очевидно из контекста.

---

## Обработка ошибок

```kotlin
// API-вызовы
runCatching { client.execute(request) }
    .onSuccess { history.add(it) }
    .onFailure { Log.w(TAG, "request failed", it) }

// UI
result.fold(
    onSuccess = { uiState = UiState.Success(it) },
    onFailure = { uiState = UiState.Error(it.message) }
)

// I/O с silent fallback
val data = runCatching { file.readText() }.getOrElse { "" }
```

`try/catch` без логирования — только когда fallback очевиден из кода.

---

## Коллекции

- Функциональные цепочки предпочтительнее `for`-циклов.
- `buildList { }` — для условного накопления.
- `.toList()` — defensive копирование при возврате внутренней mutable-коллекции.

```kotlin
entries
    .map { it.embedding cosineSimilarity query }
    .sortedByDescending { it }
    .take(topK)
```

---

## Строки

```kotlin
// составные строки
val prompt = buildString {
    appendLine("System: $systemPrompt")
    append("User: $userMessage")
}

// многострочные литералы
val template = """
    You are a helpful assistant.
    Context: $context
""".trimIndent()

// интерполяция — не конкатенация
"Hello, $name!" // OK
"Hello, " + name + "!" // не OK
```

---

## Корутины

```kotlin
// блокирующие операции
suspend fun fetchData(): Result<Data> = withContext(Dispatchers.IO) { ... }

// Compose
val scope = rememberCoroutineScope()
Button(onClick = { scope.launch { viewModel.submit() } })

// entry point (main, telegram bot)
fun main() = runBlocking { ... }
```

`GlobalScope` — не использовать.

---

## Compose UI

- Состояние хоистится к верхнему composable, дочерние получают значения и колбэки.
- Modifier-цепочка: размер/позиция → стилизация → интерактивность.
- Дефолтные параметры — в конце сигнатуры.
- Приватные helper-composable живут в том же файле ниже основного.

```kotlin
@Composable
fun ApiDemoScreen(
    onShowHistory: () -> Unit,
    modifier: Modifier = Modifier
) { ... }

@Composable
private fun SendButton(onClick: () -> Unit) { ... }
```

---

## Комментарии

Комментарий пишется только когда WHY неочевиден из кода: скрытое ограничение, обходной путь для бага, неожиданный инвариант.

```kotlin
// no KDoc for internal implementations
// no "// does X" comments — names document what, comments document why
```

---

## Импорты

Порядок групп (разделять пустой строкой):

1. `kotlin.*`
2. `kotlinx.*`
3. Третьи стороны (`okhttp3.*`, `org.json.*`, `com.github.*`)
4. Локальный код проекта

Wildcard-импорты (`import foo.*`) — запрещены.

---

## Тесты

```kotlin
class TokenTrackerTest {
    private lateinit var tracker: TokenTracker

    @Before fun setUp() { tracker = TokenTracker(tempDir) }
    @After fun tearDown() { tempDir.deleteRecursively() }

    @Test fun `starts with empty state`() {
        assertTrue(tracker.entries.isEmpty())
    }
}

// приватные helper-функции для фикстур
private fun entry(tokens: Int = 100) = TokenEntry(tokens, cost = 0.001)
```

- `kotlin.test` assertions.
- Тест проверяет публичное поведение, не детали реализации.
- Один тест — одно поведение.
