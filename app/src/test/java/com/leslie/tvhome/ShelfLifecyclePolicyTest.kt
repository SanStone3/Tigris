package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Test

class ShelfLifecyclePolicyTest {
    /** Controlled Activity/window events, delayed feed callbacks, timer and bitmap identity. */
    private class HomeShelf {
        val session = ShelfSession()
        val requests = ShelfFeedRequests()
        private val capturedCurrent = HashMap<ShelfSession.Ticket, String?>()
        var resumed = false
        var focused = false
        var eligible = true
        var gridFocused = true
        var focusPending = false
        var resumeGridPending = false
        var dockTarget = true
        val rotation = false
        var items = listOf("first", "second", "selected")
        var index = 2
        var bitmap: Any? = Any()
        var shown = true
        var fetches = 0
        var cache: ShelfRefreshPolicy.Feed<String>? = null
        var artworkFailedAt: Long? = null
        var now = 100L
        var refreshDelay: Long? = null
        var advanceScheduled = false

        fun pause() { resumed = false; focused = false; focusPending = false; resumeGridPending = true; reconcile() }
        fun resume(windowFocused: Boolean) { resumed = true; focused = windowFocused; reconcile() }
        fun windowFocus(windowFocused: Boolean) { focused = windowFocused; reconcile() }

        fun openControl() { gridFocused = false; stop() }
        fun closeControlWithDeferredFocus() { focusPending = true; reconcile() }
        fun queueResumeGridFocus() { focusPending = true; resumeGridPending = false; reconcile() }
        fun applyDeferredGridFocus() {
            gridFocused = true
            reconcile() // requestFocus synchronously delivers onTileFocused while focusPending is true.
            focusPending = false
            reconcile() // focusAt.apply explicitly refreshes after clearing the pending flag.
        }

        private fun focusAction() = ShelfFocusPolicy.action(resumed, focused, eligible && gridFocused && dockTarget,
            restoringDockFocus = !gridFocused && (focusPending || resumeGridPending) && eligible && dockTarget)

        private fun reconcile() {
            when (focusAction()) {
                ShelfFocusPolicy.Action.PAUSE -> stop()
                ShelfFocusPolicy.Action.HIDE -> {
                    stop()
                    items = emptyList(); index = 0; bitmap = null; shown = false
                }
                ShelfFocusPolicy.Action.ACTIVE -> {
                    if (session.current == null) fetch() else schedule()
                }
            }
        }

        private fun stop() {
            requests.cancel(); session.invalidate()
            refreshDelay = null; advanceScheduled = false; artworkFailedAt = null
        }

        private fun fetch() {
            session.current?.let { if (requests.isLoading(it)) return }
            val ticket = session.begin("dock")
            capturedCurrent[ticket] = items.getOrNull(index).takeIf { shown }
            requests.begin(ticket)
            fetches++
            refreshDelay = null
        }

        private fun schedule() {
            val ticket = session.current ?: return
            if (requests.isLoading(ticket)) { refreshDelay = null; advanceScheduled = false; return }
            refreshDelay = ShelfRefreshPolicy.nextDelayMs(ShelfRefreshPolicy.feedDelayMs(cache, now), artworkFailedAt, now)
            advanceScheduled = rotation && items.size > 1
        }

        fun runDueRefresh() {
            if (refreshDelay == 0L && focusAction() == ShelfFocusPolicy.Action.ACTIVE) fetch()
        }

        fun advanceTime(ms: Long) {
            now += ms
            refreshDelay = refreshDelay?.let { (it - ms).coerceAtLeast(0) }
            runDueRefresh()
        }

        fun switchAway() { dockTarget = false; reconcile() }

