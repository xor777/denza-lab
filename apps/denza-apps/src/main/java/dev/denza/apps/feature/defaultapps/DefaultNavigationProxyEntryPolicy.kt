package dev.denza.apps.feature.defaultapps

import android.content.Intent

enum class DefaultNavigationProxyEntryRoute {
    NAVIGATION,
    DENZA_APPS,
    SPLIT_PICKER,
}

/** Distinguishes navigation, ordinary AutoVoice app-open, and firmware split restore. */
object DefaultNavigationProxyEntryPolicy {
    private const val PRIMARY_SPLIT_CATEGORY = "byd.intent.category.START_IVI_PRIMARY"
    private const val SECONDARY_SPLIT_CATEGORY = "byd.intent.category.START_IVI_SECOND"
    private const val AUTOVOICE_PACKAGE = "com.byd.autovoice"

    fun route(
        action: String?,
        categories: Set<String>?,
        fromPackage: String?,
    ): DefaultNavigationProxyEntryRoute {
        val packageInfoLaunch = action == Intent.ACTION_MAIN &&
            categories?.contains(Intent.CATEGORY_INFO) == true
        if (!packageInfoLaunch || fromPackage == AUTOVOICE_PACKAGE) {
            return DefaultNavigationProxyEntryRoute.DENZA_APPS
        }
        if (
            categories.contains(PRIMARY_SPLIT_CATEGORY) ||
            categories.contains(SECONDARY_SPLIT_CATEGORY)
        ) {
            return DefaultNavigationProxyEntryRoute.SPLIT_PICKER
        }
        return DefaultNavigationProxyEntryRoute.NAVIGATION
    }
}
