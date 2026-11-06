package com.leslie.tvhome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeInteractionPolicyTest {
    @Test fun coldDockRestorationUsesIdentityAfterReorderingAndSafelyFallsBackAfterRemoval() {
        val anchor = HomeFocusMemory.Anchor("youku", 4, 900)
        val reordered = listOf("mango", "bili", "youku", "drive", "box")
        assertEquals(HomeFocusMemory.Anchor("youku", 2, 0), HomeFocusMemory.restoreDock(anchor, reordered, 5))
        val removed = listOf("mango", "bili", "drive", "box")
        assertEquals(HomeFocusMemory.Anchor("box", 3, 0), HomeFocusMemory.restoreDock(anchor, removed, 5))
    }

    @Test fun coldDockRestorationNeverExpandsTheGridOrChangesTheSavedDockOrder() {
        val apps = listOf("mango", "bili", "box", "drive", "youku", "other")
        val before = apps.toList()
        assertEquals(HomeFocusMemory.Anchor("box", 2, 0),
            HomeFocusMemory.restoreDock(HomeFocusMemory.Anchor("youku", 4, 900), apps, 3))
        assertEquals(before, apps)
        assertNull(HomeFocusMemory.restoreDock(HomeFocusMemory.Anchor("youku", 4), emptyList(), 5))
    }

    @Test fun backUnwindsOneLayerAndNeverOpensControlAtTheDock() {
        var state = HomeNavigationPolicy.State(overlay = true, moving = true, control = true, expanded = true, header = false)
        fun back() = HomeNavigationPolicy.action(HomeNavigationPolicy.Key.BACK, state)
        assertEquals(HomeNavigationPolicy.Action.DISMISS_OVERLAY, back())
        state = state.copy(overlay = false)
        assertEquals(HomeNavigationPolicy.Action.END_MOVE, back())
        state = state.copy(moving = false)
        assertEquals(HomeNavigationPolicy.Action.CLOSE_CONTROL, back())
        state = state.copy(control = false)
        assertEquals(HomeNavigationPolicy.Action.RETURN_DOCK, back())
        state = state.copy(expanded = false)
        assertEquals(HomeNavigationPolicy.Action.STAY, back())
        assertEquals(HomeNavigationPolicy.Action.RETURN_DOCK,
            HomeNavigationPolicy.action(HomeNavigationPolicy.Key.BACK, state.copy(header = true)))
    }

    @Test fun menuPassesThroughEveryHomeLayer() {
        for (overlay in listOf(false, true)) for (moving in listOf(false, true))
            for (control in listOf(false, true)) for (expanded in listOf(false, true)) {
                assertEquals(HomeNavigationPolicy.Action.PASS_THROUGH, HomeNavigationPolicy.action(
                    HomeNavigationPolicy.Key.MENU, HomeNavigationPolicy.State(overlay, moving, control, expanded, true)))
            }
    }

    @Test fun appIdentitySurvivesReorderingAndSavedAnchors() {
        val old = listOf("a", "b", "folder", "c", "d", "e", "f")
        val saved = HomeFocusMemory.remember(old, 6, 920)
        val restored = saved.copy() // The same three primitive values stored by HomeActivity.
        val reordered = listOf("f", "a", "folder", "b", "c", "d", "e")
        assertEquals(0, HomeFocusMemory.resolve(restored, old, reordered))
        assertEquals(0, HomeFocusMemory.rebasedScroll(restored, 0, 5, 266))
        assertEquals("f", restored.pkg)
        assertEquals(920, restored.scroll)
    }

    @Test fun removedSelectionFindsNearestSurvivingNeighbour() {
        val old = listOf("a", "b", "c", "d", "e")
        val anchor = HomeFocusMemory.remember(old, 2, 0)
        assertEquals(2, HomeFocusMemory.resolve(anchor, old, listOf("a", "b", "d", "e")))
        assertEquals(1, HomeFocusMemory.resolve(anchor, old, listOf("a", "b")))
        assertEquals(1, HomeFocusMemory.resolve(anchor, old, listOf("new-a", "new-b")))
        assertNull(HomeFocusMemory.resolve(anchor, old, emptyList()))
    }

    @Test fun dockMemoryStaysSeparateFromExpandedSelection() {
        val ids = (0..19).map { "app$it" }
        val dock = HomeFocusMemory.remember(ids, 3, 0)
        val expanded = HomeFocusMemory.remember(ids, 17, 1_120)
        assertEquals(3, HomeFocusMemory.resolve(dock, ids, ids, 5))
        assertEquals(17, HomeFocusMemory.resolve(expanded, ids, ids))
        val withoutDockApp = ids.filterNot { it == "app3" }
        assertEquals(3, HomeFocusMemory.resolve(dock, ids, withoutDockApp, 5))
        assertEquals(1_386, HomeFocusMemory.rebasedScroll(expanded, 22, 5, 266))
    }

    @Test fun refreshDuringMovePreservesTheCurrentManualOrder() {
        assertEquals(listOf("c", "a", "d", "new"),
            HomeFocusMemory.duringMove(listOf("c", "a", "b", "d"), listOf("a", "c", "d", "new")))
    }

    @Test fun laterInputCancelsDelayedReloadAndFocusRestores() {
        val focus = HomeFocusSession()
        val initialRestore = focus.begin()
        focus.userInput() // The user moved while the old layout callback was queued.
        val latest = focus.begin()
        assertFalse(focus.accepts(initialRestore))
        assertTrue(focus.accepts(latest))
        focus.invalidate() // Home paused or a header button took focus.
        assertFalse(focus.accepts(latest))
    }

    @Test fun repeatsAreThrottledThenAccelerateWithoutLosingRelease() {
        val keys = DirectionalKeyPolicy()
        assertTrue(keys.event(22, true, 0, 0).deliver)
        assertFalse(keys.event(22, true, 1, 40).deliver)
        assertTrue(keys.event(22, true, 2, 110).deliver)
        assertFalse(keys.event(22, true, 3, 160).deliver)
        assertTrue(keys.event(22, true, 4, 930).deliver)
        assertTrue(keys.event(22, true, 5, 1_000).deliver)
        assertTrue(keys.event(22, false, 0, 1_010).deliver)
        assertTrue(keys.event(21, true, 0, 1_020).deliver)
    }

    @Test fun quickFreshTapsAndOkayLongPressAreAlwaysDelivered() {
        val keys = DirectionalKeyPolicy()
        assertFalse(keys.event(22, true, 0, 0).rapid)
        keys.event(22, false, 0, 10)
        val tap = keys.event(22, true, 0, 50)
        assertTrue(tap.deliver)
        assertTrue(tap.rapid)
        repeat(15) { assertTrue(keys.event(23, true, it, 60L + it * 10).deliver) }
        assertTrue(keys.event(23, false, 0, 250).deliver)
        keys.reset()
        assertFalse(keys.event(20, true, 0, 300).rapid)
    }

    @Test fun dockLiftsMoreThanGridWithinEveryProfile() {
        for (profile in PerformancePolicy.profiles) {
            val effects = PerformancePolicy.effects(profile)
            val dock = FocusMotionPolicy.target(effects, true, false, 286, 56, 1f)
            val grid = FocusMotionPolicy.target(effects, false, false, 286, 56, 1f)
            assertTrue(dock.scale > grid.scale)
            assertTrue(dock.liftPx > grid.liftPx)
            assertTrue((dock.scale - 1f) * 286 / 2 + 8 <= 56)
        }
    }

    @Test fun crampedLayoutsAndFastNavigationDoNotOverlapOrBounce() {
        val effects = PerformancePolicy.effects(PerformancePolicy.FULL)
        val normal = FocusMotionPolicy.target(effects, true, false, 300, 18, 1f)
        val rapid = FocusMotionPolicy.target(effects, true, true, 300, 18, 1f)
        assertTrue(normal.scale <= 1f + 20f / 300)
        assertEquals(normal.scale, rapid.scale, 0.0001f)
        assertEquals(0f, rapid.tilt, 0.0001f)
        assertFalse(rapid.spring)
        assertTrue(rapid.enterMs < normal.enterMs)
        assertTrue(rapid.exitMs < normal.exitMs)
        val restored = FocusMotionPolicy.target(effects, true, false, 300, 18, 1f)
        assertTrue(restored.tilt > 0f)
        assertTrue(restored.spring)
    }

    @Test fun shelfPauseAndSamePackageReentryRejectOldCallbacks() {
        val shelf = ShelfSession()
        val first = shelf.begin("a")
        val second = shelf.begin("b")
        val again = shelf.begin("a")
        assertFalse(shelf.accepts(first))
        assertFalse(shelf.accepts(second))
        assertTrue(shelf.accepts(again))
        shelf.invalidate() // Pause, settings, a folder, Control Center or Activity stop.
        assertFalse(shelf.accepts(again))
        val resumed = shelf.begin("a")
        assertTrue(shelf.accepts(resumed))
        assertFalse(shelf.accepts(again))
    }

    @Test fun shelfPresentationCanPauseOrDisableOneAppWithoutChangingFeeds() {
        assertNull(ShelfPresentation.delayMs(false, 8))
        for (seconds in listOf(8, 15, 30, 60)) assertEquals(seconds * 1_000L, ShelfPresentation.delayMs(true, seconds))
        assertFalse(ShelfPresentation.enabled("a", setOf("a")))
        assertTrue(ShelfPresentation.enabled("b", setOf("a")))
    }
}
