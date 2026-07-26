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
        osName: String = "Mac OS X",
        fileExists: (String) -> Boolean = { false }
    ) = DockerChecker(runner = runner, launcher = launcher, osName = osName, delayMs = 0L, fileExists = fileExists)

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
    fun `startAndWait returns false when launcher fails on macOS`() {
        val result = checker(runnerReturning(1), launcher = alwaysLaunch(false)).startAndWait {}
        assertFalse(result)
    }

    @Test
    fun `startAndWait returns false when all Windows launch methods fail`() {
        // PowerShell fails AND no .exe found
        val result = checker(
            runnerReturning(1),
            launcher = alwaysLaunch(false),
            osName = "Windows 10",
            fileExists = { false }
        ).startAndWait {}
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
    fun `startAndWait uses PowerShell as primary on Windows`() {
        val calls = mutableListOf<List<String>>()
        val launcher: (List<String>) -> Boolean = { cmd -> calls.add(cmd); true }
        checker(runnerReturning(0), launcher = launcher, osName = "Windows 10").startAndWait {}
        // First call must be PowerShell
        assertEquals("powershell", calls.first().first())
        assertTrue(calls.first().contains("Start-Process 'Docker Desktop'").not().let { calls.first().any { it.contains("Start-Process") } })
    }

    @Test
    fun `startAndWait falls back to exe when PowerShell fails on Windows`() {
        var callIdx = 0
        val launcher: (List<String>) -> Boolean = { _ -> callIdx++ > 0 } // first call (PS) fails, second (.exe) succeeds
        checker(
            runnerReturning(0),
            launcher = launcher,
            osName = "Windows 10",
            fileExists = { path -> path.contains("Docker Desktop.exe") }
        ).startAndWait {}
        assertEquals(2, callIdx) // PS tried + exe tried
    }
}
