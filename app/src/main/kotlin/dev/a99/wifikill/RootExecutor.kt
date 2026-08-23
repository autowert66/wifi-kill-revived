package dev.a99.wifikill

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object RootExecutor {

    data class ExecResult(val stdout: List<String>, val exitCode: Int)

    /**
     * Check for a working `su`. This blocks on `su -c id` (and may wait on a
     * grant prompt), so it runs on [Dispatchers.IO] and must be called from a
     * coroutine, never the main thread.
     */
    suspend fun requireRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    suspend fun exec(cmd: String, timeoutMs: Long = 5000): ExecResult =
        withContext(Dispatchers.IO) {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            try {
                if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    p.destroy()
                    p.destroyForcibly()
                }
            } catch (_: InterruptedException) {
            }
            ExecResult(p.inputStream.bufferedReader().readLines(), p.exitValue())
        }

    /**
     * Launch a command as root, fully detached from this app process.
     *
     * setsid gives the child its own session: once the su shell exits the
     * child reparents to init, which makes it invisible to Android 12+'s
     * phantom process killer (it only tracks app-descendant children) and
     * immune to the SIGHUP that kills backgrounded su children when their
     * session closes. Output is discarded except for one stderr line: the
     * child pid echoed by the shell.
     */
    fun startDetached(cmd: String): Int = try {
        val p = Runtime.getRuntime().exec(
            arrayOf("su", "-c", "setsid $cmd </dev/null >/dev/null 2>&1 & echo \"\$!\" >&2")
        )
        val pid = p.errorStream.bufferedReader().readLine()?.trim()?.toIntOrNull() ?: -1
        p.waitFor(2, TimeUnit.SECONDS)
        pid
    } catch (_: Exception) {
        -1
    }

    /** Send SIGTERM to a (possibly detached) root process, off-thread. */
    fun terminate(pid: Int) {
        if (pid <= 0) return
        Thread {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -TERM $pid"))
                    .waitFor(2, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Launch a command as root and keep its stdout readable.
     *
     * The command is started under `su` with `&` so we can capture its real pid
     * (echoed to stderr), then `wait` keeps the `su` shell alive as the parent
     * until the command exits. Without `wait`, `su` exits immediately and its
     * backgrounded child gets killed (SIGHUP) partway through, so `arpscan`
     * would only report a fraction of the hosts. The pid matters for `arpspoof`,
     * whose SIGTERM handler restores the victim's ARP cache before exiting.
     */
    fun startPersistent(cmd: String): PersistentProcess {
        val process = Runtime.getRuntime().exec(
            arrayOf("su", "-c", "$cmd & echo \"\$!\" >&2; wait")
        )
        val err = process.errorStream.bufferedReader()
        val pid = err.readLine()?.trim()?.toIntOrNull() ?: -1
        // Drain remaining stderr in the background so the child never blocks
        // once the pipe buffer fills up.
        val drainer = Thread {
            runCatching { while (err.readLine() != null) { /* discard */ } }
        }.apply { isDaemon = true }
        drainer.start()
        return PersistentProcess(pid = pid, process = process)
    }

    class PersistentProcess(
        val pid: Int,
        private val process: Process,
    ) {

        fun childOutput(): BufferedReader? = process.inputStream.bufferedReader()

        fun childError(): BufferedReader? = process.errorStream.bufferedReader()

        val isAlive: Boolean
            get() = if (pid <= 0) {
                false
            } else {
                try {
                    val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -0 $pid"))
                    p.waitFor() == 0
                } catch (_: Exception) {
                    false
                }
            }

        fun kill() {
            if (pid > 0) {
                // Deliver SIGTERM off the calling thread; spawning `su` must
                // not block the UI thread. arpspoof's SIGTERM handler
                // restores the victim's ARP cache, then exits; its `wait` in
                // startPersistent returns and the su shell exits.
                terminate(pid)
            }
        }
    }
}
