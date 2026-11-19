package dev.denza.apps.feature.vehicle

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.platform.shell.ShellProxyClasspath
import dev.denza.apps.platform.shell.ShellProxyJar
import dev.denza.apps.platform.shell.ShellProxyStager
import dev.denza.apps.platform.shell.classpathAssignment
import dev.denza.disharebridge.LocalAdbClient
import java.security.SecureRandom

/**
 * One way of asking the car for a batch. The answer is the text the shell batch prints - an
 * `@@<index>` line per call and its `Result: Parcel(...)` - whoever produced it, so
 * [AutoserviceShell.parse] reads every reader alike. A failure throws, and the hub closes the reader.
 */
internal interface VehicleReader : AutoCloseable {
    /** Whether this is the resident helper, which [VehicleReaderChoice] is told about at its end. */
    val resident: Boolean

    /** Whether any batch came back: a helper that never answered is one that does not work here. */
    val answered: Boolean

    fun read(batch: List<VehicleSignal>, timeoutMs: Int): String

    override fun close()
}

/** The batch as one chained `service call` command on the persistent shell: a process per value. */
internal class ShellVehicleReader(private val shell: LocalAdbClient.PersistentShellSession) : VehicleReader {
    override val resident = false
    override var answered = false
        private set

    override fun read(batch: List<VehicleSignal>, timeoutMs: Int): String =
        shell.shell(AutoserviceShell.command(batch), timeoutMs).also { answered = true }

    override fun close() = shell.close()
}

/** The batch as one line to [VehicleReadMain], which asks `autoservice` over its own binder. */
internal class ResidentVehicleReader(private val session: LocalAdbClient.ResidentSession) : VehicleReader {
    override val resident = true
    override var answered = false
        private set

    override fun read(batch: List<VehicleSignal>, timeoutMs: Int): String =
        session.request(VehicleReaders.request(batch), timeoutMs).also { answered = true }

    override fun close() = session.close()
}

/**
 * Which reader the hub opens next.
 *
 * The resident helper, unless the last one ended without answering a single batch: it may not run
 * on this car at all - a class it cannot load, a binder it is refused - and the shell batch is what
 * the panel always had, so the next reader is the shell's. That shell reader lives until it fails
 * in turn, which on a working car is a sleep or a lost link, and the helper is tried again after
 * it. A helper that answered and was then lost met the same thing the shell would have, and is
 * opened again. Called from the poll loop's thread only.
 *
 * A shell reader on a working car can live for hours, and one helper start that failed for a
 * passing reason - the first after a boot, a slow staging - kept the shell's 83 processes a second
 * all that time. So the shell reader is also given up [retryDelayMs] after the failed start, while
 * it still answers, for one more helper start: five minutes after the first failure, then twice as
 * long after each failure in a row, up to an hour, so a car where the helper never comes up pays
 * its start's wait rarely. A helper that answers clears the count. [clock] is
 * `SystemClock.elapsedRealtime` on the car.
 */
internal class VehicleReaderChoice(private val clock: () -> Long) {
    var preferResident = true
        private set

    /** Helper starts in a row that ended without answering a batch. */
    var failedStarts = 0
        private set

    private var retryAt = 0L

    fun ended(resident: Boolean, answered: Boolean) {
        when {
            !resident -> preferResident = true
            answered -> {
                preferResident = true
                failedStarts = 0
            }
            else -> {
                preferResident = false
                failedStarts += 1
                retryAt = clock() + retryDelayMs(failedStarts)
            }
        }
    }

    /** Whether a shell reader opened because a helper failed has lived long enough to try one again. */
    fun retryDue(): Boolean = !preferResident && clock() >= retryAt

    companion object {
        const val RETRY_FIRST_MS = 5 * 60_000L
        const val RETRY_MAX_MS = 60 * 60_000L

        fun retryDelayMs(failedStarts: Int): Long =
            (RETRY_FIRST_MS shl (failedStarts - 1).coerceIn(0, 8)).coerceAtMost(RETRY_MAX_MS)
    }
}

/**
 * Opens, swaps and closes readers as [VehicleReaderChoice] says; the readers themselves are handed
 * in, so the whole of it runs on the JVM. [startResident] closes what it opened and throws when the
 * helper does not come up.
 */
