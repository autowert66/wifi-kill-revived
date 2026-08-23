package dev.a99.wifikill

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object BinaryDeployer {

    /**
     * Copy a packaged native tool to filesDir/bin and make it executable.
     *
     * The content-hash comparison matters because filesDir survives APK
     * updates: without it a stale binary from a previous install would
     * silently shadow the one shipped with the current build.
     */
    suspend fun deploy(context: Context, name: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "bin").apply { mkdirs() }
        val dest = File(dir, name)
        val assetHash = context.assets.open(name).use { it.readBytes().contentHashCode() }
        val currentHash = runCatching { dest.readBytes().contentHashCode() }.getOrDefault(-1)
        if (!dest.exists() || dest.length() == 0L || currentHash != assetHash) {
            context.assets.open(name).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        if (!dest.canExecute()) {
            RootExecutor.exec("chmod 755 \"${dest.absolutePath}\"")
        }
        dest
    }
}
