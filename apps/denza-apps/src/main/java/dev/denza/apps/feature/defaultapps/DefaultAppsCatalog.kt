package dev.denza.apps.feature.defaultapps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable

/** One package that AutoVoice can open through PackageManager's normal MAIN lookup. */
internal data class InstalledDefaultApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
)

/** Android discovery and deterministic presentation of default-app candidates. */
internal object DefaultAppsCatalog {
    /**
     * Mirrors `getLaunchIntentForPackage`: MAIN+INFO first, then MAIN+LAUNCHER.
     *
     * Disabled, suspended and no-longer-installed packages are excluded. Multiple activities of
     * one package collapse into one app entry; the application label/icon are used so an INFO
     * helper activity cannot rename the package in this picker.
     */
    @Suppress("DEPRECATION")
    fun discover(context: Context): List<InstalledDefaultApp> {
        val packageManager = context.packageManager
        val byPackage = linkedMapOf<String, ResolveInfo>()
        listOf(Intent.CATEGORY_INFO, Intent.CATEGORY_LAUNCHER).forEach { category ->
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            packageManager.queryIntentActivities(intent, 0).forEach { resolveInfo ->
                val activity = resolveInfo.activityInfo ?: return@forEach
                val application = activity.applicationInfo ?: return@forEach
                val installed = application.flags and ApplicationInfo.FLAG_INSTALLED != 0
                val suspended = application.flags and ApplicationInfo.FLAG_SUSPENDED != 0
                if (!installed || suspended || !application.enabled || !activity.enabled) {
                    return@forEach
                }
                byPackage.putIfAbsent(activity.packageName, resolveInfo)
            }
        }

        return byPackage.map { (packageName, resolveInfo) ->
            val application = checkNotNull(resolveInfo.activityInfo?.applicationInfo)
            InstalledDefaultApp(
                packageName = packageName,
                label = runCatching {
                    packageManager.getApplicationLabel(application).toString()
                }.getOrNull().orEmpty().ifBlank { packageName },
                icon = runCatching { packageManager.getApplicationIcon(application) }.getOrNull(),
            )
        }
    }

    fun choices(
        role: DefaultAppRole,
        selectedPackageName: String?,
        installed: Collection<InstalledDefaultApp>,
    ): List<DefaultAppChoice> {
        val knownOrder = role.knownThirdPartyApps
            .withIndex()
            .associate { (index, app) -> app.packageName to index }
        val stockOrder = knownOrder.size
        val otherOrder = stockOrder + 1

        return installed
            .map { app ->
                DefaultAppChoice(
                    packageName = app.packageName,
                    label = app.label.ifBlank { fallbackLabel(role, app.packageName) },
                    icon = app.icon,
                    selected = app.packageName == selectedPackageName,
                    known = app.packageName in knownOrder,
                    stock = app.packageName == role.stockPackageName,
                )
            }
            .sortedWith(
                compareBy<DefaultAppChoice> { choice ->
                    knownOrder[choice.packageName]
                        ?: if (choice.stock) stockOrder else otherOrder
                }.thenBy(String.CASE_INSENSITIVE_ORDER) { choice ->
                    if (choice.known || choice.stock) "" else choice.label
                }.thenBy(DefaultAppChoice::packageName),
            )
    }

    fun label(
        role: DefaultAppRole,
        packageName: String?,
        installed: Collection<InstalledDefaultApp>,
    ): String = packageName?.let { selected ->
        installed.firstOrNull { it.packageName == selected }?.label
            ?: fallbackLabel(role, selected)
    } ?: "Не выбрано"

    fun isLaunchable(
        packageName: String,
        installed: Collection<InstalledDefaultApp>,
    ): Boolean = installed.any { it.packageName == packageName }

    private fun fallbackLabel(
        role: DefaultAppRole,
        packageName: String,
    ): String = role.knownThirdPartyApps
        .firstOrNull { it.packageName == packageName }
        ?.fallbackLabel
        ?: if (packageName == role.stockPackageName) {
            when (role) {
                DefaultAppRole.NAVIGATION -> "Штатная навигация"
                DefaultAppRole.MUSIC -> "Штатная музыка"
                DefaultAppRole.VIDEO -> "Штатное видео"
            }
        } else {
            packageName
        }
}