        fun preview(ticket: ShelfSession.Ticket, feed: List<String>): Boolean {
            if (!session.accepts(ticket) || !requests.isLoading(ticket) || focusAction() != ShelfFocusPolicy.Action.ACTIVE) return false
            if (feed.isEmpty()) return false
            if (cache == null) cache = ShelfRefreshPolicy.afterFetch(null, feed, now - ShelfRefreshPolicy.TTL_MS)
            items = feed; index = 0; bitmap = Any(); shown = true
            capturedCurrent[ticket] = feed.first()
            return true
        }

        fun complete(ticket: ShelfSession.Ticket, feed: List<String>?, pictureAvailable: Boolean = true, fromCache: Boolean = false) {
            val previous = capturedCurrent.remove(ticket)
            if (!requests.finish(ticket) || !session.accepts(ticket)) return
            if (!fromCache) cache = ShelfRefreshPolicy.afterFetch(cache, feed, now)
            val fetched = cache!!.items
            val retained = ShelfRefreshPolicy.retainedIndex(fetched, previous) { a, b -> a == b }
            val first = if (retained >= 0) null else ShelfCandidateScan.first(fetched) {
                if (pictureAvailable) Any() else null
            }
            val artworkFailed = retained < 0 && first == null && (fetched.isNotEmpty() || shown)
            artworkFailedAt = if (artworkFailed) now else null
            if (!artworkFailed) items = fetched
            if (retained >= 0) index = retained
            else if (first != null) { index = first.index; bitmap = first.value; shown = true }
            else if (!artworkFailed) { index = 0; bitmap = null; shown = false }
            schedule()
        }
    }

    @Test fun settingsReturnBeforeWindowFocusPreservesANonFirstProgrammeAndItsBitmap() {
        val home = HomeShelf()
        val originalBitmap = home.bitmap
        val originalItems = home.items
        home.pause() // Settings owns the foreground.
        home.eligible = false // Grid focus restoration may also be pending during this gap.
        home.resume(windowFocused = false) // Android resumes Home before granting window focus.
        assertEquals(originalItems, home.items)
        assertEquals(2, home.index)
        assertSame(originalBitmap, home.bitmap)
        assertTrue(home.shown)
        assertEquals(0, home.fetches)
        assertNull(home.refreshDelay)

        home.eligible = true
        home.windowFocus(true)
        val ticket = home.session.current!!
        assertEquals(1, home.fetches)
        home.complete(ticket, originalItems)
        assertEquals(2, home.index)
        assertSame(originalBitmap, home.bitmap)
        assertTrue(home.shown)
        assertFalse(home.advanceScheduled)
        assertEquals(ShelfRefreshPolicy.TTL_MS, home.refreshDelay)
    }

    @Test fun repeatedFocusCallbacksDuringAnUncachedFetchCannotQueueZeroDelayReloads() {
        val home = HomeShelf()
        home.resume(windowFocused = true)
        val ticket = home.session.current!!
        repeat(100) { home.windowFocus(true); home.runDueRefresh() }
        assertEquals(1, home.fetches)
        assertTrue(home.requests.isLoading(ticket))
        assertNull(home.refreshDelay)
        assertFalse(home.advanceScheduled)
        home.complete(ticket, home.items)
        assertEquals(ShelfRefreshPolicy.TTL_MS, home.refreshDelay)
    }

    @Test fun anOldCallbackCannotUnlockOrOverwriteTheRequestAfterFocusReturns() {
        val home = HomeShelf()
        val bitmap = home.bitmap
        home.resume(windowFocused = true)
        val old = home.session.current!!
        home.windowFocus(false)
        home.windowFocus(true)
        val latest = home.session.current!!
        home.complete(old, listOf("obsolete"))
        assertTrue(home.requests.isLoading(latest))
        assertEquals("selected", home.items[home.index])
        assertSame(bitmap, home.bitmap)
        repeat(10) { home.windowFocus(true); home.runDueRefresh() }
        assertEquals(2, home.fetches)
        assertNull(home.refreshDelay)
        home.complete(latest, listOf("new", "selected"))
        assertEquals(1, home.index)
        assertSame(bitmap, home.bitmap)
        assertEquals(ShelfRefreshPolicy.TTL_MS, home.refreshDelay)
    }

