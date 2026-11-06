package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Test

class PerformancePolicyTest {
    @Test fun smoothProfileDoesNotStartVideoOrSceneBasedGlass() {
        val budget = PerformancePolicy.effects(PerformancePolicy.SMOOTH)
        assertFalse(budget.dynamicWallpaper)
        assertFalse(budget.glass)
        assertFalse(budget.extremeGlass)
        assertEquals(0f, budget.tiltDegrees, 0f)
        // Focus feedback remains visible even with the inexpensive rendering path.
        assertTrue(budget.dockScale > 1f && budget.gridScale > 1f && budget.liftDp > 0f)
    }

    @Test fun budgetsKeepAProgressiveCostAndBoundedRemoteFeedback() {
        val budgets = PerformancePolicy.profiles.map(PerformancePolicy::effects)
        assertTrue(budgets.zipWithNext().all { (a, b) -> a.dockScale <= b.dockScale && a.enterMs <= b.enterMs })
        assertTrue(budgets.all { it.gridScale <= it.dockScale && it.exitMs <= it.enterMs && it.enterMs <= 250 })
        assertTrue(budgets.all { it.liftDp in 1f..16f && it.samplePollMs >= 80 })
        assertFalse(budgets[1].extremeGlass)
        assertTrue(budgets[2].extremeGlass)
    }

    @Test fun invalidSavedProfileUsesBalancedBudget() {
        assertEquals(PerformancePolicy.effects(PerformancePolicy.BALANCED), PerformancePolicy.effects("old-or-corrupt"))
    }

    @Test fun hardwareBudgetUsesActualRamAndCores() {
        val gib = 1024L * 1024 * 1024
        assertTrue(PerformancePolicy.constrained(true, 4 * gib, 8))
        assertTrue(PerformancePolicy.constrained(false, gib, 8))
        assertTrue(PerformancePolicy.constrained(false, 4 * gib, 2))
        assertFalse(PerformancePolicy.constrained(false, 4 * gib, 4))
    }
}
