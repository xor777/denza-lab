package dev.denza.apps.feature.alice

import dev.denza.apps.platform.accessibility.AccessibilityKeyEvent

/** The physical 320 press, independent of Android windows and the service's looper. */
internal class AliceVoiceKeyController(
    private val clock: () -> Long,
    private val visibleNavigator: () -> Int?,
    private val post: (() -> Unit) -> Unit,
    private val clickIfVisible: (Int) -> Boolean,
    private val report: (String) -> Unit,
    private val postDelayed: (Long, () -> Unit) -> Unit = { _, _ -> },
    private val revealIfVisible: (Int, (Boolean) -> Unit) -> Boolean = { _, _ -> false },
) {
    private data class Press(val device: Int, val downTime: Long, val window: Int, val mayClick: Boolean)
    private class Click(val window: Int, val queuedAt: Long) {
        var revealed = false
        var deadline = Long.MAX_VALUE
    }
    private var press: Press? = null
    private var pending: Click? = null
    private var closed = false

    fun onKeyEvent(event: AccessibilityKeyEvent): Boolean {
        if (closed || event.keyCode != KEYCODE_VOICE) return false
        val start = clock()
        val held = press
        val consumed = if (held != null) {
            if (held.device != event.deviceId || held.downTime != event.downTime) {
                false
            } else if (event.action == UP) {
                press = null
                if (!event.canceled && held.mayClick && fresh(event, start)) queueClick(held.window)
                true
            } else {
                event.action == DOWN
            }
        } else if (event.action == DOWN && event.repeatCount == 0 && !event.canceled && fresh(event, start)) {
            val window = runCatching(visibleNavigator).getOrNull()
            if (window == null) false else {
                press = Press(event.deviceId, event.downTime, window, pending == null)
                true
            }
        } else false
        // Diagnostics and scheduling may fail; an owned UP must still stay away from BYD.
        note("key action=${event.action} consumed=$consumed callbackMs=${clock() - start}")
        return consumed
    }

    private fun queueClick(window: Int) {
        val click = Click(window, clock())
        pending = click
        try {
            post {
                if (!closed && pending === click) {
                    if (clock() - click.queuedAt > MAX_DELAY_MS) finish(click, false)
                    else attempt(click)
                }
            }
        } catch (_: Exception) {
            if (pending === click) pending = null
            note("click canceled=looper-unavailable")
        }
    }

    private fun attempt(click: Click) {
        if (closed || pending !== click) return
        try {
            if (clock() >= click.deadline || visibleNavigator() != click.window) {
                finish(click, false)
            } else if (clickIfVisible(click.window)) {
                finish(click, true)
            } else if (!click.revealed) {
                click.revealed = true
                click.deadline = clock() + REVEAL_TIMEOUT_MS
                val accepted = revealIfVisible(click.window) { completed ->
                    if (!closed && pending === click) {
                        if (completed) retry(click) else finish(click, false)
                    }
                }
                if (!accepted) finish(click, false)
                else postDelayed(REVEAL_TIMEOUT_MS) { finish(click, false) }
            } else retry(click)
        } catch (error: Exception) {
            note("click failed=${error.javaClass.simpleName}")
            finish(click, false)
        }
    }

    private fun retry(click: Click) {
        try { postDelayed(RETRY_MS) { attempt(click) } }
        catch (_: Exception) { finish(click, false) }
    }

    private fun finish(click: Click, accepted: Boolean) {
        if (pending !== click) return
        pending = null
        note("click accepted=$accepted window=${click.window}")
    }

    /** Losing the visible Navigator cancels pending work; changing focus between panes does not. */
    fun onWindowChanged() {
        pending?.let { if (runCatching(visibleNavigator).getOrNull() != it.window) finish(it, false) }
    }

    fun close() {
        closed = true
        press = null
        pending = null
    }

    private fun fresh(event: AccessibilityKeyEvent, now: Long): Boolean =
        event.eventTime == 0L || now - event.eventTime in 0..MAX_DELAY_MS

    private fun note(message: String) { runCatching { report(message) } }

    companion object {
        const val KEYCODE_VOICE = 320
        // Firmware KeyEventDispatcher gives filters 500 ms. Never launch from an already stale key.
        private const val MAX_DELAY_MS = 350L
        private const val REVEAL_TIMEOUT_MS = 1_500L
        private const val RETRY_MS = 50L
        private const val DOWN = 0
        private const val UP = 1
    }
}
