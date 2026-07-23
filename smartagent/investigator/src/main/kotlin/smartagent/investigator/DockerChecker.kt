package smartagent.investigator

class DockerChecker(
    private val runner: (Array<out String>) -> Int = { cmd ->
        ProcessBuilder(*cmd).redirectErrorStream(true).start().waitFor()
    },
    private val launcher: (List<String>) -> Boolean = { cmd ->
        runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start(); true }.getOrDefault(false)
    },
    private val osName: String = System.getProperty("os.name"),
    private val delayMs: Long = 2000L
) {
    sealed class Result {
        object Ok : Result()
        object NotInstalled : Result()
        object NotRunning : Result()
    }

    fun check(): Result {
        if (runner(arrayOf("docker", "--version")) != 0) return Result.NotInstalled
        return if (runner(arrayOf("docker", "info")) == 0) Result.Ok else Result.NotRunning
    }

    /** Запускает Docker Desktop и ждёт готовности демона (до 60 сек).
     *  Возвращает true если демон поднялся, false — если нет или ОС не поддерживается. */
    fun startAndWait(onProgress: () -> Unit): Boolean {
        val os = osName.lowercase()
        val cmd = when {
            os.contains("mac") -> listOf("open", "-a", "Docker")
            os.contains("win") -> {
                val localAppData = System.getenv("LOCALAPPDATA") ?: ""
                val path = if (localAppData.isNotEmpty())
                    "$localAppData\\Programs\\Docker\\Docker\\Docker Desktop.exe"
                else
                    "C:\\Program Files\\Docker\\Docker\\Docker Desktop.exe"
                listOf(path)
            }
            else -> return false
        }

        if (!launcher(cmd)) return false

        repeat(30) {
            if (delayMs > 0) Thread.sleep(delayMs)
            onProgress()
            if (runner(arrayOf("docker", "info")) == 0) return true
        }
        return false
    }
}
