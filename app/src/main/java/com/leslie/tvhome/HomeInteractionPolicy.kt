package com.leslie.tvhome

import kotlin.math.abs

internal object HomeNavigationPolicy {
    enum class Key { BACK, MENU }
    enum class Action { DISMISS_OVERLAY, END_MOVE, CLOSE_CONTROL, RETURN_DOCK, STAY, PASS_THROUGH }
    data class State(val overlay: Boolean, val moving: Boolean, val control: Boolean, val expanded: Boolean, val header: Boolean)

    fun action(key: Key, state: State): Action = when {
        key == Key.MENU -> Action.PASS_THROUGH
        state.overlay -> Action.DISMISS_OVERLAY
        state.moving -> Action.END_MOVE
        state.control -> Action.CLOSE_CONTROL
        state.expanded || state.header -> Action.RETURN_DOCK
        else -> Action.STAY
    }
}

internal object HomeFocusMemory {
    data class Anchor(val pkg: String? = null, val index: Int = 0, val scroll: Int = 0)

    /** Stable identity first; after removal prefer the next surviving neighbour in the old order. */
    fun resolve(anchor: Anchor, previous: List<String>, current: List<String>, limit: Int = current.size): Int? {
        val count = minOf(limit, current.size)
        if (count <= 0) return null
        val candidates = current.take(count)
        candidates.indexOf(anchor.pkg).takeIf { it >= 0 }?.let { return it }
        val oldIndex = previous.indexOf(anchor.pkg).takeIf { it >= 0 } ?: anchor.index
        previous.indices.sortedWith(compareBy<Int> { abs(it - oldIndex) }.thenBy { if (it >= oldIndex) 0 else 1 })
            .forEach { candidates.indexOf(previous[it]).takeIf { p -> p >= 0 }?.let { p -> return p } }
        return anchor.index.coerceIn(0, count - 1)
    }

    fun remember(ids: List<String>, index: Int, scroll: Int): Anchor =
        ids.getOrNull(index)?.let { Anchor(it, index, scroll.coerceAtLeast(0)) } ?: Anchor()

    /** A cold process restores only a surviving first-row Dock target, never an expanded grid row. */
    fun restoreDock(anchor: Anchor, current: List<String>, columns: Int): Anchor? =
        resolve(anchor, emptyList(), current, columns)?.let { remember(current, it, 0) }

    fun rebasedScroll(anchor: Anchor, index: Int, columns: Int, rowPitch: Int): Int =
        if (index < columns) 0 else (anchor.scroll + (index / columns - anchor.index / columns) * rowPitch).coerceAtLeast(0)

    fun duringMove(previous: List<String>, current: List<String>) =
        previous.filter { it in current } + current.filter { it !in previous }
}

internal class HomeFocusSession {
    data class Ticket(val request: Long, val input: Long)
    var input = 0L
        private set
    private var request = 0L
    fun userInput() { input++; request++ }
    fun invalidate() { request++ }
    fun begin() = Ticket(++request, input)
    fun accepts(ticket: Ticket) = ticket.request == request && ticket.input == input
}

/** Accepts direction changes immediately, throttles only repeated DOWNs, and always releases. */
internal class DirectionalKeyPolicy {
    data class Decision(val deliver: Boolean, val rapid: Boolean)
    private var held: Int? = null
    private var heldSince = 0L
    private var lastDelivered = Long.MIN_VALUE
    private var lastDown = Long.MIN_VALUE

    fun event(code: Int, down: Boolean, repeat: Int, now: Long): Decision {
        if (code !in 19..22) return Decision(true, false)
        if (!down) {
            if (held == code) held = null
            return Decision(true, false)
        }
        val rapid = repeat > 0 || (lastDown != Long.MIN_VALUE && now - lastDown in 0..150)
        lastDown = now
        val fresh = held != code || repeat == 0
        if (fresh) { held = code; heldSince = now }
        val interval = if (now - heldSince >= 900) 65L else 105L
        val deliver = fresh || lastDelivered == Long.MIN_VALUE || now - lastDelivered >= interval
        if (deliver) lastDelivered = now
        return Decision(deliver, rapid)
    }

    fun reset() { held = null; lastDelivered = Long.MIN_VALUE; lastDown = Long.MIN_VALUE }
}

internal object FocusMotionPolicy {
    data class Target(val scale: Float, val maxScale: Float, val liftPx: Float, val tilt: Float, val enterMs: Long, val exitMs: Long, val spring: Boolean)

    fun target(effects: PerformancePolicy.Effects, dock: Boolean, rapid: Boolean, width: Int, gap: Int, density: Float): Target {
        // One selected card grows into half of each adjacent gap, leaving readable space.
        val clearance = 8f * density
        val maxScale = 1f + 2f * (gap - clearance).coerceAtLeast(0f) / width.coerceAtLeast(1)
        return Target(
            minOf(if (dock) effects.dockScale else effects.gridScale, maxScale), maxScale,
            effects.liftDp * density * if (dock) 1f else 0.5f,
            if (rapid) 0f else effects.tiltDegrees,
            if (rapid) minOf(effects.enterMs, 90L) else effects.enterMs,
            if (rapid) minOf(effects.exitMs, 75L) else effects.exitMs,
            !rapid && effects.tiltDegrees > 0f,
        )
    }
}

/** A→B→A, pause and settings changes each invalidate every older asynchronous callback. */
internal class ShelfSession {
    data class Ticket(val generation: Long, val pkg: String)
    private var generation = 0L
    @Volatile var current: Ticket? = null
        private set
    fun begin(pkg: String): Ticket = Ticket(++generation, pkg).also { current = it }
    fun accepts(ticket: Ticket) = current == ticket
    fun invalidate() { generation++; current = null }
}
