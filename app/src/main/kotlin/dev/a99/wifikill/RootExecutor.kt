package dev.a99.wifikill

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object RootExecutor {

    private const val TAG = "RootExecutor"

    private val SU_CANDIDATES = arrayOf(
        "su", "/system/bin/su", "/system/xbin/su", "/sbin/su",
        "/data/adb/ksu/bin/su", "/data/adb/magisk/su",
    )

    data class ExecResult(val stdout: List<String>, val exitCode: Int)

    // KernelSU hides its binary from ungranted apps entirely, so "su" can be
    // a valid ENOENT here even though the device is rooted. Probe once and
    // remember whichever invocation works.
    @Volatile
    private var suPath: String? = null

    /** Resolved lazily; safe to call repeatedly. */
    fun suCommand(): String {
        suPath?.let { return it }
        for (candidate in SU_CANDIDATES) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf(candidate, "-c", "id"))
                p.waitFor(3, TimeUnit.SECONDS)
                if (p.exitValue() == 0 &&
                    p.inputStream.bufferedReader().readText().contains("uid=0")
                ) {
                    suPath = candidate
                    return candidate
                }
            } catch (_: Exception) {
                // missing binary or timed-out prompt: try the next candidate
            }
        }
        return SU_CANDIDATES.first()
    }

    /**
     * Check for working root. Never throws: on devices where no su is
     * reachable this simply reports false.
     */
    suspend fun requireRoot(): Boolean {
        val result = exec("id")
        return result.exitCode == 0 && result.stdout.any { it.contains("uid=0") }
    }

    suspend fun exec(cmd: String, timeoutMs: Long = 5000): ExecResult =
        withContext(Dispatchers.IO) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf(suCommand(), "-c", cmd))
                try {
                    if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                        p.destroy()
                        p.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                }
                ExecResult(p.inputStream.bufferedReader().readLines(), p.exitValue())
            } catch (_: Exception) {
                ExecResult(emptyList(), -1)
            }
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
            arrayOf(suCommand(), "-c", "setsid $cmd </dev/null >/dev/null 2>&1 & echo \"\$!\" >&2")
        )
        val pid = p.errorStream.bufferedReader().readLine()?.trim()?.toIntOrNull() ?: -1
        p.waitFor(2, TimeUnit.SECONDS)
        pid
    } catch (_: Exception) {
        -1
    }

    /**
     * Send SIGTERM to a (possibly detached) root process, off-thread, and
     * make sure it actually landed.
     *
     * Rules learned the hard way:
     *  - Liveness is checked via /proc/<pid> existence, not `kill -0`: an
     *    su invocation that fails or times out must count as INCONCLUSIVE,
     *    never as "process died", or we skip the TERM and leave the victim
     *    blackholed.
     *  - TERM is retried, then escalated to KILL as a last resort -- a KILL
     *    skips the graceful ARP repair, which the app-level watchdog will
     *    compensate for.
     */
    fun terminate(pid: Int) {
        if (pid <= 0) return
        Thread {
            try {
                for (attempt in 1..32) {
                    // 0 = confirmed gone, 1 = exists, null = su hiccup
                    when (runSu("[ ! -d /proc/$pid ]")) {
                        0 -> {
                            if (attempt > 1) Log.d(TAG, "spoofer $pid gone after $attempt polls")
                            return@Thread
                        }
                        1 -> Unit
                        else -> { /* inconclusive: keep polling */ }
                    }
                    when (attempt) {
                        1, 8, 16 -> runSu("kill -TERM $pid")
                        22 -> {
                            Log.w(TAG, "spoofer $pid survived TERM, escalating to KILL")
                            runSu("kill -9 $pid")
                        }
                    }
                    Thread.sleep(250)
                }
                Log.e(TAG, "terminate($pid): still alive after escalation window")
            } catch (_: InterruptedException) {
            }
        }.apply { isDaemon = true }.start()
    }

    /** Run via resolved su; returns exit code, or null on failure/timeout. */
    private fun runSu(cmd: String): Int? = try {
        val p = Runtime.getRuntime().exec(arrayOf(suCommand(), "-c", cmd))
        if (p.waitFor(4, TimeUnit.SECONDS)) p.exitValue() else {
            p.destroyForcibly()
            null
        }
    } catch (_: Exception) {
        null
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
            arrayOf(suCommand(), "-c", "$cmd & echo \"\$!\" >&2; wait")
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
