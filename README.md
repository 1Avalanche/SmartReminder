# SmartReminder

Учебный Kotlin Multiplatform проект. Android и iOS приложение + самостоятельный JVM CLI-чат с LLM.

## Модули

| Модуль | Описание |
|---|---|
| `:androidApp` | Android-приложение, UI на Compose |
| `:shared` | Общий Compose Multiplatform код (Android + iOS) |
| `:cli` | Консольный LLM-чат, работает независимо от мобильных модулей |

## CLI — установка и запуск

### Быстрая установка

Требует Java 17+.

```bash
bash smartagent/install.sh
```

Скрипт собирает fat JAR и устанавливает `smartreminder` в `~/.local/bin/`.

Если `~/.local/bin` нет в `PATH`, добавить в `~/.zshrc` или `~/.bashrc`:

```bash
export PATH="$HOME/.local/bin:$PATH"
```

### Запуск после установки

```bash
smartreminder                          # deepseek по умолчанию
smartreminder --model qwen             # выбрать модель
smartreminder --repo /path/to/repo     # анализ кода в репозитории
```

### Удаление

```bash
bash smartagent/uninstall.sh
```

### Запуск без установки (из репозитория)

```bash
./smartagent/run.sh
./smartagent/run.sh --model qwen
./smartagent/run.sh --repo /path/to/repo
```

### API-ключи

Добавить в `local.properties` в корне проекта:

```properties
DEEPSEEK_STUDY_API_KEY=sk-...
OPENROUTER_STUDY_API_KEY=sk-...
```

Или передать через переменные окружения с теми же именами.

### Модели

| Имя | Описание |
|---|---|
| `deepseek` | deepseek-v4-pro (по умолчанию) |
| `qwen` | qwen3-235b-a22b-thinking |
| `qwen-low` | qwen3-8b, быстрее и дешевле |

### Команды в чате

```
/help                  — справка
/models                — список моделей
/model <name>          — сменить модель
/repo [path]           — показать или установить репозиторий
/files [pattern]       — список файлов в репозитории
/tree [depth]          — дерево файлов (по умолчанию глубина 3)
/read <file>           — загрузить файл в контекст
/context               — показать загруженные файлы
/context clear         — убрать файлы из контекста
/history               — история запросов
/clear                 — очистить историю и контекст
/exit                  — выход
```

### Анализ кода

```bash
smartreminder --repo ~/projects/my-app
```

```
> /tree
> /files .kt
> /read src/main/kotlin/App.kt
> /read src/main/kotlin/Database.kt
> объясни как работает авторизация
```

## Мобильные приложения

### Сборка и запуск

```bash
# Android debug APK
./gradlew :androidApp:assembleDebug

# iOS — открыть iosApp/ в Xcode и запустить оттуда
```

### Тесты

```bash
./gradlew :shared:testAndroidHostTest      # Android (JVM)
./gradlew :shared:iosSimulatorArm64Test    # iOS симулятор
./gradlew :shared:allTests                 # все таргеты
```

---

[Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)