    @Test fun anIneligibleFocusedDockStillReturnsToWallpaper() {
        val home = HomeShelf()
        home.resume(windowFocused = true)
        home.eligible = false
        home.windowFocus(true)
        assertFalse(home.shown)
        assertTrue(home.items.isEmpty())
        assertNull(home.bitmap)
        assertNull(home.session.current)
        assertNull(home.refreshDelay)
    }

    @Test fun closingControlPreservesThePictureUntilTheDeferredGridFocusCompletes() {
        val home = HomeShelf()
        val bitmap = home.bitmap
        val feed = home.items
        home.resume(windowFocused = true)
        home.complete(home.session.current!!, feed)
        home.openControl()
        home.closeControlWithDeferredFocus() // Window is focused, but the old panel still owns child focus.
        assertTrue(home.focused)
        assertFalse(home.gridFocused)
        assertTrue(home.focusPending)
        assertEquals(2, home.index)
        assertSame(bitmap, home.bitmap)
        assertEquals(1, home.fetches)
        assertNull(home.refreshDelay)

        home.applyDeferredGridFocus()
        assertFalse(home.focusPending)
        assertEquals(2, home.fetches) // Both the synchronous callback and apply completion request reconciliation.
        home.complete(home.session.current!!, feed)
        assertEquals(2, home.index)
        assertSame(bitmap, home.bitmap)
        assertFalse(home.advanceScheduled)
        assertEquals(ShelfRefreshPolicy.TTL_MS, home.refreshDelay)
    }

    @Test fun closingControlThenOpeningSettingsPreservesThePictureAcrossDeferredFocusRestoration() {
        val home = HomeShelf()
        val bitmap = home.bitmap
        val feed = home.items
        home.resume(windowFocused = true)
        home.complete(home.session.current!!, feed)
        home.openControl()
        home.closeControlWithDeferredFocus()
        home.pause() // startActivity(Settings) occurs before the animation-frame focus restore.
        home.resume(windowFocused = true) // Test child-focus restoration independently of window-focus timing.
        assertTrue(home.focused)
        assertFalse(home.gridFocused)
        assertEquals(2, home.index)
        assertSame(bitmap, home.bitmap)
        assertEquals(1, home.fetches)
        home.queueResumeGridFocus()
        home.applyDeferredGridFocus()
        home.complete(home.session.current!!, feed)
        assertEquals(2, home.index)
        assertSame(bitmap, home.bitmap)
        assertTrue(home.shown)
        assertFalse(home.advanceScheduled)
        assertEquals(2, home.fetches)
    }

    @Test fun pendingFocusToAnUnsupportedOrExpandedTargetStillHidesTheShelf() {
        val home = HomeShelf()
        home.resume(windowFocused = true)
        home.openControl()
        home.dockTarget = false // The destination is unsupported or outside the first row.
        home.closeControlWithDeferredFocus()
        assertTrue(home.focused)
        assertTrue(home.focusPending)
        assertFalse(home.shown)
        assertTrue(home.items.isEmpty())
        assertNull(home.bitmap)
        assertNull(home.session.current)
    }

    @Test fun aColdWakeFeedFailureRetriesAfterTenSecondsAndReturnsToTheNormalCadenceOnSuccess() {
        val home = HomeShelf().apply { items = emptyList(); bitmap = null; shown = false }
        home.resume(windowFocused = true)
        home.complete(home.session.current!!, null) // Network is still returning from standby.
        assertFalse(home.shown)
        assertEquals(ShelfRefreshPolicy.RETRY_MS, home.refreshDelay)
        repeat(20) { home.windowFocus(true); home.runDueRefresh() }
        assertEquals(1, home.fetches)
        home.advanceTime(ShelfRefreshPolicy.RETRY_MS - 1)
        assertEquals(1, home.fetches)
        home.advanceTime(1)
        assertEquals(2, home.fetches)
        assertNull(home.refreshDelay)
        home.complete(home.session.current!!, listOf("recovered"))
        assertTrue(home.shown)
        assertEquals("recovered", home.items[home.index])
        assertEquals(ShelfRefreshPolicy.TTL_MS, home.refreshDelay)
    }

