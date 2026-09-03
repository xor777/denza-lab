package dev.denza.apps.feature.defaultapps

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Cross-process target storage for the built-in proxy.
 *
 * The editor runs in the main process and the cold trampoline in `:picker`. SharedPreferences is
 * process-cached, so a long-lived picker process could observe a stale target. Opening an
 * [AtomicFile] for every read gives either the complete previous value or the complete new value.
 */
object DefaultNavigationProxyStore {
    private const val FILE_NAME = "default-navigation-proxy-target"

    fun read(context: Context): String? {
        val value = try {
            String(file(context).readFully(), Charsets.UTF_8).trim()
        } catch (_: FileNotFoundException) {
            return null
        } catch (error: IOException) {
            Log.w(TAG, "Could not read navigation proxy target", error)
            return null
        }
        return value.takeIf(DefaultNavigationProxyContract::isValidTarget)
    }

    fun write(context: Context, packageName: String): String {
        require(DefaultNavigationProxyContract.isValidTarget(packageName)) {
            "Invalid navigation proxy target: $packageName"
        }
        val targetFile = file(context)
        val output = targetFile.startWrite()
        try {
            output.write(packageName.toByteArray(Charsets.UTF_8))
            output.fd.sync()
            targetFile.finishWrite(output)
        } catch (error: IOException) {
            targetFile.failWrite(output)
            throw IllegalStateException("Could not persist navigation proxy target", error)
        }
        return checkNotNull(read(context)) {
            "Navigation proxy target readback failed"
        }.also { persisted ->
            check(persisted == packageName) {
                "Navigation proxy readback was $persisted, expected $packageName"
            }
        }
    }

    private fun file(context: Context): AtomicFile = AtomicFile(
        File(context.applicationContext.filesDir, FILE_NAME),
    )

    private const val TAG = "DenzaNavProxyStore"
}
