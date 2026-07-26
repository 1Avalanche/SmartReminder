package smartagent.investigator

class DockerChecker(
    private val runner: (Array<out String>) -> Int = { cmd ->
        val process = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            -1
        } else {
            process.exitValue()
        }
    },
    private val launcher: (List<String>) -> Boolean = { cmd ->
        runCatching { ProcessBuilder(cmd).redirectErrorStream(true).start(); true }.getOrDefault(false)
    },
    private val osName: String = System.getProperty("os.name"),
    private val delayMs: Long = 2000L,
    private val fileExists: (String) -> Boolean = { java.io.File(it).exists() }
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

    /** Запускает Docker Desktop и ждёт готовности демона (до ~180 сек).
     *  Возвращает true если демон поднялся, false — если нет или ОС не поддерживается. */
    fun startAndWait(onProgress: () -> Unit): Boolean {
        val os = osName.lowercase()
        val launched = when {
            os.contains("mac") -> launcher(listOf("open", "-a", "Docker"))
            os.contains("win") -> launchDockerWindows()
            else -> return false
        }

        if (!launched) {
            println("[DockerChecker] Не удалось запустить Docker Desktop")
            return false
        }

        repeat(90) { attempt ->
            if (delayMs > 0) Thread.sleep(delayMs)
            onProgress()
            val rc = runner(arrayOf("docker", "info"))
            println("[DockerChecker] попытка ${attempt + 1}/90, docker info exit=$rc")
            if (rc == 0) return true
        }
        return false
    }

    private fun launchDockerWindows(): Boolean {
        // Primary: PowerShell Start-Process finds Docker Desktop via App Paths registry — no hardcoded path needed
        println("[DockerChecker] Пробую запустить Docker через PowerShell Start-Process...")
        val psLaunched = launcher(listOf("powershell", "-Command", "Start-Process 'Docker Desktop'"))
        if (psLaunched) {
            println("[DockerChecker] PowerShell Start-Process: успех")
            return true
        }

        // Fallback: search known installation paths
        println("[DockerChecker] PowerShell не сработал, ищу Docker Desktop.exe...")
        val localAppData = System.getenv("LOCALAPPDATA") ?: ""
        val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
        val programFilesX86 = System.getenv("ProgramFiles(x86)") ?: "C:\\Program Files (x86)"
        val candidates = listOf(
            "$localAppData\\Programs\\Docker\\Docker\\Docker Desktop.exe",
            "$programFiles\\Docker\\Docker\\Docker Desktop.exe",
            "$programFilesX86\\Docker\\Docker\\Docker Desktop.exe",
            "C:\\Program Files\\Docker\\Docker\\Docker Desktop.exe",
            "C:\\Program Files (x86)\\Docker\\Docker\\Docker Desktop.exe"
        )
        val found = candidates.firstOrNull { fileExists(it) }
        if (found == null) {
            println("[DockerChecker] Docker Desktop.exe не найден. Проверенные пути:")
            candidates.forEach { println("[DockerChecker]   $it") }
            return false
        }
        println("[DockerChecker] Найден: $found, запускаю...")
        return launcher(listOf(found))
    }
}
