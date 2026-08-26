package dev.denza.apps.feature.adb

/**
 * What the blocking gate says about itself, and the way through it to the service screen.
 *
 * The gate is the one screen the owner sees when nothing else works, and until now it was also the
 * screen with the least on it: a title, a sentence, and a button that retries the thing that just
 * failed. Everything that could explain the state - what this channel is, why the app cannot open it
 * itself, and every reading support would ask for - sat behind the gate rather than on it. The
 * channel of truth was unreachable exactly when something was wrong.
 *
 * The copy is deliberately one text for every state of the gate. A car whose switch is off and a car
 * whose key is untrusted are different problems with the same answer for the owner - the channel is
 * opened at the car, by someone with the equipment - and writing that twice would be two records of
 * one fact. What differs between the two states is a cause, and a cause belongs on the gate itself
 * next to the state it explains (see [AdbStartupOverlayModel.details]), not in here.
 *
 * None of it is an apology. The state is lawful: the app is honestly powerless without a person
 * doing something at the car, and saying so plainly is not an error message.
 */
object AdbExplainer {

    /** The door, as it is labelled on the gate. */
    const val OPEN_LABEL = "Что такое ADB"

    const val TITLE = "Что такое ADB"

    const val WHAT_IT_IS_TITLE = "Что это"

    const val WHAT_IT_IS =
        "Служебный канал Android, через который приложение отдаёт команды машине: " +
            "разделить экран, включить зеркала, отправить изображение на приборку. " +
            "Без него приложение видит машину, но не может ей ничего сказать."

    const val HOW_TO_OPEN_TITLE = "Как открыть"

    const val HOW_TO_OPEN =
        "Канал открывается на уровне самой машины — из приложения это сделать нельзя. " +
            "Нужен сервис с диагностическим компьютером."

    /** Seven taps on the title, each within [SERVICE_TAP_WINDOW_MS] of the one before it. */
    const val SERVICE_TAPS = 7

    const val SERVICE_TAP_WINDOW_MS = 3_000L
}

/**
 * The taps that open the service screen from inside the explainer.
 *
 * There used to be a gesture like this on the dashboard - seven taps on an undisclosed part of the
 * screen - and it was removed for a good reason: a tap that missed the secret door landed on a tile,
 * and an odd number of them switched a feature off in silence. That argument is about *where* the
 * gesture lives, not about the gesture. Here it lives on a title inside a window that has no other
 * controls at all, so a tap that is not the seventh does nothing, to anything.
 *
 * It is a plain object rather than Compose state on purpose: the count is never drawn, so nothing
 * about it should cause a recomposition, and a counter with no Android in it can be tested.
 */
class ServiceEntryTaps(
    private val required: Int = AdbExplainer.SERVICE_TAPS,
    private val windowMillis: Long = AdbExplainer.SERVICE_TAP_WINDOW_MS,
) {
    private var count = 0
    private var lastAtMillis = 0L

    /**
     * Records one tap. True exactly on the tap that opens the door, and the run starts over from
     * there, so holding a finger down on the title does not open it again and again.
     */
    fun tap(nowMillis: Long): Boolean {
        // A fresh gate needs no guard of its own: its count is zero, and zero plus one is the same
        // one that starting over gives. Writing `count > 0 &&` here reads as a case being handled
        // and is a branch no input can tell apart - which is worse than not writing it.
        count = if (nowMillis - lastAtMillis <= windowMillis) count + 1 else 1
        lastAtMillis = nowMillis
        if (count < required) return false
        count = 0
        return true
    }
}
