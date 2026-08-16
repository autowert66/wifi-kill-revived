package dev.a99.wifikill

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object RootExecutor {

    data class ExecResult(val stdout: List<String>, val exitCode: Int)

    private fun isRootAvailable(): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        out.contains("uid=0")
    } catch (_: Exception) {
        false
    }

    fun requireRoot(): Boolean = isRootAvailable()

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
     * Launch a command as root in the background and keep its stdout readable.
     *
     * The command is started under `su` with `&` so `su` returns immediately;
     * its real (root) pid is echoed to stderr and captured so callers can later
     * deliver SIGTERM directly. This matters for `arpspoof`, whose SIGTERM
     * handler restores the victim's ARP cache before exiting.
     *
     * Returns a handle with the pid and the single underlying [Process]. If the
     * command never starts, [PersistentProcess.pid] is -1.
     */
    fun startPersistent(cmd: String): PersistentProcess {
        val process = Runtime.getRuntime().exec(
            arrayOf("su", "-c", "$cmd & echo \"\$!\" >&2")
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
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -TERM $pid"))
                        .waitFor(2, TimeUnit.SECONDS)
                } catch (_: Exception) {
                }
            }
            process.destroy()
        }
    }
}
