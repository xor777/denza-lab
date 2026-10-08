package dev.denza.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a package's picture comes from: the two questions [iconOf] asks, in its order. [T] is a
 * `Drawable` in the app and anything at all in a test.
 */
internal interface AppIconSource<T : Any> {
    /** The icon of the package's launcher activity - what the home screen draws - or null. */
    fun launcherIcon(packageName: String): T?

    /** The application's own icon, or null when the package is not on the car. */
    fun applicationIcon(packageName: String): T?
}

/**
 * The one rule every chooser and every panel row draws by: the picture the driver knows from the
 * car's home screen, which is the icon of the package's launcher activity, and the application's
 * own icon only for a package the launcher does not show. One rule, so a package looks the same
 * on every page whichever list happened to read it first.
 */
internal fun <T : Any> AppIconSource<T>.iconOf(packageName: String): T? =
    launcherIcon(packageName) ?: applicationIcon(packageName)

/** [iconOf] asked of the package manager, one package at a time. Binder calls: not on main. */
internal class PackageManagerIconSource(
    private val packageManager: PackageManager,
) : AppIconSource<Drawable> {
    override fun launcherIcon(packageName: String): Drawable? {
        val launcher = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(packageName)
        @Suppress("DEPRECATION")
        val activity = packageManager.queryIntentActivities(launcher, 0).firstOrNull() ?: return null
        return activity.loadIcon(packageManager)
    }

    override fun applicationIcon(packageName: String): Drawable? = try {
        packageManager.getApplicationIcon(packageName)
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }
}

/**
 * Application icons by package, held once for the process and kept out of the dashboard's state.
 *
 * They used to travel inside the state, a `Drawable` on every application a chooser or a row could
 * show. The package manager hands out a fresh instance on every read and a `Drawable` compares by
 * identity, so no two recomputes of the state were ever equal and every one of them redrew the
 * whole dashboard. The state says which package; the screen asks here for its picture.
 *
 * The launcher catalog leaves what it read here ([put]); anything else is read once on first use
 * ([load], never on the main thread) and kept, and a package the car installs, updates or removes
 * is dropped ([forget]) so its next picture is the new one.
 */
internal object AppIcons {
    private val icons = ConcurrentHashMap<String, Drawable>()
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The icon already held, without asking the car. */
    fun cached(packageName: String): Drawable? = icons[packageName]

    /** Whether the car has said it has no such package since it was last [forget]-ed. */
    fun isMissing(packageName: String): Boolean = packageName in missing

    /** What a catalog read already holds, so the screen never asks for it again. */
    fun put(packageName: String, icon: Drawable?) {
        if (icon == null) return
        icons[packageName] = icon
        missing.remove(packageName)
    }

    /**
     * The icon by [iconOf], read from the package manager the first time. Binder calls: not on
     * main.
     */
    fun load(context: Context, packageName: String): Drawable? {
        icons[packageName]?.let { return it }
        if (packageName in missing) return null
        val icon = try {
            PackageManagerIconSource(context.packageManager).iconOf(packageName)
        } catch (_: RuntimeException) {
            return null
        }
        if (icon == null) missing += packageName else icons[packageName] = icon
        return icon
    }

    /** A package changed on the car: its next picture is read again. */
    fun forget(packageName: String) {
        icons.remove(packageName)
        missing.remove(packageName)
    }
}
