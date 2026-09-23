package dev.denza.apps.feature.split

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * The firmware's own word on Home and on the split area, heard in this process (findings,
 * "The three calls, live from an app UID").
 *
 * Two sources, both proven on the car from an ordinary app UID with no permission:
 * `CLOSE_SYSTEM_DIALOGS` with `reason=homekey`, which arrives nine milliseconds after the key, and
 * the area push of `android.app.UnionActivityManager`, which the firmware sends on every change of
 * the value `activity_task 30` reads, a tenth of a second after Home. The class is BYD's own client,
 * compiled into `framework.jar` and flagged like the public SDK, so it is reached by plain
 * reflection; the listener is an interface of that class, implemented with a [Proxy].
 *
 * Both arrive on one thread of their own and are handed to the coordinator as they are. The
 * firmware keeps one callback per process and `UnionActivityManager` multiplexes over it, so this
 * is the only place in the process that registers one.
 */
internal class SplitFirmwareSignals(
    private val app: Context,
    private val log: (String) -> Unit,
) {
    private val lock = Any()
    private var thread: HandlerThread? = null
    private var areaListener: Any? = null
    private var homeReceiver: BroadcastReceiver? = null

    /** Registers both sources, once. Whatever fails is logged and simply not heard. */
    fun arm(onHomeKey: () -> Unit, onArea: (Int) -> Unit) {
        synchronized(lock) {
            if (thread != null) return
            val signals = HandlerThread("split-signals").apply { start() }
            thread = signals
            val handler = Handler(signals.looper)
            homeReceiver = runCatching { registerHomeKey(handler, onHomeKey) }
                .onFailure { error -> log("split signals: homekey не слышен: $error") }
                .getOrNull()
            areaListener = runCatching { registerArea(handler, onArea) }
                .onFailure { error -> log("split signals: area push не слышен: $error") }
                .getOrNull()
            log(
                "split signals armed: homekey=${homeReceiver != null} " +
                    "area=${areaListener != null}",
            )
        }
    }

    /** The toggle went off (U4): nothing of ours listens to the car any more. */
    fun disarm() {
        synchronized(lock) {
            val signals = thread ?: return
            homeReceiver?.let { receiver -> runCatching { app.unregisterReceiver(receiver) } }
            areaListener?.let { listener ->
                runCatching {
                    union().javaClass
                        .getMethod("unregisterScreenAreaInfoForMultiListener", listenerType())
                        .invoke(union(), listener)
                }
            }
            homeReceiver = null
            areaListener = null
            thread = null
            signals.quitSafely()
            log("split signals disarmed")
        }
    }

    /** One in-process `getScreenAreaInfoForMulti`, 0.4 ms on the car; `null` when unreadable. */
    fun readArea(): Int? = runCatching {
        union().javaClass.getMethod("getScreenAreaInfoForMulti").invoke(union()) as Int
    }.getOrNull()

    private fun registerHomeKey(handler: Handler, onHomeKey: () -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra(EXTRA_REASON) == REASON_HOME_KEY) onHomeKey()
            }
        }
        app.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS),
            null,
            handler,
            Context.RECEIVER_EXPORTED,
        )
        return receiver
    }

    private fun registerArea(handler: Handler, onArea: (Int) -> Unit): Any {
        val type = listenerType()
        val invocation = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "onScreenAreaInfoForMultiChanged" -> {
                    (args?.firstOrNull() as? Int)?.let(onArea)
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "SplitFirmwareSignals.area"
                else -> null
            }
        }
        val listener = Proxy.newProxyInstance(type.classLoader, arrayOf(type), invocation)
        val registered = union().javaClass
            .getMethod("registerScreenAreaInfoForMultiListener", type, Handler::class.java)
            .invoke(union(), listener, handler) as Boolean
        check(registered) { "UnionActivityManager отказал в регистрации" }
        return listener
    }

    private fun union(): Any = Class.forName(UNION_ACTIVITY_MANAGER)
        .getMethod("getInstance", Context::class.java)
        .invoke(null, app)
        ?: error("UnionActivityManager недоступен")

    private fun listenerType(): Class<*> = Class.forName(AREA_LISTENER)

    private companion object {
        const val UNION_ACTIVITY_MANAGER = "android.app.UnionActivityManager"
        const val AREA_LISTENER = "$UNION_ACTIVITY_MANAGER\$ScreenAreaInfoForMultiListener"
        const val EXTRA_REASON = "reason"
        const val REASON_HOME_KEY = "homekey"
    }
}

/**
 * tx126 `setStartToSplit` on the `activity_task` binder, from this process.
 *
 * `IActivityTaskManager`'s proxy is closed to apps, but the binder itself is not:
 * `ServiceManager.getService` is on the greylist and the firmware's implementation checks no
 * permission (findings 2026-09-23). It is the same transaction `service call activity_task 126`
 * sends, with the interface token read from the binder exactly as `service call` reads it.
 */
internal object BinderSplitGateSwitch : SplitGateSwitch {
    private const val SET_START_TO_SPLIT = 126

    override fun set(open: Boolean) {
        val service = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "activity_task") as IBinder?
            ?: error("no activity_task service")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(checkNotNull(service.interfaceDescriptor))
            data.writeInt(if (open) 1 else 0)
            check(service.transact(SET_START_TO_SPLIT, data, reply, 0)) { "tx126 не принята" }
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
