package smartagent

import java.io.FileInputStream
import kotlin.concurrent.thread

internal class EscCanceller(private val onEsc: () -> Unit) {
    @Volatile var wasCancelled: Boolean = false
        private set
    @Volatile private var tty: FileInputStream? = null

    fun start(): Thread = thread(isDaemon = true) {
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "stty raw -echo </dev/tty")).waitFor()
            val stream = FileInputStream("/dev/tty")
            tty = stream
            while (true) {
                val b = stream.read()
                if (b == -1) break
                if (b == 0x1B) {
                    wasCancelled = true
                    onEsc()
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching {
                Runtime.getRuntime().exec(arrayOf("sh", "-c", "stty -raw echo </dev/tty")).waitFor()
            }
        }
    }

    fun stop() {
        runCatching { tty?.close() }
    }
}