    @Test fun aSuccessfulFeedWithTemporarilyUnavailablePicturesAlsoRetriesWithoutWaitingFifteenMinutes() {
        val home = HomeShelf().apply { items = emptyList(); bitmap = null; shown = false }
        home.resume(windowFocused = true)
        home.complete(home.session.current!!, listOf("programme"), pictureAvailable = false)
        assertFalse(home.shown)
        assertEquals(ShelfRefreshPolicy.RETRY_MS, home.refreshDelay)
        home.advanceTime(ShelfRefreshPolicy.RETRY_MS)
        assertEquals(2, home.fetches)
        home.complete(home.session.current!!, home.cache!!.items, fromCache = true)
        assertTrue(home.shown)
        assertEquals("programme", home.items[home.index])
        assertNull(home.artworkFailedAt)
        assertEquals(ShelfRefreshPolicy.TTL_MS - ShelfRefreshPolicy.RETRY_MS, home.refreshDelay)
    }

    @Test fun aFeedExceptionPreservesTheSuccessfulFeedAndDisplayedBitmapWhileRetrying() {
        val home = HomeShelf()
        val bitmap = home.bitmap
        val feed = home.items
        home.resume(windowFocused = true)
        home.complete(home.session.current!!, feed)
        home.advanceTime(ShelfRefreshPolicy.TTL_MS)
        home.complete(home.session.current!!, null)
        assertSame(feed, home.cache!!.items)
        assertSame(bitmap, home.bitmap)
        assertTrue(home.shown)
        assertEquals("selected", home.items[home.index])
        assertEquals(ShelfRefreshPolicy.RETRY_MS, home.refreshDelay)
    }

    @Test fun pictureFailuresInAChangedFeedKeepThePreviousSuccessfulPictureUntilRecovery() {
        val home = HomeShelf()
        val bitmap = home.bitmap
        val previous = home.items
        home.resume(windowFocused = true)
        home.complete(home.session.current!!, listOf("new"), pictureAvailable = false)
        assertSame(previous, home.items)
        assertSame(bitmap, home.bitmap)
        assertTrue(home.shown)
        assertEquals(ShelfRefreshPolicy.RETRY_MS, home.refreshDelay)
        home.advanceTime(ShelfRefreshPolicy.RETRY_MS)
        home.complete(home.session.current!!, listOf("new"), fromCache = true)
        assertEquals(listOf("new"), home.items)
        assertNotSame(bitmap, home.bitmap)
        assertEquals(ShelfRefreshPolicy.TTL_MS - ShelfRefreshPolicy.RETRY_MS, home.refreshDelay)
    }

    @Test fun pauseFocusLossAndOverlaysCancelThePendingRetryAndRejectItsOldCompletion() {
        val stops: List<(HomeShelf) -> Unit> = listOf({ it.pause() }, { it.windowFocus(false) }, { it.openControl() })
        stops.forEach { stop ->
            val waiting = HomeShelf().apply { items = emptyList(); bitmap = null; shown = false }
            waiting.resume(windowFocused = true)
            waiting.complete(waiting.session.current!!, null)
            stop(waiting)
            waiting.advanceTime(ShelfRefreshPolicy.RETRY_MS)
            assertEquals(1, waiting.fetches)
            assertNull(waiting.refreshDelay)

            val home = HomeShelf().apply { items = emptyList(); bitmap = null; shown = false }
            home.resume(windowFocused = true)
            home.complete(home.session.current!!, listOf("programme"), pictureAvailable = false)
            home.advanceTime(ShelfRefreshPolicy.RETRY_MS)
            val old = home.session.current!!
            stop(home)
            assertNull(home.refreshDelay)
            assertNull(home.artworkFailedAt)
            assertNull(home.session.current)
            home.complete(old, listOf("obsolete"))
            home.advanceTime(ShelfRefreshPolicy.RETRY_MS)
            assertFalse(home.shown)
            assertEquals(2, home.fetches)
            assertNull(home.refreshDelay)
        }
    }

