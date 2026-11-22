package dev.denza.apps.feature.split.pill

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import dev.denza.apps.R
import dev.denza.apps.core.AppText
import dev.denza.apps.feature.split.SplitPair
import dev.denza.apps.feature.split.SplitPane
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Where a breath of [cycleMs] stands at [nowMs], 0 at its quietest and 1 at its brightest: up over
 * the first half of the cycle, down over the second (README, "Motion": `breathe` is one cycle).
 */
internal fun breathePhase(nowMs: Double, cycleMs: Double): Double {
    val phase = (nowMs % cycleMs) / cycleMs
    return if (phase <= 0.5) phase * 2 else 2 - phase * 2
}

/**
 * How many panes [d] has empty, read from its home row: none; one - the repair row, which carries
 * neither «+» nor the divider's mark; or both - the pairs (with «+») or the apps (with the mark).
 */
internal fun pillVacancy(d: SplitPillDescription?): Int = when {
    d == null || !d.vacant -> 0
    PillItem.Plus in d.home || PillItem.DividerMark in d.home -> 2
    else -> 1
}

/** What a tap on the pill asks for; every one of them is an operation of the split's actor. */
internal interface SplitPillActions {
    fun choosePair(pair: SplitPair)
    fun selectIntoPane(pane: SplitPane, packageName: String)
    fun setPinned(pair: SplitPair, pinned: Boolean)

    /** The touchable region, px bands in window coordinates; set at a motion's start and end. */
    fun touchRegion(bands: List<IntArray>)

    /** The pill has faded out after its scene went: the window may go. */
    fun gone()
}

/**
 * The handle and the pill: one view filling the pill's fixed window ([PillGeometry.window]), drawn
 * in stage dp, moved by the page's own springs and tweens ([PillValue]), from the pure
 * [SplitPillDescription] alone. It owns nothing but its motion and its timers (decision 5, «К
 * 1.15»): what it shows comes from [setDescription], what a tap means goes out through
 * [SplitPillActions].
 *
 * Nothing here allocates, lays out or blurs on a frame: icons, contours, badges, the mark, the
 * shadow and the bloom are bitmaps of [SplitPillArt]; the glass, sheen, rim and bead are one
 * rounded rectangle each. A row is laid out once, when it changes.
 */
