package smartagent

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class Spinner(private val message: String = "${Colors.DARK_GRAY}думаю${Colors.RESET}") {
    private val done = AtomicBoolean(false)
    private val frames = listOf("|", "/", "-", "\\")
    private val worker = thread(isDaemon = true) {
        var idx = 0
        while (!done.get()) {
            print("\r$message ${frames[idx]}")
            System.out.flush()
            idx = (idx + 1) % frames.size
            Thread.sleep(100)
        }
    }

    fun stop() {
        done.set(true)
        worker.join(200)
        System.err.print("\r[K")
        System.err.flush()
    }
}
