package dev.denza.apps.feature.split

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/** Engine-side launch catalog. The visible picker owns icons and labels in its own package. */
internal object SplitPickerCatalog {
    @Synchronized
    fun load(context: Context): List<SplitLaunchTarget> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        return context.packageManager
            .queryIntentActivities(launcher, PackageManager.GET_META_DATA)
            .mapNotNull { info ->
                val activityInfo = info.activityInfo ?: return@mapNotNull null
                val packageName = activityInfo.packageName
                if (
                    activityInfo.name == APP_DETAILS_ACTIVITY ||
                    !SplitPickerVisibilityPolicy.isVisible(
                        packageName = packageName,
                        showInAppList = readShowInAppList(context, packageName),
                    ) ||
                    !seen.add(packageName)
                ) {
                    return@mapNotNull null
                }
                SplitLaunchTarget(
                    packageName = packageName,
                    componentName = ComponentName(packageName, activityInfo.name)
                        .flattenToShortString(),
                    launchMode = activityInfo.launchMode,
                )
            }
    }

    fun resolve(context: Context, packageName: String): SplitLaunchTarget? =
        load(context).firstOrNull { it.packageName == packageName }

    @Suppress("DEPRECATION")
    private fun readShowInAppList(context: Context, packageName: String): Boolean? {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_META_DATA or PackageManager.GET_PROVIDERS,
            )
        }.getOrNull() ?: return null

        packageInfo.applicationInfo?.metaData
            ?.takeIf { it.containsKey(SHOW_IN_APP_LIST) }
            ?.get(SHOW_IN_APP_LIST)
            ?.toString()
            ?.let(SplitPickerVisibilityPolicy::parseShowInAppList)
            ?.let { return it }

        val authorities = packageInfo.providers
            .orEmpty()
            .asSequence()
            .filter { provider ->
                provider.name?.endsWith(DYNA_CONFIG_PROVIDER_SUFFIX) == true ||
                    provider.authority
                        ?.split(';')
                        ?.any { it.endsWith(DYNA_CONFIG_PROVIDER_SUFFIX) } == true
            }
            .flatMap { it.authority.orEmpty().split(';').asSequence() }
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()

        for (authority in authorities) {
            val value = runCatching {
                context.contentResolver.query(
                    Uri.parse("content://$authority"),
                    arrayOf(SHOW_IN_APP_LIST),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val index = cursor.getColumnIndex(SHOW_IN_APP_LIST)
                    if (index < 0 || !cursor.moveToFirst() || cursor.isNull(index)) null
                    else SplitPickerVisibilityPolicy.parseShowInAppList(cursor.getString(index))
                }
            }.getOrNull()
            if (value != null) return value
        }
        return null
    }

    private const val APP_DETAILS_ACTIVITY = "android.app.AppDetailsActivity"
    private const val SHOW_IN_APP_LIST = "ShowInAppList"
    private const val DYNA_CONFIG_PROVIDER_SUFFIX = "DynaConfigContentProvider"
}

internal object SplitPickerVisibilityPolicy {
    fun isVisible(packageName: String, showInAppList: Boolean?): Boolean =
        packageName.isNotBlank() && packageName !in EXCLUDED_PACKAGES && showInAppList != false

    fun parseShowInAppList(value: String): Boolean? = when (value.trim().lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }

    private val EXCLUDED_PACKAGES = setOf("dev.denza.apps", "com.android.launcher3")
}