@SuppressLint("ViewConstructor")
internal class SplitPillView(
    context: Context,
    private val scale: Double,
    private val actions: SplitPillActions,
) : View(context) {

    private val art = SplitPillArt(context, scale) { kick() }.also(SplitPillArt::prepare)
    private val handler = Handler(Looper.getMainLooper())
    private val window = PillGeometry.window
    private val reduced: Boolean get() = !ValueAnimator.areAnimatorsEnabled()

    // region state

    private enum class Mode { HANDLE, OPEN }
    private enum class Content { HOME, APPS }

    private var desc: SplitPillDescription? = null
    private var divX = SplitPillSpec.Stage.W / 2
    private var mode = Mode.HANDLE
    private var content = Content.HOME
    private var byHand = false
    private var visible = false

    /** Removed with its window: nothing may schedule it again ([dispose]). */
    private var disposed = false

    /** Counts descriptions; a gesture remembers the one it began under. */
    private var descSeq = 0

    /** A description arrived while the row was held under a finger: reconcile when it settles. */
    private var stale = false

    /** Both panes filled while the row was held: close once the pull settles. */
    private var closeAfterPull = false

    /** The row as laid out now, and every item drawn - leaving ones included - in drawing order. */
    private var layout = PillGeometry.layout(emptyList())
    private val items = ArrayList<ItemAnim>()

    private val w = PillValue(SplitPillSpec.Handle.W, 0.05)
    private val h = PillValue(SplitPillSpec.Handle.H, 0.05)
    private val o = PillValue(0.0, 0.002)
    private val rowA = PillValue(0.0, 0.002)
    private val hA = PillValue(SplitPillSpec.Handle.ALPHA, 0.002)
    private val dx = PillValue(0.0, 0.05)
    private val sw = PillValue(0.0, 0.002)
    private val shown = PillValue(0.0, 0.002)

    private val values = listOf(w, h, o, rowA, hA, dx, sw, shown)

    private inner class ItemAnim(var item: PillItem) {
        val x = PillValue(0.0, 0.05)
        val a = PillValue(1.0, 0.002)
        val dy = PillValue(0.0, 0.02)
        val s = PillValue(1.0, 0.0005)
        val press = PillValue(1.0, 0.0005)
        val t = PillValue(0.0, 0.002)
        val gl = PillValue(0.0, 0.002)
        val gr = PillValue(0.0, 0.002)
        val markA = PillValue(0.0, 0.002)
        val badgeA = PillValue(0.0, 0.002)
        val badgeS = PillValue(1.0, 0.0005)
        val tx = PillValue(0.0, 0.02)
        val sy = PillValue(1.0, 0.0005)
        var badge: PillBadge? = null
        var flashAt = Double.NaN
        var leaving = false
        val all = listOf(x, a, dy, s, press, t, gl, gr, markA, badgeA, badgeS, tx, sy)
    }

    // the pull (README, "Pull", "Catch")
    private enum class Hold { HANDLE, CATCH, DOWN }

    private var hold: Hold? = null
    private var pointerId = -1
    private var bound = false
    private var releasing = false
    private var x0 = 0.0
    private var y0 = 0.0
    private var dist = 0.0
    private var raw = 0.0
    private var p0 = 0.0
    private var floor = 0.0
    private var give = false
    private var pullW = SplitPillSpec.Handle.W
    private var anchorQ = Double.NaN
    private var anchorW = 0.0
    private var resH = 0.0
    private var resRow = 0.0
    private var resScale = 0.0
    private var resItems = DoubleArray(0)
    private var resDy = DoubleArray(0)
    private var resS = DoubleArray(0)
    private var wasOpening = false
    private var backAfterPull = false
    private var heldAt = 0.0
    private var onHit = false
    private val pr = PillValue(0.0, 0.002)
    private val sampleT = DoubleArray(SAMPLES)
    private val sampleY = DoubleArray(SAMPLES)
    private var samples = 0

    // a touch on the open pill: the target taken at the down, and what it meant then
    private var pressed: ItemAnim? = null
    private var pressedBadge: ItemAnim? = null
    private var pressedItem: PillItem? = null
    private var glassTouch = false
    private var moved = false

    // the blooms of the last few presses
    private val bloomX = DoubleArray(BLOOMS)
    private val bloomY = DoubleArray(BLOOMS)
    private val bloomT = DoubleArray(BLOOMS) { Double.NaN }
    private var bloomNext = 0

    // endregion

    // region what the core says

    /**
     * A new description: the pill follows it, opening by itself on a pane left empty (1.15.5). The
     * row follows the scene even while the pill is open: a pane that has just gone empty - or the
     * first of two empty panes filled - brings the repair row up, from the apps row too. A row held
     * under a finger takes the new description when the pull settles. A publication is not input:
     * it neither re-arms the 5 s close nor wakes the handle (1.15.3).
     */
    fun setDescription(next: SplitPillDescription?) {
        if (disposed) return
        if (next == null) {
            desc = null
            hide()
            return
        }
        val before = desc
        desc = next
        descSeq++
        divX = next.dividerX
        if (!visible) show()
        val was = pillVacancy(before)
        val now = pillVacancy(next)
        if (now == 1 && was != 1) content = Content.HOME
        if (bound) stale = true else if (mode == Mode.OPEN) reconcile(row())
        if (now > 0 && was == 0 && mode == Mode.HANDLE) {
            content = Content.HOME
            later(Timer.SELF_OPEN, SplitPillSpec.Timer.SELF_OPEN) { if (desc?.vacant == true) openPill(byHand = false) }
        }
        if (now == 0) cancel(Timer.SELF_OPEN)
        // While a pane is empty the pill stays open: nothing closes it by time.
        if (now > 0) cancel(Timer.AUTO)
        // Both panes are full again: the pill that stood open over the empty pane melts away.
        if (now == 0 && was > 0 && mode == Mode.OPEN) {
            if (bound) closeAfterPull = true else closePill()
        }
        updateRegion()
        kick()
    }

    /**
     * The dock button's open over a full pair (1.15.2, 1.15.10): after [delayMs] the pill opens as
     * a tap on the handle would, and melts away 5 s later. A pane left empty has opened it already.
     */
    fun requestOpen(delayMs: Double) {
        if (disposed) return
        later(Timer.REQUEST_OPEN, delayMs) {
            val d = desc
            if (visible && d != null && !d.vacant && hold == null) openPill(byHand = true)
        }
    }

    /** The window is going: no timer, frame or icon may bring this view back. */
    fun dispose() {
        disposed = true
        timers.values.forEach(handler::removeCallbacks)
        timers.clear()
        Choreographer.getInstance().removeFrameCallback(frame)
        looping = false
    }

    private fun row(): List<PillItem> {
        val d = desc ?: return emptyList()
        return if (content == Content.APPS) d.apps else d.home
    }

    /** Whether [item] - as it was when a finger took it - still means the same in the row now. */
    private fun stillMeans(item: PillItem?): Boolean = item != null && row().contains(item)

    private fun show() {
        visible = true
        cancel(Timer.HOME_RESET)
        resetToHandle()
        tween(shown, 1.0, SplitPillSpec.Motion.SHOW)
        startDim()
        contentDescription = AppText.s(R.string.split_pill_handle)
    }

    private fun hide() {
        if (!visible) return
        visible = false
        cancelTouches()
        // The input goes at once; only the picture fades (1.15.9).
        updateRegion()
        listOf(Timer.AUTO, Timer.DIM, Timer.SELF_OPEN, Timer.CLEAR, Timer.REQUEST_OPEN).forEach(::cancel)
        tween(shown, 0.0, SplitPillSpec.Motion.HIDE)
        later(Timer.HOME_RESET, SplitPillSpec.Timer.HOME_RESET) {
            resetToHandle()
            actions.gone()
        }
        kick()
    }

    private fun resetToHandle() {
        mode = Mode.HANDLE
        content = Content.HOME
        byHand = false
        bound = false
        releasing = false
        stale = false
        closeAfterPull = false
        items.clear()
        layout = PillGeometry.layout(emptyList())
        w.set(SplitPillSpec.Handle.W)
        h.set(SplitPillSpec.Handle.H)
        o.set(0.0)
        rowA.set(0.0)
        dx.set(0.0)
        sw.set(0.0)
        hA.set(SplitPillSpec.Handle.ALPHA)
        pr.set(0.0)
    }

    // endregion

    // region the row

    /**
     * Brings the row to [next]: kept items glide, new ones come in, gone ones leave (the page's
     * `reconcile`). To or from the apps row the rows do not cross: the outgoing items leave first
     * (`swapOut`), the divider's mark grows from its centre (`swapMark`), and the incoming items
     * arrive from the divider outward (`swapIn`), nearest first.
     */
    private fun reconcile(next: List<PillItem>, fresh: Boolean = false, pull: Boolean = false, openSpring: Boolean = false) {
        val l = PillGeometry.layout(next)
        val keys = next.mapTo(HashSet()) { it.key }
        val hadMark = items.any { !it.leaving && it.item == PillItem.DividerMark }
        val swap = !fresh && !pull && items.any { !it.leaving } && hadMark != (PillItem.DividerMark in next)
        items.forEach { anim ->
            if (anim.item.key !in keys && !anim.leaving) {
                anim.leaving = true
                if (swap) {
                    tween(anim.a, 0.0, SplitPillSpec.Motion.SWAP_OUT)
                    tween(anim.s, SplitPillSpec.Motion.SWAP_OUT_SCALE, SplitPillSpec.Motion.SWAP_OUT)
                } else {
                    tween(anim.a, 0.0, SplitPillSpec.Motion.EXIT)
                    tween(anim.s, SplitPillSpec.Enter.EXIT, SplitPillSpec.Motion.EXIT)
                }
            }
        }
        // Where the divider stands in the new row, and each new item's rank by its distance from it.
        val markAt = next.indexOf(PillItem.DividerMark).let { i -> if (i < 0) 0.0 else l.x[i] + l.widthOf(i) / 2 }
        val away = DoubleArray(next.size) { i -> kotlin.math.round(abs(l.x[i] + l.widthOf(i) / 2 - markAt)) }
        val dists = next.indices
            .filter { i -> next[i] != PillItem.DividerMark && items.none { it.item.key == next[i].key } }
            .map { i -> away[i] }
            .distinct()
            .sorted()
        val ordered = ArrayList<ItemAnim>(next.size)
        next.forEachIndexed { i, item ->
            var anim = items.firstOrNull { it.item.key == item.key }
            val born = anim == null
            if (anim == null) {
                anim = ItemAnim(item)
                anim.x.set(l.x[i])
                anim.badge = (item as? PillItem.Chip)?.badge
                anim.badgeA.set(if (anim.badge != null) 1.0 else 0.0)
                anim.markA.set(if ((item as? PillItem.Chip)?.live == true) 1.0 else 0.0)
                when {
                    pull -> Unit
                    swap && item == PillItem.DividerMark -> {
                        anim.sy.set(0.0)
                        tween(anim.sy, 1.0, SplitPillSpec.Motion.SWAP_MARK)
                    }
                    swap -> {
                        val m = SplitPillSpec.Motion.SWAP_IN
                        val r = if (dists.size > 1) dists.indexOf(away[i]) / (dists.size - 1.0) else 0.0
                        val toward = kotlin.math.sign(markAt - (l.x[i] + l.widthOf(i) / 2)) * SplitPillSpec.Motion.SWAP_IN_FROM
                        val delay = m.delay + r * SplitPillSpec.Motion.SWAP_IN_SPREAD
                        anim.a.set(0.0)
                        anim.tx.set(toward)
                        anim.s.set(SplitPillSpec.Motion.SWAP_IN_SCALE)
                        tween(anim.a, 1.0, m, delay)
                        tween(anim.tx, 0.0, m, delay)
                        tween(anim.s, 1.0, m, delay)
                    }
                    fresh -> {
                        val m = SplitPillSpec.Motion.ENTER
                        anim.a.set(0.0)
                        anim.dy.set(SplitPillSpec.Enter.DY)
                        val delay = m.delay + i * m.stagger
                        tween(anim.a, 1.0, m, delay)
                        tween(anim.dy, 0.0, m, delay)
                    }
                    else -> {
                        anim.a.set(0.0)
                        anim.s.set(SplitPillSpec.Enter.POP)
                        tween(anim.a, 1.0, SplitPillSpec.Motion.POP)
                        tween(anim.s, 1.0, SplitPillSpec.Motion.POP)
                    }
                }
            } else {
                if (anim.leaving) {
                    anim.leaving = false
                    tween(anim.a, 1.0, SplitPillSpec.Motion.POP)
                    tween(anim.s, 1.0, SplitPillSpec.Motion.POP)
                }
                spring(anim.x, l.x[i], SplitPillSpec.Spring.SLIDE)
            }
            update(anim, item, born)
            ordered += anim
        }
        // Leaving items keep drawing where they were until they are gone.
        items.filter { it.leaving }.forEach { ordered += it }
        items.clear()
        items.addAll(ordered)
        layout = l
        if (!pull) {
            val spring = if (fresh || openSpring) SplitPillSpec.Spring.OPEN else SplitPillSpec.Spring.RESIZE
            spring(w, l.w, spring)
            if (dx.goal != l.dx) spring(dx, l.dx, spring)
        }
        updateRegion()
        kick()
    }

    /** A kept item takes its new description: a chip's form, its live mark and its badge. */
    private fun update(anim: ItemAnim, item: PillItem, born: Boolean) {
        anim.item = item
        if (item !is PillItem.Chip) return
        val open = if (item.missing != null) 1.0 else 0.0
        val gl = if (item.missing == Side.LEFT) 1.0 else 0.0
        val gr = if (item.missing == Side.RIGHT) 1.0 else 0.0
        if (born) {
            anim.t.set(open)
            anim.gl.set(gl)
            anim.gr.set(gr)
        } else {
            if (anim.t.goal != open) spring(anim.t, open, SplitPillSpec.Spring.FORM)
            if (anim.gl.goal != gl) tween(anim.gl, gl, if (gl > 0) SplitPillSpec.Motion.GHOST_IN else SplitPillSpec.Motion.GHOST_OUT)
            if (anim.gr.goal != gr) tween(anim.gr, gr, if (gr > 0) SplitPillSpec.Motion.GHOST_IN else SplitPillSpec.Motion.GHOST_OUT)
        }
        val mark = if (item.live) 1.0 else 0.0
        if (anim.markA.goal != mark) tween(anim.markA, mark, SplitPillSpec.Motion.MARK_FADE)
        if (item.badge != anim.badge) {
            val coming = anim.badge == null
            if (item.badge != null) anim.badge = item.badge
            when {
                item.badge == null -> {
                    tween(anim.badgeA, 0.0, SplitPillSpec.Motion.BADGE_OUT)
                    tween(anim.badgeS, 0.0, SplitPillSpec.Motion.BADGE_OUT)
                }
                coming && !born -> {
                    anim.badgeA.set(0.0)
                    anim.badgeS.set(0.0)
                    tween(anim.badgeA, 1.0, SplitPillSpec.Motion.BADGE_IN)
                    tween(anim.badgeS, 1.0, SplitPillSpec.Motion.BADGE_IN)
                }
            }
        }
    }

    /** Opening by itself on an emptied pane: the pair arrives stacked and whole, then opens up. */
    private fun deferForms() {
        items.forEach { anim ->
            val chip = anim.item as? PillItem.Chip ?: return@forEach
            if (chip.missing == null) return@forEach
            anim.t.set(0.0)
            anim.gl.set(0.0)
            anim.gr.set(0.0)
            val delay = SplitPillSpec.Timer.GHOST_OPEN
            spring(anim.t, 1.0, SplitPillSpec.Spring.FORM, delay)
            if (chip.missing == Side.LEFT) tween(anim.gl, 1.0, SplitPillSpec.Motion.GHOST_IN, delay)
            if (chip.missing == Side.RIGHT) tween(anim.gr, 1.0, SplitPillSpec.Motion.GHOST_IN, delay)
        }
    }

    // endregion

    // region open and close

    private fun openPill(byHand: Boolean) {
        if (!visible || desc == null) return
        cancel(Timer.SELF_OPEN)
        if (mode == Mode.OPEN) {
            reconcile(row())
            armAuto()
            return
        }
        val closing = items.isNotEmpty()
        mode = Mode.OPEN
        content = Content.HOME
        this.byHand = byHand
        cancel(Timer.CLEAR)
        cancel(Timer.DIM)
        if (closing) {
            tween(rowA, 1.0, SplitPillSpec.Motion.ROW_IN)
            reconcile(row(), openSpring = true)
        } else {
            items.clear()
            rowA.set(1.0)
            reconcile(row(), fresh = true)
            if (desc?.vacant == true) deferForms()
        }
        spring(h, SplitPillSpec.Pill.OPEN_H, SplitPillSpec.Spring.OPEN_H)
        // The glass is the disc's already: the glass tween shows only from a quiet disc.
        o.set(glassNow())
        tween(o, 1.0, SplitPillSpec.Motion.GLASS_IN)
        tween(hA, SplitPillSpec.Handle.ALPHA, SplitPillSpec.Motion.WAKE)
        contentDescription = AppText.s(R.string.split_pill_open)
        armAuto()
        updateRegion()
        kick()
    }

    private fun closePill() {
        if (mode == Mode.HANDLE) return
        mode = Mode.HANDLE
        content = Content.HOME
        byHand = false
        cancel(Timer.AUTO)
        tween(rowA, 0.0, SplitPillSpec.Motion.ROW_OUT)
        val delay = SplitPillSpec.Motion.SHRINK_DELAY
        spring(w, SplitPillSpec.Handle.W, SplitPillSpec.Spring.CLOSE, delay)
        spring(h, SplitPillSpec.Handle.H, SplitPillSpec.Spring.CLOSE, delay)
        if (dx.goal != 0.0) spring(dx, 0.0, SplitPillSpec.Spring.CLOSE, delay)
        tween(o, 0.0, SplitPillSpec.Motion.GLASS_OUT)
        later(Timer.CLEAR, SplitPillSpec.Timer.CLEAR) {
            if (mode == Mode.HANDLE && hold == null) {
                items.clear()
                layout = PillGeometry.layout(emptyList())
            }
        }
        contentDescription = AppText.s(R.string.split_pill_handle)
        startDim()
        updateRegion()
        kick()
    }

    /** Showing pairs or apps opened by hand, it melts back 5 s after the last touch (1.15.3). */
    /**
     * A touch outside, or a pull down that lets go: with both panes full the pill goes away; while
     * a pane is empty it cannot, and from the apps row it goes back to the row «+» was pressed in.
     */
    private fun away() {
        if (desc?.vacant != true) {
            closePill()
        } else if (content == Content.APPS) {
            content = Content.HOME
            reconcile(row())
        }
    }

    /** The glass as drawn now: whole once open, the disc's quiet level when idle. */
    private fun glassNow(): Double {
        val awake = ((hA.v - SplitPillSpec.Handle.DIM) / (SplitPillSpec.Handle.ALPHA - SplitPillSpec.Handle.DIM)).coerceIn(0.0, 1.0)
        return max(o.v.coerceIn(0.0, 1.0), SplitPillSpec.Handle.DIM_GLASS + (1 - SplitPillSpec.Handle.DIM_GLASS) * awake)
    }

    private fun armAuto() {
        cancel(Timer.AUTO)
        if (mode == Mode.OPEN && byHand && desc?.vacant != true && hold == null) {
            later(Timer.AUTO, SplitPillSpec.Timer.AUTO) { closePill() }
        }
    }

    private fun startDim() {
        cancel(Timer.DIM)
        later(Timer.DIM, SplitPillSpec.Timer.DIM) {
            if (mode == Mode.HANDLE) tween(hA, SplitPillSpec.Handle.DIM, SplitPillSpec.Motion.DIM)
        }
    }

    private fun wake() {
        if (hA.goal < SplitPillSpec.Handle.ALPHA) tween(hA, SplitPillSpec.Handle.ALPHA, SplitPillSpec.Motion.WAKE)
        if (mode == Mode.HANDLE) startDim()
    }

    // endregion

    // region taps

    private fun tapItem(anim: ItemAnim) {
        val d = desc ?: return
        when (val item = anim.item) {
            is PillItem.Chip -> {
                if (item.live) {
                    nudge(anim)
                    armAuto()
                    return
                }
                actions.choosePair(item.pair)
                // Over an empty pane the pill stays until both panes are full (1.15.5).
                if (!d.vacant) closePill()
            }
            is PillItem.Single -> {
                actions.selectIntoPane(item.pane, item.packageName)
                if (!d.vacant) closePill()
            }
            PillItem.Plus -> {
                content = Content.APPS
                reconcile(d.apps)
                armAuto()
            }
            PillItem.Separator, PillItem.DividerMark -> Unit
        }
    }

    /** The star on the pair on screen (1.15.7): pinning flashes it and nudges the chip. */
    private fun tapBadge(anim: ItemAnim) {
        val chip = anim.item as? PillItem.Chip ?: return
        val pin = chip.badge != PillBadge.SOLID
        actions.setPinned(chip.pair, pin)
        if (pin) {
            anim.flashAt = now()
            tween(anim.badgeS, SplitPillSpec.Press.STAR, SplitPillSpec.Motion.NUDGE)
            later(Timer.BADGE_BACK, SplitPillSpec.Motion.NUDGE.ms) { spring(anim.badgeS, 1.0, SplitPillSpec.Spring.RELEASE) }
            later(Timer.CHIP_NUDGE, SplitPillSpec.Timer.CHIP_NUDGE) { nudge(anim) }
        }
        armAuto()
        kick()
    }

    private fun nudge(anim: ItemAnim) {
        tween(anim.press, SplitPillSpec.Press.NUDGE, SplitPillSpec.Motion.NUDGE)
        later(Timer.NUDGE_BACK, SplitPillSpec.Motion.NUDGE.ms) { spring(anim.press, 1.0, SplitPillSpec.Spring.RELEASE) }
        kick()
    }

    // endregion

    // region touch

    private fun toStageX(px: Float): Double = px / scale + window.l
    private fun toStageY(px: Float): Double = px / scale + window.t

    /**
     * One finger at a time: the pointer that went down is tracked, its own up ends the gesture,
     * every other pointer is ignored, and a cancel puts back what the pill was doing before the
     * gesture without acting on it.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!visible) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> {
                // Only the open pill answers a touch elsewhere; the resting handle stays as it is.
                if (mode == Mode.OPEN && hold == null && pressed == null && pressedBadge == null) away()
                return true
            }
            MotionEvent.ACTION_DOWN -> return down(event)
            MotionEvent.ACTION_MOVE -> if (pointerId >= 0) move(event)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val index = event.actionIndex
                if (pointerId >= 0 && event.getPointerId(index) == pointerId) up(event, index)
            }
            MotionEvent.ACTION_CANCEL -> if (pointerId >= 0) cancelGesture()
        }
        return true
    }

    /** The pill moving and not under a finger: a touch anywhere on it catches it (README, "Catch"). */
    private fun inFlight(): Boolean = !bound && (h.active || w.active)

    private fun down(event: MotionEvent): Boolean {
        val x = toStageX(event.x)
        val y = toStageY(event.y)
        pointerId = event.getPointerId(0)
        pressed = null
        pressedBadge = null
        pressedItem = null
        glassTouch = false
        moved = false
        x0 = x
        y0 = y
        dist = 0.0
        heldAt = now()
        val caught = inFlight()
        if (caught) {
            wake()
            bloom(x, y)
            // What is drawn under the finger now, before the catch re-reads the pill.
            val badge = badgeUnder(x, y)
            val anim = if (badge == null) itemUnder(x, y) else null
            onHit = PillGeometry.handleHit(divX).contains(x, y)
            startHold(Hold.CATCH, event)
            seize()
            p0 = raw
            if (badge != null) {
                pressedBadge = badge
                pressedItem = badge.item
                tween(badge.badgeS, SplitPillSpec.Press.BADGE, SplitPillSpec.Motion.BADGE_PRESS)
            } else if (anim != null) {
                pressed = anim
                pressedItem = anim.item
                tween(anim.press, SplitPillSpec.Press.SCALE, SplitPillSpec.Motion.PRESS)
            }
            return true
        }
        if (mode == Mode.HANDLE) {
            if (!PillGeometry.handleHit(divX).contains(x, y)) {
                pointerId = -1
                return false
            }
            wake()
            bloom(x, y)
            startHold(Hold.HANDLE, event)
            p0 = 0.0
            raw = 0.0
            tween(sw, 1.0, SplitPillSpec.Motion.PRESS)
            return true
        }
        // The open pill at rest: a badge, an item, or the glass between them - as drawn now.
        wake()
        armAuto()
        bloom(x, y)
        val badge = badgeUnder(x, y)
        if (badge != null) {
            pressedBadge = badge
            pressedItem = badge.item
            tween(badge.badgeS, SplitPillSpec.Press.BADGE, SplitPillSpec.Motion.BADGE_PRESS)
            return true
        }
        val anim = itemUnder(x, y)
        if (anim != null) {
            pressed = anim
            pressedItem = anim.item
            tween(anim.press, SplitPillSpec.Press.SCALE, SplitPillSpec.Motion.PRESS)
        } else {
            glassTouch = true
        }
        return true
    }

    private fun move(event: MotionEvent) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        if (hold != null) {
            sample(event, index)
            follow(event.getX(index), event.getY(index))
            return
        }
        val x = toStageX(event.getX(index))
        val y = toStageY(event.getY(index))
        val ddx = x - x0
        val ddy = y - y0
        dist = max(dist, hypot(ddx, ddy))
        if (moved) return
        if (dist > SplitPillSpec.Pull.DOWN_START) {
            val down = ddy > SplitPillSpec.Pull.DOWN_DOMINANCE * abs(ddx)
            pressed?.let { spring(it.press, 1.0, SplitPillSpec.Spring.RELEASE) }
            pressedBadge?.let { tween(it.badgeS, 1.0, SplitPillSpec.Motion.BADGE_RELEASE) }
            pressed = null
            pressedBadge = null
            pressedItem = null
            moved = true
            if (down && mode == Mode.OPEN && !inFlight()) {
                startHold(Hold.DOWN, event)
                seize()
                p0 = raw
                sample(event, index)
                follow(event.getX(index), event.getY(index))
            }
        }
        kick()
    }

    private fun up(event: MotionEvent, index: Int) {
        pointerId = -1
        if (hold != null) {
            sample(event, index)
            follow(event.getX(index), event.getY(index))
            letGo(cancelled = false)
            return
        }
        val x = toStageX(event.getX(index))
        val y = toStageY(event.getY(index))
        val near = hypot(x - x0, y - y0) < SplitPillSpec.Press.SLOP
        val meant = pressedItem
        pressedBadge?.let { badge ->
            tween(badge.badgeS, 1.0, SplitPillSpec.Motion.BADGE_RELEASE)
            if (near && !moved && !badge.leaving && stillMeans(meant)) tapBadge(badge)
        }
        pressed?.let { anim ->
            spring(anim.press, 1.0, SplitPillSpec.Spring.RELEASE)
            val held = now() - heldAt
            val quick = anim.item !is PillItem.Chip || held < SplitPillSpec.Timer.TAP
            if (near && !moved && quick && !anim.leaving && stillMeans(meant)) tapItem(anim)
        }
        pressed = null
        pressedBadge = null
        pressedItem = null
        glassTouch = false
        armAuto()
        kick()
    }

    /** A cancelled gesture: what was pressed lets go, the pill goes back to where it was going. */
    private fun cancelGesture() {
        pointerId = -1
        pressedBadge?.let { tween(it.badgeS, 1.0, SplitPillSpec.Motion.BADGE_RELEASE) }
        if (hold != null) {
            letGo(cancelled = true)
            return
        }
        pressed?.let { spring(it.press, 1.0, SplitPillSpec.Spring.RELEASE) }
        pressed = null
        pressedBadge = null
        pressedItem = null
        glassTouch = false
        armAuto()
        kick()
    }

    private fun cancelTouches() {
        pointerId = -1
        hold = null
        pressed = null
        pressedBadge = null
        pressedItem = null
        glassTouch = false
    }

    private fun startHold(kind: Hold, event: MotionEvent) {
        hold = kind
        samples = 0
        sample(event, 0)
        cancel(Timer.AUTO)
        cancel(Timer.DIM)
    }

    private fun sample(event: MotionEvent, index: Int) {
        for (k in 0 until event.historySize) {
            push(event.getHistoricalEventTime(k).toDouble(), toStageY(event.getHistoricalY(index, k)))
        }
        push(event.eventTime.toDouble(), toStageY(event.getY(index)))
    }

    private fun push(t: Double, y: Double) {
        if (samples == SAMPLES) {
            System.arraycopy(sampleT, 1, sampleT, 0, SAMPLES - 1)
            System.arraycopy(sampleY, 1, sampleY, 0, SAMPLES - 1)
            samples--
        }
        sampleT[samples] = t
        sampleY[samples] = y
        samples++
    }

    // endregion

    // region the pull

    private val span get() = SplitPillSpec.Pill.OPEN_H - SplitPillSpec.Handle.H

    // The pull's arithmetic is [PillPull]'s, the one the tests hold to the page.
    private fun pOf(r: Double): Double = PillPull.progress(r, floor, give)

    private fun hOf(q: Double): Double = PillPull.heightOf(q)

    private fun wAt(q: Double): Double = PillPull.widthOf(q, pullW, anchorQ, anchorW)

    private fun rawOf(height: Double): Double = PillPull.rawOfHeight(height)

    /** The first pixel of a pull from the handle: the row laid out, every item still hidden. */
    private fun armHandle() {
        cancel(Timer.CLEAR)
        items.clear()
        content = Content.HOME
        reconcile(row(), pull = true)
        pullW = layout.w
        floor = 0.0
        give = false
        resH = 0.0
        resRow = 0.0
        resScale = 0.0
        resItems = DoubleArray(layout.items.size)
        resDy = DoubleArray(layout.items.size)
        resS = DoubleArray(layout.items.size)
        anchorQ = Double.NaN
        pr.set(0.0)
        o.set(1.0)
        bound = true
    }

    /** The pill as shown becomes the pill under the finger (the page's `seize`). */
    private fun seize() {
        val k0 = ((max(SplitPillSpec.Pill.MIN_H, h.v) - SplitPillSpec.Handle.H) / span).coerceIn(0.0, 1.0)
        val rowNow = rowAlpha()
        val scaleNow = rowScaleNow()
        val shownAlpha = HashMap<String, Double>()
        items.forEach { anim -> if (!anim.leaving) shownAlpha[anim.item.key] = itemAlpha(anim) }
        wasOpening = h.goal == SplitPillSpec.Pill.OPEN_H
        cancel(Timer.CLEAR)
        if (mode == Mode.HANDLE || layout.items.isEmpty()) {
            content = Content.HOME
            reconcile(row(), pull = true)
        }
        pullW = layout.w
        val keep = desc?.vacant == true
        raw = rawOf(h.v)
        floor = if (keep) min(raw, 1.0) else 0.0
        give = keep
        val q = pOf(raw)
        resH = h.v - hOf(q)
        resRow = rowNow - 1
        resScale = scaleNow - PillPull.rowScale(max(SplitPillSpec.Pill.MIN_H, h.v), max(SplitPillSpec.Pill.MIN_W, w.v), pullW)
        anchorQ = q
        anchorW = w.v
        // Each item keeps where it is drawn under the still finger: its lift and its scale go on as
        // residuals that the catch lets out, as its alpha does (README, "Catch").
        val n = layout.items.size
        resDy = DoubleArray(n)
        resS = DoubleArray(n)
        resItems = DoubleArray(n) { i ->
            val key = layout.items[i].key
            val anim = items.firstOrNull { it.item.key == key && !it.leaving }
            anim?.let {
                resDy[i] = it.dy.v - SplitPillSpec.Enter.DY * (1 - PillPull.revealOf(i, n, k0))
                resS[i] = it.s.v - 1
                it.a.set(1.0)
                it.dy.set(0.0)
                it.s.set(1.0)
            }
            (shownAlpha[key] ?: 0.0) - PillPull.revealOf(i, n, k0)
        }
        items.removeAll { it.leaving }
        pr.set(1.0)
        h.hold()
        w.hold()
        o.set(1.0)
        bound = true
        releasing = false
    }

    private fun follow(pxX: Float, pxY: Float) {
        val x = toStageX(pxX)
        val y = toStageY(pxY)
        dist = max(dist, hypot(x - x0, y - y0))
        pressed?.let { anim ->
            if (dist >= SplitPillSpec.Press.MOVE) {
                spring(anim.press, 1.0, SplitPillSpec.Spring.RELEASE)
                pressed = null
            }
        }
        raw = p0 + (y0 - y) / SplitPillSpec.Pull.TRAVEL
        if (!bound) {
            if (raw <= 0) return
            armHandle()
        }
        if (pr.v > 0) pr.set(min(pr.v, 1 - (dist / SplitPillSpec.Pull.CATCH_SPAN).coerceIn(0.0, 1.0)))
        val q = pOf(raw)
        h.set(hOf(q) + resH * pr.v)
        w.set(wAt(q))
        kick()
    }

    /**
     * The release (the page's `letGo`). A [cancelled] gesture takes no action and projects no
     * speed: the pill goes back to where it was going before the finger came.
     */
    private fun letGo(cancelled: Boolean) {
        val kind = hold
        hold = null
        val tap = !cancelled && kind != Hold.DOWN && dist < SplitPillSpec.Press.MOVE
        if (sw.goal != 0.0) spring(sw, 0.0, SplitPillSpec.Spring.RELEASE)
        val meant = pressedItem
        pressedItem = null
        if (!bound) {
            if (tap) openPill(byHand = true) else startDim()
            pressed = null
            pressedBadge = null
            kick()
            return
        }
        val speed = if (cancelled) 0.0 else PillPull.fingerSpeed(sampleT, sampleY, samples)
        val vr = speed / SplitPillSpec.Pull.TRAVEL
        val e = 1e-4
        val vh = (hOf(pOf(raw + e)) - hOf(pOf(raw - e))) / (2 * e) * vr
        val vw = (wAt(pOf(raw + e)) - wAt(pOf(raw - e))) / (2 * e) * vr
        if (tap && kind == Hold.HANDLE) {
            unbind(End.TAP)
            items.clear()
            launchOpen(vh, vw)
            openPill(byHand = true)
            return
        }
        val keep = desc?.vacant == true
        var item: ItemAnim? = null
        var badge: ItemAnim? = null
        pressed?.let { spring(it.press, 1.0, SplitPillSpec.Spring.RELEASE) }
        pressedBadge?.let { tween(it.badgeS, 1.0, SplitPillSpec.Motion.BADGE_RELEASE) }
        val open: Boolean
        if (cancelled) {
            open = when (kind) {
                Hold.HANDLE -> false
                Hold.CATCH -> keep || wasOpening
                Hold.DOWN, null -> true
            }
        } else if (tap && kind == Hold.CATCH) {
            val anim = pressed
            val held = now() - heldAt
            // Only what the finger took, and only if it still means what it meant then.
            if (anim != null && !anim.leaving && itemShown(anim) >= SplitPillSpec.Pull.TAP_VISIBLE &&
                !(anim.item is PillItem.Chip && (anim.item as PillItem.Chip).missing == null && held >= SplitPillSpec.Timer.TAP) &&
                stillMeans(meant)
            ) {
                item = anim
            }
            pressedBadge?.let { b -> if (!b.leaving && stillMeans(meant)) badge = b }
            open = keep || item != null || badge != null || onHit || wasOpening
        } else {
            open = keep || tap || PillPull.opens(pOf(raw), speed)
        }
        pressed = null
        pressedBadge = null
        releasing = true
        spring(pr, 0.0, SplitPillSpec.Spring.CATCH)
        if (open) {
            if (mode == Mode.HANDLE) {
                mode = Mode.OPEN
                byHand = true
                contentDescription = AppText.s(R.string.split_pill_open)
            }
            cancel(Timer.CLEAR)
            cancel(Timer.DIM)
            launchOpen(vh, vw)
            spring(h, SplitPillSpec.Pill.OPEN_H, SplitPillSpec.Spring.OPEN_H)
            spring(w, pullW, SplitPillSpec.Spring.OPEN)
            // A pull down over an empty pane springs back open; on the apps row it also takes the
            // row back to the one «+» was pressed in, once the pull has let go.
            backAfterPull = kind == Hold.DOWN && !cancelled && keep && content == Content.APPS
            armAuto()
            badge?.let(::tapBadge)
            item?.let(::tapItem)
        } else {
            mode = Mode.HANDLE
            byHand = false
            cancel(Timer.AUTO)
            val shut = SplitPillSpec.Pull.BOUND_SHUT
            val close = PillSpring.of(SplitPillSpec.Spring.CLOSE)
            h.spring(SplitPillSpec.Handle.H, close, 0.0, now(), PillPull.boundVel(vh, h.v, SplitPillSpec.Handle.H, close, SplitPillSpec.Handle.H - shut))
            w.spring(SplitPillSpec.Handle.W, close, 0.0, now(), PillPull.boundVel(vw, w.v, SplitPillSpec.Handle.W, close, SplitPillSpec.Handle.W - shut))
            if (reduced) {
                h.set(SplitPillSpec.Handle.H)
                w.set(SplitPillSpec.Handle.W)
            }
            later(Timer.CLEAR, SplitPillSpec.Timer.CLEAR) { if (mode == Mode.HANDLE && hold == null) items.clear() }
            contentDescription = AppText.s(R.string.split_pill_handle)
            startDim()
        }
        updateRegion()
        kick()
    }

    private fun launchOpen(vh: Double, vw: Double) {
        val openH = PillSpring.of(SplitPillSpec.Spring.OPEN_H)
        val open = PillSpring.of(SplitPillSpec.Spring.OPEN)
        val vH = PillPull.boundVel(vh, h.v, SplitPillSpec.Pill.OPEN_H, openH, SplitPillSpec.Pill.OPEN_H + SplitPillSpec.Pull.BOUND_HEIGHT)
        val vW = PillPull.boundVel(vw, w.v, pullW, open, pullW * (1 + SplitPillSpec.Pull.BOUND_WIDTH))
        h.spring(h.v, openH, 0.0, now(), vH)
        w.spring(w.v, open, 0.0, now(), vW)
    }

    private enum class End { OPEN, CLOSED, TAP }

    /** The derived values handed back to their own (the page's `unbind`). */
    private fun unbind(end: End) {
        val ww = max(SplitPillSpec.Pill.MIN_W, w.v)
        dx.set(
            when (end) {
                End.OPEN -> layout.dx
                End.CLOSED -> 0.0
                End.TAP -> layout.dx * ((ww - SplitPillSpec.Handle.W) / max(1.0, pullW - SplitPillSpec.Handle.W)).coerceIn(0.0, 1.0)
            },
        )
        bound = false
        releasing = false
        when (end) {
            End.OPEN -> {
                o.set(1.0)
                rowA.set(1.0)
            }
            End.CLOSED -> {
                o.set(0.0)
                rowA.set(0.0)
            }
            End.TAP -> o.set(1.0)
        }
        items.forEach { anim ->
            anim.a.set(1.0)
            anim.dy.set(0.0)
        }
        pr.set(0.0)
    }

    // endregion

    // region what is drawn where

    /** The capsule as drawn now, in [box] (left, top, right, bottom), reused: nothing allocates. */
    private val box = DoubleArray(4)

    private fun capsuleNow() {
        val ww = max(SplitPillSpec.Pill.MIN_W, w.v)
        val hh = max(SplitPillSpec.Pill.MIN_H, h.v)
        val ox = if (bound) {
            layout.dx * ((ww - SplitPillSpec.Handle.W) / max(1.0, pullW - SplitPillSpec.Handle.W)).coerceIn(0.0, 1.0)
        } else {
            dx.v
        }
        PillGeometry.capsuleInto(divX, ox, ww, hh, box)
        // A pressed disc is swollen about its centre; the swell is given back over the first
        // 2 x swell of growth.
        val swell = SplitPillSpec.Handle.SWELL
        if (sw.v != 0.0) {
            val g = 1 - ((hh - SplitPillSpec.Handle.H) / (2 * swell)).coerceIn(0.0, 1.0)
            val s = sw.v * swell * g * g
            box[0] -= s / 2
            box[1] -= s / 2
            box[2] += s / 2
            box[3] += s / 2
        }
    }

    private fun rowScaleNow(): Double {
        if (!bound) return 1.0
        val base = PillPull.rowScale(max(SplitPillSpec.Pill.MIN_H, h.v), max(SplitPillSpec.Pill.MIN_W, w.v), pullW)
        return max(0.0, base + resScale * pr.v)
    }

    private fun rowAlpha(): Double = if (bound) (1 + resRow * pr.v).coerceIn(0.0, 1.0) else rowA.v.coerceIn(0.0, 1.0)

    private fun rowCentreX(): Double {
        capsuleNow()
        return (box[0] + box[2]) / 2
    }

    /** The row at the pill's centre; past the open height (the band) on the pill's bottom. */
    private fun rowCentreY(): Double {
        capsuleNow()
        val hh = box[3] - box[1]
        if (hh <= SplitPillSpec.Pill.OPEN_H) return (box[1] + box[3]) / 2
        return box[3] - SplitPillSpec.Pill.OPEN_H * rowScaleNow() / 2
    }

    private fun kNow(): Double =
        ((max(SplitPillSpec.Pill.MIN_H, h.v) - SplitPillSpec.Handle.H) / span).coerceIn(0.0, 1.0)

    private fun layoutIndex(anim: ItemAnim): Int {
        val key = anim.item.key
        val list = layout.items
        for (i in list.indices) if (list[i].key == key) return i
        return -1
    }

    private fun itemAlpha(anim: ItemAnim): Double {
        if (!bound) return anim.a.v.coerceIn(0.0, 1.0)
        val i = layoutIndex(anim)
        if (i < 0) return 0.0
        val res = if (i < resItems.size) resItems[i] else 0.0
        return (PillPull.revealOf(i, layout.items.size, kNow()) + res * pr.v).coerceIn(0.0, 1.0)
    }

    /** How far the item stands below its place now: its entrance, or under a pull its reveal. */
    private fun riseOf(anim: ItemAnim): Double {
        if (!bound) return anim.dy.v
        val i = layoutIndex(anim).coerceAtLeast(0)
        val res = if (i < resDy.size) resDy[i] else 0.0
        return SplitPillSpec.Enter.DY * (1 - PillPull.revealOf(i, layout.items.size, kNow())) + res * pr.v
    }

    /** The item's own scale now: its pop or swap, or under a pull what it had when caught. */
    private fun scaleOf(anim: ItemAnim): Double {
        if (!bound) return anim.s.v
        val i = layoutIndex(anim)
        val res = if (i >= 0 && i < resS.size) resS[i] else 0.0
        return anim.s.v + res * pr.v
    }

    private fun itemShown(anim: ItemAnim): Double = itemAlpha(anim) * rowAlpha()

    private val local = DoubleArray(2)

    /**
     * (x, y) on the stage in [anim]'s own measure as it is drawn now - its slide, swap offset,
     * lift and scale, and the row's own scale - into [local]: x from its left edge, y from the
     * row's centre line. False for an item not shown enough to take a tap (`pull.tap.visible`).
     */
    private fun toItem(anim: ItemAnim, x: Double, y: Double): Boolean {
        if (anim.leaving || itemShown(anim) < SplitPillSpec.Pull.TAP_VISIBLE) return false
        val sc = rowScaleNow()
        val s = scaleOf(anim)
        if (sc <= 0 || s <= 0) return false
        val iw = PillGeometry.itemWidth(anim.item)
        val ih = PillGeometry.itemHeight(anim.item)
        val rx = (x - rowCentreX()) / sc - (anim.x.v + anim.tx.v)
        val ry = (y - rowCentreY()) / sc - (-ih / 2 + riseOf(anim))
        local[0] = iw / 2 + (rx - iw / 2) / s
        local[1] = ih / 2 + (ry - ih / 2) / s - ih / 2
        return true
    }

    /**
     * The badge drawn under (x, y): a badge goes before every chip, its own included. Only the live
     * chip's badge is a control; another chip's star is a mark, and a tap on it opens that chip.
     */
    private fun badgeUnder(x: Double, y: Double): ItemAnim? {
        for (i in items.indices.reversed()) {
            val anim = items[i]
            val chip = anim.item as? PillItem.Chip ?: continue
            if (!chip.live || anim.badge == null || anim.badgeA.v < 0.5) continue
            if (toItem(anim, x, y) && PillGeometry.hitsBadge(local[0], local[1])) return anim
        }
        return null
    }

    /** The item drawn under (x, y), the topmost first; not a leaving one. */
    private fun itemUnder(x: Double, y: Double): ItemAnim? {
        for (i in items.indices.reversed()) {
            val anim = items[i]
            if (!toItem(anim, x, y)) continue
            if (PillGeometry.hitsItem(anim.item, local[0], local[1])) return anim
            // A pinned chip's star mark is the chip's: a tap on its overhang opens the chip too -
            // while the mark is the chip's own and drawn, not one fading off a repair chip.
            val chip = anim.item as? PillItem.Chip ?: continue
            val marked = !chip.live && chip.badge == PillBadge.SOLID && anim.badgeA.v >= 0.5
            if (marked && PillGeometry.hitsBadge(local[0], local[1])) return anim
        }
        return null
    }

    // endregion

    // region region

    private var lastRegion: List<PillRect> = emptyList()
    private var lastHit: PillRect? = null
    private var regionMoving = false

    private fun geometryMoving(): Boolean = bound || h.active || w.active || dx.active

    /**
     * The touchable region for what the pill is doing ([PillGeometry.region]): set when a motion
     * starts, when the shape stops and the moment hiding starts - never per frame.
     */
    private fun updateRegion() {
        val shown = visible && !disposed
        val moving = shown && geometryMoving()
        regionMoving = moving
        val (shapes, hit) = PillGeometry.region(shown, mode == Mode.OPEN, moving, divX, layout, w.v, dx.v)
        send(shapes, hit)
    }

    private fun send(shapes: List<PillRect>, hit: PillRect?) {
        if (shapes == lastRegion && hit == lastHit) return
        lastRegion = shapes
        lastHit = hit
        actions.touchRegion(PillGeometry.touchBands(shapes, hit, scale))
    }

    // endregion

    // region frames

    private var lastFrame = Double.NaN
    private var looping = false
    private var clockMs = Double.NaN

    /** The frame clock: the system's, or the fixture's when it pins a moment. */
    private fun now(): Double = if (clockMs.isNaN()) SystemClock.uptimeMillis().toDouble() else clockMs

    private val frame = Choreographer.FrameCallback { nanos ->
        looping = false
        val t = nanos / 1e6
        val dt = if (lastFrame.isNaN()) 1.0 / 60 else ((t - lastFrame) / 1000).coerceIn(0.0, MAX_DT)
        lastFrame = t
        if (disposed) return@FrameCallback
        step(dt, now())
        invalidate()
        // The region is final the moment the shape stops, whatever still breathes or fades.
        if (regionMoving && !geometryMoving()) updateRegion()
        if (animating()) {
            loop()
        } else {
            lastFrame = Double.NaN
            if (bound && releasing) settlePull()
            updateRegion()
        }
    }

    private fun kick() {
        invalidate()
        loop()
    }

    /** Frames only for an attached, live view: a removed one is never scheduled again. */
    private fun loop() {
        if (looping || clockMs.isFinite() || disposed || !isAttachedToWindow) return
        looping = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (disposed) return
        invalidate()
        if (animating()) loop()
    }

    // Indexed loops: a frame allocates nothing, not even an iterator (decision 5).
    private fun step(dt: Double, nowMs: Double) {
        for (i in values.indices) values[i].step(dt, nowMs)
        pr.step(dt, nowMs)
        for (i in items.indices) {
            val all = items[i].all
            for (j in all.indices) all[j].step(dt, nowMs)
        }
        var i = items.size - 1
        while (i >= 0) {
            val anim = items[i]
            if (anim.leaving && !anim.a.active) items.removeAt(i)
            // Timed pictures run out here, not only when drawn: a view that no longer draws stops.
            if (anim.flashAt.isFinite() && nowMs - anim.flashAt >= SplitPillSpec.Motion.STAR_FLASH.ms) anim.flashAt = Double.NaN
            i--
        }
        for (b in 0 until BLOOMS) {
            if (bloomT[b].isFinite() && nowMs - bloomT[b] >= SplitPillSpec.Motion.BLOOM.ms) bloomT[b] = Double.NaN
        }
        if (bound && releasing && !h.active && !pr.active) settlePull()
    }

    private fun settlePull() {
        releasing = false
        unbind(if (h.v >= SplitPillSpec.Pill.OPEN_H) End.OPEN else End.CLOSED)
        val back = backAfterPull && mode == Mode.OPEN
        backAfterPull = false
        if (back) content = Content.HOME
        // What arrived while the row was held is the row now.
        if (mode == Mode.OPEN && (back || stale)) reconcile(row())
        stale = false
        if (closeAfterPull) {
            closeAfterPull = false
            if (mode == Mode.OPEN && desc?.vacant != true) closePill()
        }
        if (mode == Mode.HANDLE) {
            items.clear()
            layout = PillGeometry.layout(emptyList())
        }
        updateRegion()
    }

    private fun animating(): Boolean {
        if (pr.active || hold != null) return true
        for (i in values.indices) if (values[i].active) return true
        for (i in items.indices) {
            val anim = items[i]
            if (anim.flashAt.isFinite()) return true
            for (j in anim.all.indices) if (anim.all[j].active) return true
            // A contour breathes while it is shown - unless motion is off, when it holds still.
            if (!reduced && (anim.gl.v > 0.002 || anim.gr.v > 0.002) && rowA.v > 0) return true
        }
        for (i in 0 until BLOOMS) if (bloomT[i].isFinite()) return true
        return false
    }

    // endregion

    // region drawing

    private val cap = RectF()
    private val dst = RectF()
    private val src = Rect()
    private val clip = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SplitPillSpec.Color.GLASS }
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * The rim: a `pill.rim.w` stroke centred half its width inside the capsule's edge, so it
     * covers exactly the ring the page's border does. It and the sheen take the prepared gradients
     * of the capsule's size class ([PillGlass]) with the canvas only translated to the capsule's
     * top: a shader is never given a new matrix (which would remake it natively on every frame of a
     * growing pill), and no path is built per frame.
     */
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = SplitPillSpec.Pill.RIM_W.toFloat()
    }
    private val breatheEase = PillEase.named(SplitPillSpec.Motion.BREATHE.ease)
    private val flashEase = PillEase.named(SplitPillSpec.Motion.STAR_FLASH.ease)
    private val bloomEase = PillEase.named(SplitPillSpec.Motion.BLOOM.ease)
    private val bead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SplitPillSpec.Color.HANDLE }
    private val plain = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { blendMode = BlendMode.PLUS }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }

    override fun onDraw(canvas: Canvas) {
        if (shown.v <= 0.001 && !visible) return
        canvas.save()
        canvas.scale(scale.toFloat(), scale.toFloat())
        canvas.translate(-window.l.toFloat(), -window.t.toFloat())
        capsuleNow()
        cap.set(box[0].toFloat(), box[1].toFloat(), box[2].toFloat(), box[3].toFloat())
        val r = cap.height() / 2
        val show = shown.v.coerceIn(0.0, 1.0)
        // The disc is glass at rest: idle, it goes as quiet as `dimGlass` with the bead's dimming.
        val awake = ((hA.v - SplitPillSpec.Handle.DIM) / (SplitPillSpec.Handle.ALPHA - SplitPillSpec.Handle.DIM)).coerceIn(0.0, 1.0)
        val glassO = if (bound) 1.0 else o.v.coerceIn(0.0, 1.0)
        val g = max(glassO, SplitPillSpec.Handle.DIM_GLASS + (1 - SplitPillSpec.Handle.DIM_GLASS) * awake) * show
        if (g > 0.001) {
            drawShadow(canvas, g)
            fill.alpha = alpha(((SplitPillSpec.Color.GLASS ushr 24) / 255.0) * g)
            canvas.drawRoundRect(cap, r, r, fill)
            // Sheen and rim from the capsule's top, in its size class's gradients.
            val hh = cap.height()
            val cls = PillGlassPlan.classOf(hh.toDouble(), scale)
            val rw = min(SplitPillSpec.Pill.RIM_W.toFloat(), hh / 2)
            canvas.save()
            canvas.translate(0f, cap.top)
            sheen.shader = art.glass.sheen(cls)
            sheen.alpha = alpha(g)
            canvas.drawRoundRect(cap.left, 0f, cap.right, hh, r, r, sheen)
            val half = rw / 2
            val rr = max(0f, r - half)
            rim.shader = art.glass.rim(cls)
            rim.alpha = alpha(g)
            canvas.drawRoundRect(cap.left + half, half, cap.right - half, hh - half, rr, rr, rim)
            canvas.restore()
        }
        // The bead: the handle's white at the centre, fading over the first `fadeBy` of growth.
        val h0 = max(SplitPillSpec.Pill.MIN_H, h.v)
        val beadA = hA.v * (1 - (h0 - SplitPillSpec.Handle.H) / SplitPillSpec.Handle.FADE_BY).coerceIn(0.0, 1.0) * show
        if (beadA > 0.001) {
            val swell = cap.height() - h0.toFloat()
            val size = min(min(cap.width(), cap.height()), (SplitPillSpec.Handle.BEAD + swell).toFloat())
            bead.alpha = alpha(beadA)
            dst.set(cap.centerX() - size / 2, cap.centerY() - size / 2, cap.centerX() + size / 2, cap.centerY() + size / 2)
            canvas.drawRoundRect(dst, size / 2, size / 2, bead)
        }
        val ra = rowAlpha() * show
        if (ra > 0.001 && items.isNotEmpty()) drawRow(canvas, ra)
        drawBlooms(canvas)
        canvas.restore()
    }

    private fun drawRow(canvas: Canvas, ra: Double) {
        canvas.save()
        clip.reset()
        clip.addRoundRect(cap, cap.height() / 2, cap.height() / 2, Path.Direction.CW)
        canvas.clipPath(clip)
        val sc = rowScaleNow().toFloat()
        canvas.translate(rowCentreX().toFloat(), rowCentreY().toFloat())
        canvas.scale(sc, sc)
        for (index in items.indices) {
            val anim = items[index]
            val a = ra * itemAlpha(anim)
            if (a <= 0.001) continue
            val item = anim.item
            val ih = PillGeometry.itemHeight(item).toFloat()
            val iw = PillGeometry.itemWidth(item).toFloat()
            val rise = riseOf(anim)
            canvas.save()
            canvas.translate((anim.x.v + anim.tx.v).toFloat(), (-ih / 2 + rise).toFloat())
            val s = scaleOf(anim).toFloat()
            if (s != 1f) canvas.scale(s, s, iw / 2, ih / 2)
            val sy = anim.sy.v.toFloat()
            if (sy != 1f) canvas.scale(1f, sy, iw / 2, ih / 2)
            when (item) {
                is PillItem.Chip -> drawChip(canvas, anim, item, a)
                is PillItem.Single -> {
                    val p = anim.press.v.toFloat()
                    val top = (ih - SplitPillSpec.Icon.SIZE.toFloat()) / 2
                    canvas.scale(p, p, iw / 2, ih / 2)
                    drawIcon(canvas, item.packageName, 0f, top, a)
                }
                PillItem.Separator -> {
                    plain.color = SplitPillSpec.Color.SEP
                    plain.alpha = alpha(((SplitPillSpec.Color.SEP ushr 24) / 255.0) * a)
                    canvas.drawRect(0f, 0f, iw, ih, plain)
                }
                PillItem.Plus -> {
                    val p = anim.press.v.toFloat()
                    canvas.scale(p, p, iw / 2, ih / 2)
                    bmp.alpha = alpha(a)
                    dst.set(0f, 0f, iw, ih)
                    canvas.drawBitmap(art.plus, null, dst, bmp)
                }
                PillItem.DividerMark -> {
                    plain.color = SplitPillSpec.Color.WHITE
                    plain.alpha = alpha(SplitPillSpec.Apps.MARK_ALPHA * a)
                    val rr = SplitPillSpec.Apps.MARK_R.toFloat()
                    dst.set(0f, 0f, iw, ih)
                    canvas.drawRoundRect(dst, rr, rr, plain)
                }
            }
            canvas.restore()
        }
        canvas.restore()
    }

    private fun drawChip(canvas: Canvas, anim: ItemAnim, chip: PillItem.Chip, a: Double) {
        val t = anim.t.v.coerceIn(0.0, SplitPillSpec.Chip.OVERSHOOT)
        val t1 = t.coerceIn(0.0, 1.0)
        val faceTop = ((SplitPillSpec.Row.H_CHIP - SplitPillSpec.Chip.H) / 2).toFloat()
        val inset = SplitPillSpec.Chip.INSET
        val openX = inset + SplitPillSpec.Icon.SIZE + SplitPillSpec.Chip.GAP
        val centre = lerp(SplitPillSpec.Chip.STACKED / 2, SplitPillSpec.Chip.OPEN / 2, t1).toFloat()
        val p = anim.press.v.toFloat()
        canvas.save()
        canvas.scale(p, p, centre, faceTop + SplitPillSpec.Chip.H.toFloat() / 2)
        val bx = lerp(SplitPillSpec.Chip.BACK_X, openX, t)
        val by = lerp(SplitPillSpec.Chip.BACK_Y, inset, t1)
        val bs = lerp(SplitPillSpec.Chip.BACK_SCALE, 1.0, t1)
        val faceW = SplitPillSpec.Chip.OPEN.toFloat()
        // The back app, cut round by a ring that follows the front one, in a layer of the face.
        val layer = canvas.saveLayer(0f, faceTop - 1, faceW + 1, faceTop + SplitPillSpec.Chip.H.toFloat() + 1, null)
        canvas.save()
        canvas.translate(bx.toFloat(), (faceTop + by).toFloat())
        canvas.scale(bs.toFloat(), bs.toFloat())
        drawSide(canvas, chip.right, anim.gr.v, a)
        canvas.restore()
        val cut = SplitPillSpec.Chip.CUT
        val ringR = (SplitPillSpec.Icon.RADIUS + cut).toFloat()
        dst.set(
            (inset - cut).toFloat(),
            (faceTop + inset - cut).toFloat(),
            (inset + SplitPillSpec.Icon.SIZE + cut).toFloat(),
            (faceTop + inset + SplitPillSpec.Icon.SIZE + cut).toFloat(),
        )
        if (t1 < 1) canvas.drawRoundRect(dst, ringR, ringR, clear)
        canvas.save()
        canvas.translate(inset.toFloat(), (faceTop + inset).toFloat())
        drawSide(canvas, chip.left, anim.gl.v, a)
        canvas.restore()
        canvas.restoreToCount(layer)
        canvas.restore()
        // The live mark under the middle of whatever the chip covers now.
        val markA = anim.markA.v * a
        if (markA > 0.001) {
            val pad = art.liveMarkPad / scale
            val mw = SplitPillSpec.Mark.W
            val left = centre - mw / 2 - pad
            val top = SplitPillSpec.Mark.TOP - pad
            bmp.alpha = alpha(markA)
            dst.set(left.toFloat(), top.toFloat(), (left + mw + 2 * pad).toFloat(), (top + SplitPillSpec.Mark.H + 2 * pad).toFloat())
            canvas.drawBitmap(art.liveMark, null, dst, bmp)
        }
        // The pin badge, part of the chip's content.
        val badge = anim.badge
        val badgeA = anim.badgeA.v * a
        if (badge != null && badgeA > 0.001) {
            val size = SplitPillSpec.Badge.SIZE
            val pad = art.badgePad / scale
            val bs2 = anim.badgeS.v.toFloat()
            val cx = (SplitPillSpec.Badge.X + size / 2).toFloat()
            val cy = (SplitPillSpec.Badge.Y + size / 2).toFloat()
            canvas.save()
            canvas.scale(bs2, bs2, cx, cy)
            dst.set(
                (SplitPillSpec.Badge.X - pad).toFloat(),
                (SplitPillSpec.Badge.Y - pad).toFloat(),
                (SplitPillSpec.Badge.X + size + pad).toFloat(),
                (SplitPillSpec.Badge.Y + size + pad).toFloat(),
            )
            bmp.alpha = alpha(badgeA)
            canvas.drawBitmap(art.badge(badge == PillBadge.SOLID, lit = false), null, dst, bmp)
            val lit = flash(anim)
            if (lit > 0.001) {
                bmp.alpha = alpha(badgeA * lit)
                canvas.drawBitmap(art.badge(solid = true, lit = true), null, dst, bmp)
            }
            canvas.restore()
        }
    }

    /** One app of a chip: solid, or the breathing contour as `g` goes to 1. */
    private fun drawSide(canvas: Canvas, packageName: String, g0: Double, a: Double) {
        val g = g0.coerceIn(0.0, 1.0)
        val solid = (1 - g * SplitPillSpec.Ghost.SOLID_FADE).coerceIn(0.0, 1.0)
        if (solid > 0.001) drawIcon(canvas, packageName, 0f, 0f, a * solid)
        if (g > 0.002) {
            val contour = art.contour(packageName) ?: return
            val shownG = ((g - SplitPillSpec.Ghost.RIM_FROM) / (1 - SplitPillSpec.Ghost.RIM_FROM)).coerceIn(0.0, 1.0)
            val ca = a * shownG * breathe()
            val pad = art.contourPad / scale
            bmp.alpha = alpha(ca)
            dst.set((-pad).toFloat(), (-pad).toFloat(), (SplitPillSpec.Icon.SIZE + pad).toFloat(), (SplitPillSpec.Icon.SIZE + pad).toFloat())
            canvas.drawBitmap(contour, null, dst, bmp)
        }
    }

    private fun drawIcon(canvas: Canvas, packageName: String, x: Float, y: Float, a: Double) {
        val size = SplitPillSpec.Icon.SIZE.toFloat()
        dst.set(x, y, x + size, y + size)
        val icon: Bitmap? = art.icon(packageName)
        if (icon == null) {
            plain.color = SplitPillSpec.Color.BTN
            plain.alpha = alpha(((SplitPillSpec.Color.BTN ushr 24) / 255.0) * a)
            val rr = SplitPillSpec.Icon.RADIUS.toFloat()
            canvas.drawRoundRect(dst, rr, rr, plain)
            return
        }
        bmp.alpha = alpha(a)
        canvas.drawBitmap(icon, null, dst, bmp)
    }

    /**
     * The contour's breathing: one full cycle - `lo` up to `hi` and back - every `motion.breathe`
     * ms; held at `still` when motion is off, at `hi` on a pinned fixture moment.
     */
    private fun breathe(): Double {
        if (clockMs.isFinite()) return SplitPillSpec.Ghost.BREATHE_HI
        if (reduced) return SplitPillSpec.Ghost.BREATHE_STILL
        return lerp(SplitPillSpec.Ghost.BREATHE_LO, SplitPillSpec.Ghost.BREATHE_HI, breatheEase.at(breathePhase(now(), SplitPillSpec.Motion.BREATHE.ms)))
    }

    /** The star's flash: crown-blue and lit, held for `star.hold`, then out over `starFlash`. */
    private fun flash(anim: ItemAnim): Double {
        if (anim.flashAt.isNaN()) return 0.0
        val m = SplitPillSpec.Motion.STAR_FLASH
        val p = (now() - anim.flashAt) / m.ms
        if (p >= 1) {
            anim.flashAt = Double.NaN
            return 0.0
        }
        if (p <= SplitPillSpec.Star.HOLD) return 1.0
        return 1 - flashEase.at((p - SplitPillSpec.Star.HOLD) / (1 - SplitPillSpec.Star.HOLD))
    }

    private fun drawShadow(canvas: Canvas, g: Double) {
        val s = art.shadow
        val sy = cap.height() / SplitPillSpec.Pill.OPEN_H
        val top = (cap.top - s.reachT * sy).toFloat()
        val bottom = (cap.bottom + s.reachB * sy).toFloat()
        val capHalf = (SplitPillSpec.Pill.OPEN_H / 2 * sy).toFloat()
        var leftEnd = cap.left + capHalf
        var rightStart = cap.right - capHalf
        if (rightStart < leftEnd) {
            leftEnd = cap.centerX()
            rightStart = leftEnd
        }
        bmp.alpha = alpha(g)
        src.set(0, 0, s.leftEnd, s.bitmap.height)
        dst.set((cap.left - s.reachL * sy).toFloat(), top, leftEnd, bottom)
        canvas.drawBitmap(s.bitmap, src, dst, bmp)
        if (rightStart > leftEnd) {
            src.set(s.leftEnd, 0, s.rightStart, s.bitmap.height)
            dst.set(leftEnd, top, rightStart, bottom)
            canvas.drawBitmap(s.bitmap, src, dst, bmp)
        }
        src.set(s.rightStart, 0, s.bitmap.width, s.bitmap.height)
        dst.set(rightStart, top, (cap.right + s.reachR * sy).toFloat(), bottom)
        canvas.drawBitmap(s.bitmap, src, dst, bmp)
    }

    private fun bloom(x: Double, y: Double) {
        if (reduced) return
        bloomX[bloomNext] = x
        bloomY[bloomNext] = y
        bloomT[bloomNext] = now()
        bloomNext = (bloomNext + 1) % BLOOMS
        kick()
    }

    private fun drawBlooms(canvas: Canvas) {
        val m = SplitPillSpec.Motion.BLOOM
        for (i in 0 until BLOOMS) {
            if (bloomT[i].isNaN()) continue
            val t = ((now() - bloomT[i]) / m.ms).coerceIn(0.0, 1.0)
            if (t >= 1) {
                bloomT[i] = Double.NaN
                continue
            }
            val e = bloomEase.at(t)
            val peak = SplitPillSpec.Bloom.PEAK_AT
            val sc: Double
            val a: Double
            if (e < peak) {
                sc = lerp(SplitPillSpec.Bloom.SCALE_FROM, SplitPillSpec.Bloom.SCALE_PEAK, e / peak)
                a = e / peak
            } else {
                val u = (e - peak) / (1 - peak)
                sc = lerp(SplitPillSpec.Bloom.SCALE_PEAK, SplitPillSpec.Bloom.SCALE_TO, u)
                a = 1 - u
            }
            val half = (SplitPillSpec.Bloom.SIZE * sc / 2).toFloat()
            dst.set((bloomX[i] - half).toFloat(), (bloomY[i] - half).toFloat(), (bloomX[i] + half).toFloat(), (bloomY[i] + half).toFloat())
            bloomPaint.alpha = alpha(a)
            canvas.drawBitmap(art.bloom, null, dst, bloomPaint)
        }
    }

    // endregion

    // region motion helpers

    /** A spring from where [v] is, keeping its speed: a catch or a retarget never jumps. */
    private fun spring(v: PillValue, to: Double, spec: PillSpringSpec, delay: Double = 0.0) {
        if (reduced) {
            v.set(to)
            return
        }
        v.spring(to, PillSpring.of(spec), delay, now())
    }

    private fun tween(v: PillValue, to: Double, m: PillTween, delay: Double = m.delay) {
        if (reduced) {
            v.set(to)
            return
        }
        v.tween(to, m, now(), delay)
    }

    // endregion

    // region timers

    private enum class Timer { AUTO, DIM, SELF_OPEN, CLEAR, HOME_RESET, CHIP_NUDGE, BADGE_BACK, NUDGE_BACK, REQUEST_OPEN }

    private val timers = HashMap<Timer, Runnable>()

    private fun later(timer: Timer, delayMs: Double, action: () -> Unit) {
        cancel(timer)
        if (disposed) return
        val runnable = Runnable {
            timers.remove(timer)
            if (disposed) return@Runnable
            action()
            kick()
        }
        timers[timer] = runnable
        handler.postDelayed(runnable, delayMs.toLong())
    }

    private fun cancel(timer: Timer) {
        timers.remove(timer)?.let(handler::removeCallbacks)
    }

    override fun onDetachedFromWindow() {
        timers.values.forEach(handler::removeCallbacks)
        timers.clear()
        Choreographer.getInstance().removeFrameCallback(frame)
        looping = false
        super.onDetachedFromWindow()
    }

    // endregion

    // region fixture

    /** A still moment of the design's boards, for `compare.py` in the debug build. */
    enum class Board { HANDLE, HANDLE_DIM, OPEN, APPS }

    /**
     * Draws [board] for [description] with every value at rest and the clock stopped: the contour
     * at its brightest, as the boards show it. Debug fixture only.
     */
    fun pin(description: SplitPillDescription, board: Board) {
        // A pinned moment runs no timer: the awake handle never dims, the open pill never closes.
        timers.values.forEach(handler::removeCallbacks)
        timers.clear()
        clockMs = 0.0
        desc = description
        divX = description.dividerX
        visible = true
        resetToHandle()
        shown.set(1.0)
        when (board) {
            Board.HANDLE -> Unit
            Board.HANDLE_DIM -> hA.set(SplitPillSpec.Handle.DIM)
            Board.OPEN, Board.APPS -> {
                mode = Mode.OPEN
                content = if (board == Board.APPS) Content.APPS else Content.HOME
                val l = PillGeometry.layout(row())
                layout = l
                items.clear()
                l.items.forEachIndexed { i, item ->
                    val anim = ItemAnim(item)
                    anim.x.set(l.x[i])
                    update(anim, item, born = true)
                    anim.badge = (item as? PillItem.Chip)?.badge
                    anim.badgeA.set(if (anim.badge != null) 1.0 else 0.0)
                    anim.markA.set(if ((item as? PillItem.Chip)?.live == true) 1.0 else 0.0)
                    items += anim
                }
                w.set(l.w)
                h.set(SplitPillSpec.Pill.OPEN_H)
                dx.set(l.dx)
                o.set(1.0)
                rowA.set(1.0)
            }
        }
        invalidate()
    }

    // endregion

    private companion object {
        const val SAMPLES = 64
        const val BLOOMS = 4

        /** A frame that comes late is stepped as at most this many seconds (the probe's clamp). */
        const val MAX_DT = 0.05

        fun alpha(a: Double): Int = (a.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()

        fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t
    }
}
