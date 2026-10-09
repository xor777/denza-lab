package dev.denza.apps.platform.shell

/**
 * The shell commands with which this app gives itself a right or sets a system setting: an app op,
 * a permission, a `settings` key.
 *
 * Packages, permissions, ops, modes, namespaces and keys are names the platform defines - letters,
 * digits, `_` and `.` - and they go on the line bare, as every one of these commands always sent
 * them. A name that would need quoting is refused rather than quoted: it is a caller's mistake, and
 * the shell is the wrong place to find out. A setting's value is data and is quoted
 * ([shellQuote]), except a plain integer, which is a word as it stands.
 */
internal object ShellGrants {

    /** `cmd appops set`: [op] such as `SYSTEM_ALERT_WINDOW`, [mode] such as `allow`. */
    fun appop(packageName: String, op: String, mode: String): String =
        "cmd appops set ${name(packageName)} ${name(op)} ${name(mode)}"

    /** `pm grant`: a runtime or development [permission] such as `android.permission.RECORD_AUDIO`. */
    fun permission(packageName: String, permission: String): String =
        "pm grant ${name(packageName)} ${name(permission)}"

    fun settingsPut(namespace: String, key: String, value: String): String =
        "settings put ${namespace(namespace)} ${name(key)} " +
            if (value.matches(INTEGER)) value else shellQuote(value)

    fun settingsDelete(namespace: String, key: String): String =
        "settings delete ${namespace(namespace)} ${name(key)}"

    private fun name(value: String): String = value.also {
        require(it.matches(NAME)) { "not a platform name: \"$it\"" }
    }

    private fun namespace(value: String): String = value.also {
        require(it in NAMESPACES) { "not a settings namespace: \"$it\"" }
    }

    private val NAME = Regex("[A-Za-z0-9_.]+")
    private val INTEGER = Regex("-?[0-9]+")
    private val NAMESPACES = setOf("system", "secure", "global")
}
