# androidApp

Android-приложение: демо-клиент OpenRouter API с историей запросов. UI на Compose.

## Сборка и запуск

```bash
# Debug APK
./gradlew :androidApp:assembleDebug

# APK: androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Или открыть проект в Android Studio и запустить через Run.

## API-ключ

Ключ OpenRouter задаётся в `local.properties` в корне проекта:

```properties
OPENROUTER_STUDY_API_KEY=sk-...
```

При компиляции прокидывается в `BuildConfig.OPENROUTER_STUDY_API_KEY` через `androidApp/build.gradle.kts`.

## Экраны

### `ApiDemoScreen`

Главный экран:
- Поле ввода промпта
- Выбор модели (группировка по `ModelFamily`)
- Выбор ограничений: `MaxTokens`
- Кнопка отправки → `ApiSample.ask()`
- Отображение ответа (content, время, токены, стоимость)
- Кнопка перехода в историю

### `HistoryScreen`

История запросов:
- Список всех `HistoryItem` с промптом, моделью, статусом
- Аналитика по моделям: среднее время ответа, токены, стоимость

Навигация между экранами — один `Boolean` в `MainActivity.setContent { }`. ViewModel нет.

## Архитектура

**`ApiSample`** — singleton object, все HTTP-вызовы через OkHttp к OpenRouter.

```
ApiDemoScreen
    └─ ApiSample.ask(prompt, model, maxTokens, ...)
            └─ OkHttp POST → openrouter.ai
            └─ _history.add(HistoryItem)  ← mutableStateListOf, живёт в памяти
```

Состояние хранится в `ApiSample._history` (`mutableStateListOf`) — доступно из Compose без ViewModel/StateFlow. При перезапуске приложения история сбрасывается.

## Как добавить модель

В `Restrictions.kt`:

```kotlin
enum class ModelFamily {
    DeepSeek, Qwen, Llama  // добавить новое семейство при необходимости
}

enum class Model(val family: ModelFamily, val modelId: String) {
    // добавить:
    MyModel(ModelFamily.Qwen, "provider/model-name"),
}
```

UI автоматически подхватит новую модель и сгруппирует по `ModelFamily`.

## Зависимости

- Compose BOM (Material3, UI, Activity)
- OkHttp + Logging Interceptor
- `org.json` — парсинг JSON-ответов вручную
- `:shared` — общий KMP-модуль (в приложении не используется активно)