    @Test fun changingDockTargetsCancelsTheRetryAndAnOldFailureCannotUnlockTheNewRequest() {
        val home = HomeShelf().apply { items = emptyList(); bitmap = null; shown = false }
        home.resume(windowFocused = true)
        home.complete(home.session.current!!, null)
        home.advanceTime(ShelfRefreshPolicy.RETRY_MS)
        val old = home.session.current!!
        home.switchAway()
        assertNull(home.refreshDelay)
        home.dockTarget = true
        home.windowFocus(true)
        val latest = home.session.current!!
        home.complete(old, null)
        assertTrue(home.requests.isLoading(latest))
        assertNull(home.refreshDelay)
        home.complete(latest, listOf("current"))
        assertTrue(home.shown)
        assertEquals(listOf("current"), home.items)
        assertEquals(ShelfRefreshPolicy.TTL_MS, home.refreshDelay)
    }

    @Test fun localPreviewIsVisibleWhileTheOnlineRequestIsStillPendingAndSurvivesItsFailure() {
        val home = HomeShelf().apply { items = emptyList(); bitmap = null; shown = false }
        home.resume(windowFocused = true)
        val ticket = home.session.current!!
        assertTrue(home.preview(ticket, listOf("cached")))
        val previewBitmap = home.bitmap
        assertTrue(home.shown)
        assertTrue(home.requests.isLoading(ticket))
        assertNull(home.refreshDelay)
        home.complete(ticket, null)
        assertTrue(home.shown)
        assertSame(previewBitmap, home.bitmap)
        assertEquals(listOf("cached"), home.items)
        assertEquals(ShelfRefreshPolicy.RETRY_MS, home.refreshDelay)
    }

    @Test fun successfulBackgroundRefreshReplacesThePreviewAndReturnsToTheOnlineTtl() {
        val home = HomeShelf().apply { items = emptyList(); bitmap = null; shown = false }
        home.resume(windowFocused = true)
        val ticket = home.session.current!!
        home.preview(ticket, listOf("cached"))
        val previous = home.bitmap
        home.complete(ticket, listOf("online"))
        assertEquals(listOf("online"), home.items)
        assertNotSame(previous, home.bitmap)
        assertEquals(ShelfRefreshPolicy.TTL_MS, home.refreshDelay)
    }

    @Test fun anEmptyBackgroundResponseDoesNotBlankTheAlreadyDisplayedPreview() {
        val home = HomeShelf().apply { items = emptyList(); bitmap = null; shown = false }
        home.resume(windowFocused = true)
        val ticket = home.session.current!!
        home.preview(ticket, listOf("cached"))
        val previous = home.bitmap
        home.complete(ticket, emptyList())
        assertSame(previous, home.bitmap)
        assertTrue(home.shown)
        assertEquals(listOf("cached"), home.items)
        assertEquals(ShelfRefreshPolicy.RETRY_MS, home.refreshDelay)
    }

    @Test fun aPreviewAndOnlineCompletionFromBeforePauseCannotPublishIntoTheNewSession() {
        val home = HomeShelf().apply { items = emptyList(); bitmap = null; shown = false }
        home.resume(windowFocused = true)
        val old = home.session.current!!
        home.pause()
        home.resume(windowFocused = true)
        val current = home.session.current!!
        assertFalse(home.preview(old, listOf("obsolete")))
        home.complete(old, listOf("obsolete"))
        assertFalse(home.shown)
        assertTrue(home.requests.isLoading(current))
        assertTrue(home.preview(current, listOf("current")))
        assertEquals(listOf("current"), home.items)
    }
}
