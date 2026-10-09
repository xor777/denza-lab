package dev.denza.apps.feature.fse

import android.content.Context
import dev.denza.apps.AppIcons
import dev.denza.apps.core.Decision
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus
import dev.denza.apps.core.StateCell
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * What «Экран справа» shows: the last install as the tile says it, and what the car offers to send.
 *
 * Two fields of the dashboard's state, written only by [FseInstallRuntime]: no slice reads them.
 */
data class FseInstallState(
    val install: FeatureSnapshot,
    val apps: List<FseInstallApp>,
)

/**
 * The «Экран справа» tile behind its chooser: the list read for it, one install at a time on a
 * thread of its own, and the install's progress written to [state] as it goes.
 *
 * It lived inside `DenzaAppRepository` until 2026-10-09 - the claim, the list and the executor
 * the install ran on, which nothing else used. The code moved as it was.
 *
 * [context] is the application's, or null before the app is initialised: every command then
 * answers that nothing happened.
 */
class FseInstallRuntime internal constructor(
    private val state: StateCell<FseInstallState>,
    private val context: () -> Context?,
    private val executor: Executor,
) {
    constructor(state: StateCell<FseInstallState>, context: () -> Context?) :
        this(
            state,
            context,
            Executors.newSingleThreadExecutor { runnable ->
                // Named so its lines in logcat say whose they are; otherwise the default factory's.
                Thread(runnable, "denza-fse-install").apply {
                    isDaemon = false
                    priority = Thread.NORM_PRIORITY
                }
            },
        )

    /**
     * Read what «Экран справа» can offer, before its chooser opens; false when it must not open -
     * an install is already under way - and the screen leaves it shut.
     *
     * On the caller's thread, as it always was: the chooser opens drawn, list and pictures both.
     */
    fun refreshApps(): Boolean {
        val context = context() ?: return false
        if (FseInstallStatus.installing(state.value.install)) return false
        val installedApps = FseAppInstaller.installedApps(context)
        // The pictures are read with the list, as they always were, so the chooser opens drawn.
        installedApps.filter(FseInstallApp::installable).forEach { app ->
            AppIcons.load(context, app.packageName)
        }
        state.update { current -> current.copy(apps = installedApps) }
        return true
    }

    /**
     * One application pressed in the chooser; true when its install has started and the chooser
     * closes, false when the chooser stays.
     */
    fun install(packageName: String): Boolean {
        val context = context() ?: return false
        when (claim(packageName)) {
            FseInstallClaim.BUSY -> return false
            // The list the picker was drawn from no longer matches the car. Re-read it and leave
            // the picker standing: a working chooser is the answer, not a note about the old one.
            FseInstallClaim.STALE -> {
                refreshApps()
                return false
            }
            FseInstallClaim.START -> Unit
        }
        executor.execute {
            val result = FseAppInstaller.install(context, packageName) { message ->
                val progress = FseInstallStatus.progress(message)
                state.update { current -> current.copy(install = progress) }
            }
            val completed = FseInstallStatus.of(result)
            state.update { current -> current.copy(install = completed) }
        }
        return true
    }

    /**
     * Whether a tap on [packageName] may start an install, and the tile's «starting» if it may -
     * decided over the state as it stands, so two taps cannot both start one.
     */
    internal fun claim(packageName: String): FseInstallClaim = state.decide { current ->
        if (FseInstallStatus.installing(current.install)) {
            return@decide Decision.none(FseInstallClaim.BUSY)
        }

        val app = current.apps.firstOrNull { it.packageName == packageName }
        // A package that cannot be sent across is already drawn as unpressable, so reaching
        // here means the list is out of date. Both of these used to write a line of amber over
        // the grid instead - "APK недоступен", "Приложение больше не найдено" - which is the
        // picker teaching the driver to read explanations of taps that will never work.
        if (app == null || !app.installable) return@decide Decision.none(FseInstallClaim.STALE)

        val starting = FeatureSnapshot(
            id = FeatureId.FSE_INSTALLER,
            desiredEnabled = false,
            status = FeatureStatus.STARTING,
            message = app.label,
        )
        Decision(current.copy(install = starting), FseInstallClaim.START)
    }
}

/**
 * What a tap on the passenger picker turns out to be.
 *
 * [STALE] is the one case worth telling apart: the picker was drawn from a list that has since
 * changed under it, so the honest answer is a picker that shows the car as it is now rather
 * than a sentence explaining why the tile the driver just pressed did nothing.
 */
internal enum class FseInstallClaim { START, BUSY, STALE }
