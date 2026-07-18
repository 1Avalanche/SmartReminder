package smartagent.investigator

object DockerChecker {

    sealed class Result {
        object Ok : Result()
        object NotInstalled : Result()
        object NotRunning : Result()
    }

    fun check(): Result {
        if (runProcess("docker", "--version") != 0) return Result.NotInstalled
        return if (runProcess("docker", "info") == 0) Result.Ok else Result.NotRunning
    }

    private fun runProcess(vararg cmd: String): Int =
        ProcessBuilder(*cmd)
            .redirectErrorStream(true)
            .start()
            .waitFor()
}
