package dev.denza.apps.feature.vehicle

import android.content.Context
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
 */
internal class VehicleReaderChoice {
    var preferResident = true
        private set

    fun ended(resident: Boolean, answered: Boolean) {
        preferResident = !resident || answered
    }
}

/** The readers of the main process, on the app's own local ADB and the packed helper jar. */
internal class VehicleReaders(context: Context) {
    private val app = context.applicationContext
    private val client get() = DenzaLocalAdb.client(app)
    private val choice = VehicleReaderChoice()

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

    /**
     * The next reader. A helper that does not come up is closed and counted as one that never
     * answered, and the failure goes to the hub like any failed read: the hub's backoff, then the
     * shell's reader.
     */
    fun open(): VehicleReader {
        if (!choice.preferResident) return ShellVehicleReader(client.openPersistentShell())
        val id = nonce()
        val session = client.openResidentSession(id)
        val reader = ResidentVehicleReader(session)
        try {
            // The shell connects at its first command, and a jar this process has staged needs none.
            val entry = client.openPersistentShell().use { shell -> classpath.entry(shell::shell) }
            session.start(launchCommand(entry, app.applicationInfo.sourceDir, id), READY_TIMEOUT_MS)
        } catch (error: Exception) {
            classpath.forget()
            ended(reader)
            throw error
        }
        Log.i(TAG, "Чтение машины через резидентного помощника")
        return reader
    }

    /** Closes [reader] and lets the next [open] know how it went. */
    fun ended(reader: VehicleReader) {
        reader.runCatching { close() }
        choice.ended(reader.resident, reader.answered)
        if (reader.resident && !choice.preferResident) {
            Log.w(TAG, "Помощник чтения не ответил ни разу, следующее чтение - через оболочку")
        }
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