internal class VehicleReaderSwitch(
    private val choice: VehicleReaderChoice,
    private val startResident: () -> VehicleReader,
    private val openShell: () -> VehicleReader,
    private val log: (String) -> Unit = {},
    private val warn: (String) -> Unit = {},
) {
    /**
     * The next reader. A helper that does not come up is counted as one that never answered, and
     * the failure goes to the hub like any failed read: the hub's backoff, then the shell's reader.
     */
    fun open(): VehicleReader = if (choice.preferResident) start() else openShell()

    /**
     * [reader], or a helper in its place when [VehicleReaderChoice.retryDue]. The shell reader is
     * closed before the helper starts, so there is never a second reader, and a helper that does
     * not come up this time hands the read straight to a new shell reader: the try costs the poll
     * loop the helper's start, as any helper start does, and no dropped read or backoff after it.
     */
    fun current(reader: VehicleReader): VehicleReader {
        if (reader.resident || !choice.retryDue()) return reader
        log("Trying the resident reader again after ${choice.failedStarts} failed start(s)")
        ended(reader)
        return try {
            start()
        } catch (error: Exception) {
            log("The resident reader did not come up again (${error.javaClass.simpleName}); " +
                "next try in ${VehicleReaderChoice.retryDelayMs(choice.failedStarts) / 60_000} min")
            openShell()
        }
    }

    /** Closes [reader] and lets the next [open] know how it went. */
    fun ended(reader: VehicleReader) {
        reader.runCatching { close() }
        record(reader.resident, reader.answered)
    }

    private fun start(): VehicleReader = try {
        startResident()
    } catch (error: Exception) {
        record(resident = true, answered = false)
        throw error
    }

    private fun record(resident: Boolean, answered: Boolean) {
        choice.ended(resident, answered)
        if (resident && !answered) {
            warn("Помощник чтения не ответил ни разу, следующее чтение - через оболочку")
        }
    }
}

/** The readers of the main process, on the app's own local ADB and the packed helper jar. */
internal class VehicleReaders(context: Context) {
    private val app = context.applicationContext
    private val client get() = DenzaLocalAdb.client(app)
    private val switch = VehicleReaderSwitch(
        VehicleReaderChoice(SystemClock::elapsedRealtime),
        startResident = ::startResident,
        openShell = { ShellVehicleReader(client.openPersistentShell()) },
        log = { Log.i(TAG, it) },
        warn = { Log.w(TAG, it) },
    )

    /**
     * The jar is staged once per process on a shell of its own; a car that will not take it starts
     * the helper from the APK instead, a second or so once per reader ([ShellProxyClasspath]).
     */
    private val classpath = ShellProxyClasspath(
        ShellProxyStager(
            helper = ShellProxyJar.VEHICLE_READ,
            jar = { app.assets.open(ShellProxyJar.VEHICLE_READ.asset).use { it.readBytes() } },
            log = { Log.i(TAG, it) },
        ),
        apkPath = app.applicationInfo.sourceDir,
        log = { Log.i(TAG, it) },
    )

    /** The next reader ([VehicleReaderSwitch.open]). */
    fun open(): VehicleReader = switch.open()

    /** [reader], or the helper retried in its place ([VehicleReaderSwitch.current]). */
    fun current(reader: VehicleReader): VehicleReader = switch.current(reader)

    /** Closes [reader] and lets the next [open] know how it went. */
    fun ended(reader: VehicleReader) = switch.ended(reader)

    /** A helper that answers start; one that does not is closed, and the error thrown. */
    private fun startResident(): VehicleReader {
        val id = nonce()
        val session = client.openResidentSession(id)
        val reader = ResidentVehicleReader(session)
        try {
            // The shell connects at its first command, and a jar this process has staged needs none.
            val entry = client.openPersistentShell().use { shell -> classpath.entry(shell::shell) }
            session.start(launchCommand(entry, app.applicationInfo.sourceDir, id), READY_TIMEOUT_MS)
        } catch (error: Exception) {
            classpath.forget()
            reader.runCatching { close() }
            throw error
        }
        Log.i(TAG, "Чтение машины через резидентного помощника")
        return reader
    }

    internal companion object {
        private const val TAG = "DenzaVehicleReaders"

        /** An `app_process` start, from the APK at worst, with room. */
        const val READY_TIMEOUT_MS = 10_000

        /** `r <transact>:<device>:<fid> ...`, in the batch's order: the helper answers by index. */
        fun request(batch: List<VehicleSignal>): String =
            "r " + batch.joinToString(" ") { "${it.transact.code}:${it.device}:${it.fid}" }

        fun launchCommand(entry: String, apk: String, nonce: String): String =
            "${classpathAssignment(entry, apk)} exec app_process /system/bin --nice-name=denza_vehicle_read " +
                "${VehicleReadMain::class.java.name} serve $nonce"

        private val random = SecureRandom()

        private fun nonce(): String =
            ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
