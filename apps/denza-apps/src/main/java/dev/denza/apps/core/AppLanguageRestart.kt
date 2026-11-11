package dev.denza.apps.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import dev.denza.apps.MainActivity

/**
 * Where the choice is kept. One small file of its own, so that reading it in
 * `Application.attachBaseContext` costs little and `:picker` loads nothing else's preferences.
 *
 * Both processes read it, each once as it starts, and only the main process writes it. SharedPreferences
 * keeps a copy per process, so this holds only because `:picker` is ended whenever the value changes.
 */
internal object AppLanguageStore {
    private const val TAG = "DenzaLanguage"
    private const val PREFS = "app_language"
    private const val KEY = "language"

    /**
     * Takes the base context `attachBaseContext` is handed, since no application context exists yet.
     * A read that fails (credential storage before the user unlocks throws) means Russian.
     */
    fun read(base: Context): AppLanguage = AppLanguage.ofStored(
        runCatching { prefs(base).getString(KEY, null) }
            .onFailure { error -> Log.w(TAG, "the stored language could not be read", error) }
            .getOrNull(),
    )

    /** `commit`, so the next process cannot start before the value is on disk. */
    fun write(context: Context, language: AppLanguage): Boolean = runCatching {
        prefs(context).edit().putString(KEY, language.stored).commit()
    }.onFailure { error -> Log.w(TAG, "the language could not be stored", error) }.getOrDefault(false)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Applies a language the way a sleep of the car ends the app: the processes die and the next start
 * reads what is stored. `DenzaAppsApplication.onCreate` then runs the same recovery contour it runs at
 * every wake, so nothing here restarts a feature by itself.
 *
 * Runs on the main thread, in the tap. A resumed `MainActivity` cannot answer the pause that the
 * relaunch asks of it, so the new one starts in a new process. A paused one owes no pause, and the launch
 * scheduled to this dying process should still be relaunched (AOSP from memory, `docs/system-language.md`).
 */
internal object AppLanguageRestart {
    private const val TAG = "DenzaLanguage"

    fun choose(context: Context, chosen: AppLanguage): AppLanguageSwitch.Result {
        val current = AppText.language
        val result = AppLanguageSwitch.choose(current, chosen, Steps(context.applicationContext, current))
        // A write has logged itself by now; after a successful one this line is never reached.
        if (result == AppLanguageSwitch.Result.SAME_LANGUAGE) Log.i(TAG, "language $current -> $chosen: $result")
        return result
    }

    private class Steps(private val app: Context, private val current: AppLanguage) : AppLanguageSwitch.Steps {
        /** The log line lives here because `endSelf` ends the process before `choose` could return. */
        override fun write(language: AppLanguage): Boolean {
            val written = AppLanguageStore.write(app, language)
            Log.i(TAG, "language $current -> $language: " + if (written) "written, restarting" else "write failed, staying")
            return written
        }

        /** Our own UID, so the kernel allows it, and `runningAppProcesses` lists only our own. */
        override fun endPicker() {
            val picker = "${app.packageName}:picker"
            app.getSystemService(ActivityManager::class.java)?.runningAppProcesses
                ?.filter { it.processName == picker }
                ?.forEach { Process.killProcess(it.pid) }
        }

        /**
         * `MainActivity` is `singleTask` with an affinity of its own, so the clear finds its task and
         * no other: the pickers, the capture request and the launcher entries all have different
         * affinities. A failure here still ends the process, since a running language that differs
         * from the stored one is the one state the row could not explain.
         */
        override fun relaunch() {
            runCatching {
                app.startActivity(
                    Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            }.onFailure { error -> Log.w(TAG, "relaunch refused", error) }
        }

        override fun endSelf() {
            Process.killProcess(Process.myPid())
        }
    }
}
