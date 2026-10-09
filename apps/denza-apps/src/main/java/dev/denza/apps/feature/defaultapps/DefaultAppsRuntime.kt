package dev.denza.apps.feature.defaultapps

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.denza.apps.core.Decision
import dev.denza.apps.core.StateCell
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * The «Shortcuts» tile behind the screen: the three PersonBean roles read from the car, written to
 * it, and reported as they land.
 *
 * Nothing here is read by the state publisher. The roles are read and written on this runtime's
 * own thread, one provider operation at a time, and every write is claimed in [state] before it
 * leaves - the tile and the panel mark a tap the moment the finger lands, and a refresh already in
 * flight must not undo that mark. So the roles publish themselves, through [state], the one field
 * of the dashboard's state this runtime owns.
 *
 * It lived inside `DenzaAppRepository` until 2026-10-09, seven hundred lines of it beside every
 * other tile's state; the code moved as it was.
 *
 * [context] is the application's, or null before the app is initialised: every command is then
 * dropped, as it was.
 */
class DefaultAppsRuntime(
    private val state: StateCell<DefaultAppsUiState>,
    private val context: () -> Context?,
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        // Named so its lines in logcat say whose they are; otherwise the default factory's thread.
        Thread(runnable, "denza-defaultapps").apply {
            isDaemon = false
            priority = Thread.NORM_PRIORITY
        }
    }
    private val hydrated = AtomicBoolean(false)
    private val refreshRequested = AtomicBoolean(false)
    private val refreshRunning = AtomicBoolean(false)
    private val navigationRoleRepair =
        NavigationRoleRepair(DefaultAppRole.NAVIGATION.stockPackageName)
    private val roleRepositoryLock = Any()

    @Volatile
    private var roleRepository: DefaultAppRoleRepository? = null

    /**
     * Explicit, coalesced provider refresh used on Activity resume and by the settings sheet.
     *
     * Every resume revalidates PersonBean without replacing a state the driver can already see.
     * [force] additionally drops the installed-launcher cache before the refresh.
     */
    fun refresh(force: Boolean = false) {
        val context = context() ?: return
        if (force) DefaultAppsCatalogCache.invalidate()

        if (hydrated.compareAndSet(false, true)) {
            DefaultAppsCatalogCache.ensureWatching(
                context,
                onPackage = { intent -> repairNavigationRole(context, intent) },
            ) { refresh(force = false) }
            state.update { defaults ->
                defaults.copy(
                    roles = defaults.roles.map { roleState ->
                        DefaultAppsSettings.confirmedSelection(context, roleState.role)?.let {
                            roleState.copy(
                                selectedPackageName = it.selectedPackageName,
                                selectedLabel = it.selectedLabel,
                                choices = emptyList(),
                                status = DefaultAppRoleStatus.READY,
                                message = "",
                                providerConfirmed = true,
                                pendingPackageName = null,
                            )
                        } ?: roleState.copy(
                            status = DefaultAppRoleStatus.LOADING,
                            message = "Читаю настройку…",
                        )
                    },
                )
            }
        }

        state.update { defaults ->
            defaults.copy(
                refreshing = true,
                roles = defaults.roles.map { roleState ->
                    when {
                        roleState.status == DefaultAppRoleStatus.APPLYING ||
                            roleState.status == DefaultAppRoleStatus.ERROR -> roleState
                        roleState.status == DefaultAppRoleStatus.LOADING ||
                            roleState.selectedPackageName == null -> roleState.copy(
                                status = DefaultAppRoleStatus.LOADING,
                                message = "Читаю настройку…",
                            )
                        else -> roleState
                    }
                },
            )
        }

        refreshRequested.set(true)
        scheduleRefresh(context)
    }

    /**
     * The tile's switch: run the driver's applications, or hand every role back to the car.
     *
     * Off writes the car's own application into all three roles - including one that was chosen in
     * the stock settings rather than here. The narrower rule the cold start follows is right for a
     * read nobody asked for and wrong for this: the driver has just asked for the stock
     * applications, and a press that quietly left one of the three alone would be a press with
     * nothing to show for it. What each role was holding is already remembered, so on puts it back.
     */
    fun setEnabled(enabled: Boolean) {
        val context = context() ?: return
        val targets = claimSwitch(context, enabled) ?: return
        executor.execute {
            applyTargets(context, targets)
        }
    }

    /** Writes one role. Package admission is the current installed-launcher catalog, not a list. */
    fun select(
        role: DefaultAppRole,
        packageName: String,
    ) {
        val context = context() ?: return
        if (!claimSelection(context, role, packageName)) return

        executor.execute {
            applySelection(context, role, packageName)
        }
    }

    /**
     * A Store update of the chosen navigator, answered by putting it back into the map role.
     *
     * Called on the main thread from the package receiver, before that receiver starts the refresh,
     * so [NavigationRoleRepair] is handed what this app last confirmed in the role rather than the
     * stock map AutoVoice may already have written. The write itself goes to the default-apps
     * thread ahead of that refresh, which then reads back whatever it left.
     */
    private fun repairNavigationRole(context: Context, intent: Intent) {
        val event = NavigationRoleRepair.eventOf(intent.action) ?: return
        val packageName = intent.data?.schemeSpecificPart?.takeIf(String::isNotBlank) ?: return
        val role = DefaultAppRole.NAVIGATION
        val restore = navigationRoleRepair.on(
            event = event,
            packageName = packageName,
            replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false),
            held = DefaultAppsSettings.confirmedSelection(context, role)?.selectedPackageName,
        ) ?: return
        executor.execute {
            runCatching {
                runBlocking {
                    // Only over the stock map: anything else in the role is somebody's choice.
                    roleRepository(context).setIfCurrent(
                        role = role,
                        expectedCurrentPackageName = role.stockPackageName,
                        packageName = restore,
                    )
                }
            }.onSuccess {
                navigationRoleRepair.restored(restore)
                Log.i(TAG, "${role.roleKey} restored to $restore after its update")
            }.onFailure { error ->
                Log.i(TAG, "${role.roleKey} not restored to $restore after its update", error)
            }
        }
    }

    /** At most one provider refresh runs and one newer request waits behind it. */
    private fun scheduleRefresh(context: Context) {
        if (!refreshRunning.compareAndSet(false, true)) return
        executor.execute {
            try {
                refreshRequested.set(false)
                runRefresh(context)
            } catch (error: Exception) {
                publishUnavailable(
                    failure(DefaultAppsWords.UNREAD, "refresh", error),
                )
            } finally {
                refreshRunning.set(false)
                if (refreshRequested.get()) {
                    scheduleRefresh(context)
                }
            }
        }
    }

    private fun runRefresh(context: Context) {
        val launchable = runCatching { DefaultAppsCatalogCache.launchablePackages(context) }
            .getOrElse { error ->
                publishUnavailable(
                    failure(DefaultAppsWords.UNREAD, "catalog read", error),
                )
                return
            }
        val repository = roleRepository(context)
        // One provider query for all three roles. Each role still answers for itself: a missing or
        // duplicated row fails alone, and the failure reaches its role exactly as its own read did.
        val observed = runBlocking { repository.readAll() }
        val installed = DefaultAppsCatalogCache.installedIfCached()

        DefaultAppRole.entries.forEach { role ->
            // A tap accepted while this refresh was already in flight owns the role until its
            // exact set/readback completes. Refreshing the other roles must not undo APPLYING.
            if (state.value.stateFor(role).status == DefaultAppRoleStatus.APPLYING) {
                return@forEach
            }

            val roleState = refreshRole(
                context = context,
                repository = repository,
                role = role,
                launchable = launchable,
                installed = installed,
                observed = observed.getValue(role),
            )
            state.updateIf(
                predicate = { defaults ->
                    defaults.stateFor(role).status != DefaultAppRoleStatus.APPLYING
                },
                transform = { defaults -> defaults.update(role) { roleState } },
            )
        }

        if (installed == null) publishChoices(context)

        state.update { defaults ->
            defaults.copy(refreshing = refreshRequested.get())
        }
    }

    private fun publishChoices(context: Context) {
        val installed = runCatching { DefaultAppsCatalogCache.installed(context) }
            .getOrElse { error ->
                failure(DefaultAppsWords.UNREAD, "catalog read", error)
                return
            }
        DefaultAppRole.entries.forEach { role ->
            state.updateIf(
                predicate = { defaults ->
                    defaults.stateFor(role).status != DefaultAppRoleStatus.APPLYING
                },
                transform = { defaults ->
                    val roleState = defaults.stateFor(role)
                    val selectedPackageName = roleState.effectivePackageName
                    defaults.update(role) {
                        roleState.copy(
                            selectedLabel = DefaultAppsCatalog.label(
                                role,
                                selectedPackageName,
                                installed,
                            ),
                            choices = DefaultAppsCatalog.choices(
                                role,
                                selectedPackageName,
                                installed,
                            ),
                        )
                    }
                },
            )
        }
    }

    private fun scheduleChoices(context: Context) {
        executor.execute { publishChoices(context) }
    }

    private fun refreshRole(
        context: Context,
        repository: DefaultAppRoleRepository,
        role: DefaultAppRole,
        launchable: Set<String>,
        installed: List<InstalledDefaultApp>?,
        observed: Result<String>,
    ): DefaultAppRoleUiState {
        val previous = state.value.stateFor(role)
        var observedPackage: String? = null
        var writeAttempted = false

        return try {
            observedPackage = observed.getOrThrow()
            val initializationHandled = DefaultAppsSettings.isInitializationHandled(context, role)
            val coldSelection = DefaultAppsPolicy.coldStartSelection(
                role = role,
                providerPackageName = observedPackage,
                initializationHandled = initializationHandled,
                installedLaunchablePackages = launchable,
            )
            val shouldAutoSelect = DefaultAppsPolicy.shouldAutoApplyColdStartSelection(
                role = role,
                providerPackageName = observedPackage,
                initializationHandled = initializationHandled,
                resolvedPackageName = coldSelection,
            )

            val persistedPackage = if (shouldAutoSelect) {
                writeAttempted = true
                runBlocking {
                    repository.setIfCurrent(
                        role = role,
                        expectedCurrentPackageName = checkNotNull(observedPackage),
                        packageName = checkNotNull(coldSelection),
                    )
                }.also {
                    // The provider is the source of truth. Only its exact successful readback may
                    // complete first-run handling after an automatic change.
                    DefaultAppsSettings.markInitializationHandled(context, role)
                }
            } else {
                checkNotNull(observedPackage)
            }
            if (!initializationHandled && !shouldAutoSelect) {
                // A successful first resolution is final even when it keeps stock or preserves an
                // external non-stock value. Installing a known app later must not silently change
                // an already observed role.
                DefaultAppsSettings.markInitializationHandled(context, role)
            }
            providerState(
                context = context,
                role = role,
                packageName = persistedPackage,
                launchable = launchable,
                installed = installed,
                message = if (shouldAutoSelect) "Выбрано автоматически" else "",
            )
        } catch (error: Throwable) {
            val recoveredPackage = if (writeAttempted) {
                runCatching { runBlocking { repository.read(role) } }.getOrNull()
            } else {
                null
            }
            // Once an update was attempted, the pre-write observation is no longer proof of the
            // current value. Only a recovery read may confirm it; otherwise retain the old value
            // strictly as last-known UI context.
            val confirmedPackage = recoveredPackage ?: observedPackage.takeUnless { writeAttempted }
            errorState(
                context = context,
                role = role,
                selectedPackageName = confirmedPackage
                    ?: observedPackage
                    ?: previous.selectedPackageName,
                launchable = launchable,
                installed = installed,
                providerConfirmed = confirmedPackage != null,
                message = when {
                    writeAttempted -> failure(DefaultAppsWords.UNSAVED, "automatic choice", error)
                    observedPackage != null -> failure(DefaultAppsWords.UNREAD, "initialization", error)
                    else -> failure(DefaultAppsWords.UNREAD, "setting read", error)
                },
            )
        }
    }

    private fun applySelection(
        context: Context,
        role: DefaultAppRole,
        packageName: String,
    ) {
        // The chosen package is checked against the car as it is now; the catalog behind the
        // labels and icons may be the one the sheet was opened with.
        val launchable = runCatching { DefaultAppsCatalogCache.launchablePackages(context) }
            .getOrElse { error ->
                finishRole(
                    role,
                    abandonedWrite(
                        role,
                        failure(DefaultAppsWords.UNREAD, "catalog read", error),
                    ),
                )
                return
            }
        val selectable = runCatching {
            packageName in launchable &&
                DefaultAppsCatalog.isLaunchableNow(context, packageName)
        }.getOrElse { error ->
            finishRole(
                role,
                abandonedWrite(
                    role,
                    failure(DefaultAppsWords.UNREAD, "launchable check", error),
                ),
            )
            return
        }
        val installed = DefaultAppsCatalogCache.installedIfCached()
        if (!selectable) {
            val previous = state.value.stateFor(role)
            finishRole(
                role,
                errorState(
                    context = context,
                    role = role,
                    selectedPackageName = previous.selectedPackageName,
                    launchable = launchable,
                    installed = installed,
                    providerConfirmed = false,
                    message = DefaultAppsWords.GONE,
                ),
            )
            if (installed == null) scheduleChoices(context)
            return
        }
        finishRole(
            role,
            writeRole(context, role, packageName, launchable, installed),
        )
        if (installed == null) scheduleChoices(context)
    }

    /**
     * One role written to the provider and reported, whichever gesture asked for it.
     *
     * The picker asks for one role and the tile's switch asks for all three; what happens to a
     * role is the same either way, down to the failure, which is why it is written once.
     */
    private fun writeRole(
        context: Context,
        role: DefaultAppRole,
        packageName: String,
        launchable: Set<String>,
        installed: List<InstalledDefaultApp>?,
    ): DefaultAppRoleUiState {
        val repository = roleRepository(context)
        var persistedPackage: String? = null
        val result = runCatching {
            persistedPackage = runBlocking { repository.set(role, packageName) }
            // Even a deliberate stock choice becomes distinguishable only after the provider has
            // echoed it back exactly. This marker must never be written optimistically.
            if (!DefaultAppsSettings.isInitializationHandled(context, role)) {
                DefaultAppsSettings.markInitializationHandled(context, role)
            }
            checkNotNull(persistedPackage)
        }

        return result.fold(
            onSuccess = { persisted ->
                providerState(
                    context = context,
                    role = role,
                    packageName = persisted,
                    launchable = launchable,
                    installed = installed,
                )
            },
            onFailure = { error ->
                val recovered = runCatching { runBlocking { repository.read(role) } }.getOrNull()
                val confirmedPackage = recovered ?: persistedPackage
                errorState(
                    context = context,
                    role = role,
                    selectedPackageName = confirmedPackage
                        ?: state.value.stateFor(role).selectedPackageName,
                    launchable = launchable,
                    installed = installed,
                    providerConfirmed = confirmedPackage != null,
                    message = failure(DefaultAppsWords.UNSAVED, "write", error),
                )
            },
        )
    }

    /**
     * A write that never reached the provider, reported without keeping the mark it moved.
     *
     * The grid marks a tap before the write leaves, so a failure that only changes the status has
     * to put that mark back on the package the car last confirmed - otherwise the panel goes on
     * showing the choice as made while saying it was not.
     */
    private fun abandonedWrite(
        role: DefaultAppRole,
        message: String,
    ): DefaultAppRoleUiState {
        val previous = state.value.stateFor(role)
        return previous.copy(
            status = DefaultAppRoleStatus.ERROR,
            providerConfirmed = false,
            pendingPackageName = null,
            message = message,
            choices = previous.choices.map { choice ->
                choice.copy(selected = choice.packageName == previous.selectedPackageName)
            },
        )
    }

    private fun providerState(
        context: Context,
        role: DefaultAppRole,
        packageName: String,
        launchable: Set<String>,
        installed: List<InstalledDefaultApp>?,
        message: String = "",
    ): DefaultAppRoleUiState {
        if (packageName !in launchable) {
            return errorState(
                context = context,
                role = role,
                selectedPackageName = packageName,
                launchable = launchable,
                installed = installed,
                providerConfirmed = true,
                message = DefaultAppsWords.GONE,
            )
        }
        // Whatever the car is confirmed to be running for this role is what switching the
        // substitution back on should restore, so it is remembered where it is observed rather
        // than at the moment the switch is thrown - by then the role already says "stock".
        DefaultAppsSettings.rememberPick(context, role, packageName)
        val selectedLabel = installed?.let {
            DefaultAppsCatalog.label(role, packageName, it)
        } ?: DefaultAppsCatalog.labelNow(context, role, packageName)
        val choices = installed?.let {
            DefaultAppsCatalog.choices(role, packageName, it)
        } ?: state.value.stateFor(role).choices.map { choice ->
            choice.copy(selected = choice.packageName == packageName)
        }
        DefaultAppsSettings.rememberConfirmedSelection(
            context,
            role,
            packageName,
            selectedLabel,
        )
        return DefaultAppRoleUiState(
            role = role,
            selectedPackageName = packageName,
            selectedLabel = selectedLabel,
            choices = choices,
            status = DefaultAppRoleStatus.READY,
            message = message,
            providerConfirmed = true,
        )
    }

    private fun errorState(
        context: Context,
        role: DefaultAppRole,
        selectedPackageName: String?,
        launchable: Set<String>,
        installed: List<InstalledDefaultApp>?,
        providerConfirmed: Boolean,
        message: String,
    ): DefaultAppRoleUiState {
        val selectedLabel = when {
            selectedPackageName == null -> "Не выбрано"
            installed != null -> DefaultAppsCatalog.label(role, selectedPackageName, installed)
            else -> DefaultAppsCatalog.labelNow(context, role, selectedPackageName)
        }
        val choices = installed?.let {
            DefaultAppsCatalog.choices(role, selectedPackageName, it)
        } ?: state.value.stateFor(role).choices.map { choice ->
            choice.copy(selected = choice.packageName == selectedPackageName)
        }
        return DefaultAppRoleUiState(
            role = role,
            selectedPackageName = selectedPackageName,
            selectedLabel = selectedLabel,
            choices = choices,
            status = DefaultAppRoleStatus.ERROR,
            message = message,
            providerConfirmed = providerConfirmed,
        )
    }

    private fun claimSwitch(
        context: Context,
        enabled: Boolean,
    ): Map<DefaultAppRole, String>? = claimSwitch { role ->
        if (enabled) {
            switchOnTarget(context, role)
        } else {
            role.stockPackageName
        }
    }

    /**
     * Decides what each role should hold, and marks the panel with it before anything is written.
     *
     * A role with its own write in flight is skipped; the rest are still written. Returns null only
     * when no role has anything to write. [targetOf] is what the switch puts into a role, asked
     * inside the claim for each role not already applying - again whenever the claim is decided
     * again - which is where the switch's own reads always were.
     */
    internal fun claimSwitch(
        targetOf: (DefaultAppRole) -> String?,
    ): Map<DefaultAppRole, String>? = state.decide { defaults ->
        val targets = DefaultAppRole.entries.mapNotNull { role ->
            val roleState = defaults.stateFor(role)
            if (roleState.status == DefaultAppRoleStatus.APPLYING) return@mapNotNull null
            val target = targetOf(role)
            // A role already holding its target has nothing to write; the provider would
            // accept the update and report the same value back.
            target?.takeIf { it != roleState.selectedPackageName }?.let { role to it }
        }.toMap()
        if (targets.isEmpty()) return@decide Decision.none(null)

        val updated = targets.entries.fold(defaults) { applying, (role, target) ->
            applying.update(role) { roleState ->
                roleState.copy(
                    status = DefaultAppRoleStatus.APPLYING,
                    message = "",
                    pendingPackageName = target,
                    choices = roleState.choices.map { choice ->
                        choice.copy(selected = choice.packageName == target)
                    },
                )
            }
        }
        Decision(updated, targets)
    }

    /**
     * What switching the substitution on should put into a role.
     *
     * The package the car was last seen running for it, and failing that the catalog's first
     * installed suggestion - the same order a first run resolves in, so a role the driver has
     * never touched comes on holding what it would have held anyway.
     */
    private fun switchOnTarget(
        context: Context,
        role: DefaultAppRole,
    ): String? {
        val remembered = DefaultAppsSettings.rememberedPick(context, role)
        val launchable = runCatching { DefaultAppsCatalogCache.launchablePackages(context) }
            .getOrElse { error ->
                failure(DefaultAppsWords.UNREAD, "catalog read", error)
                return null
            }
        if (remembered != null && remembered in launchable) return remembered
        return role.knownThirdPartyApps
            .firstOrNull { it.packageName in launchable }
            ?.packageName
    }

    /** Writes each role of a switch, one at a time, reporting each as it lands. */
    private fun applyTargets(
        context: Context,
        targets: Map<DefaultAppRole, String>,
    ) {
        val launchable = runCatching { DefaultAppsCatalogCache.launchablePackages(context) }
            .getOrElse { error ->
                val message = failure(DefaultAppsWords.UNREAD, "catalog read", error)
                targets.keys.forEach { role ->
                    finishRole(role, abandonedWrite(role, message))
                }
                return
            }
        val installed = DefaultAppsCatalogCache.installedIfCached()
        targets.forEach { (role, packageName) ->
            // The car's own application is what "off" means and is never withheld for not being
            // in a catalog we swept; anything else has to still be there to be worth writing.
            val available = packageName == role.stockPackageName ||
                packageName in launchable
            val completed = if (available) {
                writeRole(context, role, packageName, launchable, installed)
            } else {
                abandonedWrite(
                    role,
                    DefaultAppsWords.GONE,
                )
            }
            finishRole(role, completed)
        }
        if (installed == null) scheduleChoices(context)
    }

    private fun claimSelection(
        context: Context,
        role: DefaultAppRole,
        packageName: String,
    ): Boolean {
        val launchablePackages = runCatching {
            DefaultAppsCatalogCache.launchablePackages(context)
        }.getOrElse { error ->
            failure(DefaultAppsWords.UNREAD, "catalog read", error)
            emptySet()
        }
        return claimSelection(role, packageName, launchablePackages)
    }

    /**
     * One tap on a role's grid, claimed over [launchablePackages] as the car offered them: true
     * when its write may leave. A role whose own write is still in flight refuses the tap and
     * nothing is written - the refresh and the switch keep off it the same way.
     */
    internal fun claimSelection(
        role: DefaultAppRole,
        packageName: String,
        launchablePackages: Set<String>,
    ): Boolean {
        return state.decide { defaults ->
            val roleState = defaults.stateFor(role)
            if (roleState.status == DefaultAppRoleStatus.APPLYING) return@decide Decision.none(false)

            val selectable = DefaultAppsPolicy.isSelectable(packageName, launchablePackages)
            val updatedRole = if (selectable) {
                // The mark moves with the finger. It used to wait for the provider to echo the
                // write back, so the tile the driver had just pressed stayed unmarked for as long
                // as the car took to answer, and the old one stayed marked beside it.
                roleState.copy(
                    status = DefaultAppRoleStatus.APPLYING,
                    message = "",
                    pendingPackageName = packageName,
                    choices = roleState.choices.map { choice ->
                        choice.copy(selected = choice.packageName == packageName)
                    },
                )
            } else {
                roleState.copy(
                    status = DefaultAppRoleStatus.ERROR,
                    message = DefaultAppsWords.GONE,
                    pendingPackageName = null,
                )
            }
            Decision(defaults.update(role) { updatedRole }, selectable)
        }
    }

    private fun finishRole(
        role: DefaultAppRole,
        completed: DefaultAppRoleUiState,
    ) {
        state.update { defaults -> defaults.update(role) { completed } }
    }

    private fun publishUnavailable(message: String) {
        state.update { defaults ->
            defaults.copy(
                refreshing = false,
                roles = defaults.roles.map { roleState ->
                    if (roleState.status == DefaultAppRoleStatus.APPLYING) {
                        roleState
                    } else {
                        roleState.copy(
                            status = DefaultAppRoleStatus.ERROR,
                            message = message,
                            providerConfirmed = false,
                        )
                    }
                },
            )
        }
    }

    private fun roleRepository(context: Context): DefaultAppRoleRepository {
        roleRepository?.let { return it }
        return synchronized(roleRepositoryLock) {
            roleRepository ?: DefaultAppRoleRepository(context).also {
                roleRepository = it
            }
        }
    }

    /**
     * What went wrong, in the panel's words - and only in the panel's words.
     *
     * This used to glue the exception's own message, or failing that its class name, onto the
     * prefix and cut the result at three hundred characters. Three hundred characters is four or
     * five lines of the settings panel, written by a content provider, in whatever language and
     * whatever register that provider happens to use - "android.os.DeadObjectException", or a
     * sentence about a cursor. None of it is actionable from a driver's seat, all of it moves
     * everything below it down the panel, and the last thing it does is convince the reader the
     * app is broken in a way they are expected to understand.
     *
     * The row says which way it fell short ([DefaultAppsWords]); the step and the exception go to
     * logcat, which is where the person who can act on them is looking.
     */
    private fun failure(words: String, step: String, error: Throwable): String {
        Log.w(TAG, "default applications: $step failed", error)
        return words
    }

    private companion object {
        /** The repository's tag, which these lines carried before they moved: filters keep working. */
        const val TAG = "DenzaApps.Repository"
    }
}
