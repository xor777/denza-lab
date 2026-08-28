package dev.denza.apps.feature.locale

import android.content.Context
import android.content.pm.PackageManager
import android.os.LocaleList
import dev.denza.apps.adb.DenzaLocalAdb
import java.lang.reflect.InvocationTargetException

/**
 * Where the stock language switch has got to.
 *
 * [failed] is the one thing this had no way of saying. A refused change wrote a message into
 * [message] and nothing read it: the tile has no runtime feature behind it, so it took its colour
 * from [enabled] alone and stayed the quiet grey of a language that is simply off. The car had
 * refused, the screen said nothing, and pressing again was the driver's only way to find out.
 */
data class StockRussianLocaleSnapshot(
    val enabled: Boolean? = null,
    val permissionReady: Boolean = false,
    val running: Boolean = false,
    val failed: Boolean = false,
    val message: String = "Состояние не проверено",
    val details: String? = null,
)

internal data class StockRussianLocaleStatus(
    val enabled: Boolean?,
    val permissionReady: Boolean,
)

internal enum class StockRussianLocaleChange {
    REAPPLIED,
    CHANGED,
}

internal object StockRussianLocalePolicy {
    const val TARGET_PACKAGE = "com.byd.carsettings"
    const val RUSSIAN_TAG = "ru-RU"
    const val CHANGE_CONFIGURATION_PERMISSION =
        "android.permission.CHANGE_CONFIGURATION"

    fun languageTags(enabled: Boolean): String = if (enabled) RUSSIAN_TAG else ""

    fun permissionGrantCommand(packageName: String): String =
        "pm grant $packageName $CHANGE_CONFIGURATION_PERMISSION"
}

internal class StockRussianLocaleRepair(
    private val readSavedState: () -> Boolean?,
    private val writeDirectLocale: (Boolean) -> Unit,
    private val saveState: (Boolean) -> Unit,
) {
    fun inspect(): Boolean? = readSavedState()

    fun setEnabled(enabled: Boolean): StockRussianLocaleChange {
        val previous = readSavedState()

        // Reapply even when our saved value already matches. Another app can change the system
        // override, while Android does not let an ordinary app read another package's locale.
        writeDirectLocale(enabled)
        saveState(enabled)

        return if (previous == enabled) {
            StockRussianLocaleChange.REAPPLIED
        } else {
            StockRussianLocaleChange.CHANGED
        }
    }
}

internal object StockRussianLocaleCoordinator {
    private const val PREFERENCES = "stock_russian_locale"
    private const val ENABLED_KEY = "enabled"
    private const val PERMISSION_GRANT_TIMEOUT_MS = 2_500

    fun inspect(context: Context): StockRussianLocaleStatus {
        val app = context.applicationContext
        return StockRussianLocaleStatus(
            enabled = repair(app).inspect(),
            permissionReady = hasPermission(app),
        )
    }

    fun setEnabled(
        context: Context,
        enabled: Boolean,
    ): Pair<StockRussianLocaleChange, StockRussianLocaleStatus> {
        val app = context.applicationContext
        ensurePermission(app)
        val change = repair(app).setEnabled(enabled)
        return change to StockRussianLocaleStatus(
            enabled = enabled,
            permissionReady = true,
        )
    }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(StockRussianLocalePolicy.CHANGE_CONFIGURATION_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensurePermission(context: Context) {
        if (hasPermission(context)) return

        val output = DenzaLocalAdb.client(context).shell(
            StockRussianLocalePolicy.permissionGrantCommand(context.packageName),
            PERMISSION_GRANT_TIMEOUT_MS,
        )
        check(hasPermission(context)) {
            buildString {
                append("Не удалось один раз выдать CHANGE_CONFIGURATION через локальный ADB")
                output.trim().takeIf(String::isNotEmpty)?.let {
                    append(": ")
                    append(it.take(500))
                }
            }
        }
    }

    private fun repair(context: Context): StockRussianLocaleRepair {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return StockRussianLocaleRepair(
            readSavedState = {
                if (preferences.contains(ENABLED_KEY)) {
                    preferences.getBoolean(ENABLED_KEY, false)
                } else {
                    null
                }
            },
            writeDirectLocale = { enabled -> setDirectLocale(context, enabled) },
            saveState = { enabled ->
                check(preferences.edit().putBoolean(ENABLED_KEY, enabled).commit()) {
                    "Системная локаль изменена, но Denza Apps не сохранил состояние переключателя"
                }
            },
        )
    }

    private fun setDirectLocale(
        context: Context,
        enabled: Boolean,
    ) {
        val localeManager = checkNotNull(context.getSystemService(Context.LOCALE_SERVICE)) {
            "Сервис LocaleManager недоступен на этой прошивке"
        }
        val locales = StockRussianLocalePolicy.languageTags(enabled).let { tags ->
            if (tags.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tags)
        }
        val method = try {
            localeManager.javaClass.getDeclaredMethod(
                "setApplicationLocales",
                String::class.java,
                LocaleList::class.java,
            ).apply { isAccessible = true }
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException(
                "Прошивка не предоставляет прямой пакетный LocaleManager",
                error,
            )
        }

        try {
            method.invoke(localeManager, StockRussianLocalePolicy.TARGET_PACKAGE, locales)
        } catch (error: InvocationTargetException) {
            val cause = error.targetException ?: error
            throw IllegalStateException(
                "LocaleManager не изменил локаль BYD Настроек: ${cause.message ?: cause}",
                cause,
            )
        } catch (error: ReflectiveOperationException) {
            throw IllegalStateException(
                "Не удалось вызвать прямой LocaleManager для BYD Настроек",
                error,
            )
        }
    }
}
