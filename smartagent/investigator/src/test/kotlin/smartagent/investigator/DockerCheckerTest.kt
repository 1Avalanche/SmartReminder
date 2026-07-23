package smartagent.investigator

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerCheckerTest {

    // --- helpers ---

    private fun runnerReturning(vararg codes: Int): (Array<out String>) -> Int {
        var idx = 0
        return { if (idx < codes.size) codes[idx++] else codes.last() }
    }

    private fun alwaysLaunch(success: Boolean = true): (List<String>) -> Boolean = { success }

    private fun checker(
        runner: (Array<out String>) -> Int,
        launcher: (List<String>) -> Boolean = alwaysLaunch(),
        osName: String = "Mac OS X"
    ) = DockerChecker(runner = runner, launcher = launcher, osName = osName, delayMs = 0L)

    // --- check() ---

    @Test
    fun `check returns NotInstalled when docker version fails`() {
        val result = checker(runnerReturning(1)).check()
        assertEquals(DockerChecker.Result.NotInstalled, result)
    }

    @Test
    fun `check returns NotRunning when docker info fails`() {
        val result = checker(runnerReturning(0, 1)).check()
        assertEquals(DockerChecker.Result.NotRunning, result)
    }

    @Test
    fun `check returns Ok when both commands succeed`() {
        val result = checker(runnerReturning(0, 0)).check()
        assertEquals(DockerChecker.Result.Ok, result)
    }

    // --- startAndWait() ---

    @Test
    fun `startAndWait returns false on Linux`() {
        val result = checker(runnerReturning(0), osName = "Linux").startAndWait {}
        assertFalse(result)
    }

    @Test
    fun `startAndWait returns false when launcher fails`() {
        val result = checker(runnerReturning(1), launcher = alwaysLaunch(false)).startAndWait {}
        assertFalse(result)
    }

    @Test
    fun `startAndWait returns true when docker ready on first poll`() {
        val result = checker(runnerReturning(0)).startAndWait {}
        assertTrue(result)
    }

    @Test
    fun `startAndWait returns true when docker ready after several polls`() {
        // docker info fails twice, then succeeds
        val result = checker(runnerReturning(1, 1, 0)).startAndWait {}
        assertTrue(result)
    }

    @Test
    fun `startAndWait returns false when docker never starts within timeout`() {
        val result = checker(runnerReturning(1)).startAndWait {}
        assertFalse(result)
    }

    @Test
    fun `startAndWait calls onProgress on each poll`() {
        var count = 0
        // fails twice, then succeeds — onProgress called on every iteration including the success one
        checker(runnerReturning(1, 1, 0)).startAndWait { count++ }
        assertEquals(3, count)
    }

    @Test
    fun `startAndWait passes correct command on macOS`() {
        var capturedCmd: List<String>? = null
        val launcher: (List<String>) -> Boolean = { cmd -> capturedCmd = cmd; true }
        checker(runnerReturning(0), launcher = launcher, osName = "Mac OS X").startAndWait {}
        assertEquals(listOf("open", "-a", "Docker"), capturedCmd)
    }

    @Test
    fun `startAndWait works on Windows`() {
        var capturedCmd: List<String>? = null
        val launcher: (List<String>) -> Boolean = { cmd -> capturedCmd = cmd; true }
        checker(runnerReturning(0), launcher = launcher, osName = "Windows 10").startAndWait {}
        assertTrue(capturedCmd?.first()?.contains("Docker Desktop.exe") == true)
    }
}
