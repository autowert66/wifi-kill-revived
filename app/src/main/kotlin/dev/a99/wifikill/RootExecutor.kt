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
     * Launch a non-blocking process that keeps running in the background.
     * The returned handle keeps a reference to both the `sh` parent and the
     * exec'd child process so stdout can be streamed and the pid recovered.
     */
    fun startPersistent(cmd: String): PersistentProcess {
        val sh = Runtime.getRuntime().exec(arrayOf("sh", "-c", "$cmd & echo \$!"))
        val pidLine = BufferedReader(InputStreamReader(sh.inputStream)).readLine()?.trim()
        val pid = pidLine?.filter { it.isDigit() }?.toIntOrNull() ?: -1
        val child = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        return PersistentProcess(pid = pid, sh = sh, child = child)
    }

    class PersistentProcess(
        val pid: Int,
        private val sh: Process? = null,
        private val child: Process? = null,
    ) {

        fun childOutput(): BufferedReader? = child?.inputStream?.bufferedReader()

        fun childError(): BufferedReader? = child?.errorStream?.bufferedReader()

        fun kill() {
            if (pid > 0) {
                try {
                    Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -TERM $pid"))
                        .waitFor(2, TimeUnit.SECONDS)
                } catch (_: Exception) {
                }
            }
            child?.destroy()
            child?.destroyForcibly()
            sh?.destroy()
            sh?.destroyForcibly()
        }

        val isAlive: Boolean
            get() = child?.isAlive == true || sh?.isAlive == true
    }
}