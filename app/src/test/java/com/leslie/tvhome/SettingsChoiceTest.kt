package com.leslie.tvhome

import org.junit.Assert.*
import org.junit.Test

class SettingsChoiceTest {
    @Test fun opensAtTheSavedChoiceAndStagesChangesWithoutSaving() {
        var saved = "balanced"
        val choice = SettingsChoice(listOf("smooth", "balanced", "full"), saved)
        assertEquals(1, choice.initialIndex)
        choice.select(2)
        assertEquals("full", choice.value)
        assertEquals("balanced", saved) // Back discards the local transaction.
        saved = choice.value // Explicit Apply is the only write.
        assertEquals("full", saved)
    }

    @Test fun invalidSavedChoiceAndOutOfRangeFocusAreBounded() {
        val choice = SettingsChoice(listOf(8, 15, 30, 60), 99)
        assertEquals(0, choice.initialIndex)
        choice.select(-1)
        choice.select(999)
        assertEquals(8, choice.value)
        choice.select(2)
        assertEquals(30, choice.value)
    }

    @Test fun restoreAllowlistKeepsContentQualityAndAuthorization() {
        val preserve = setOf("order", "hidden", "keep", "background", "wall_quality", "aerial_quality",
            "wall_minutes", "frame_screensaver", "system_screensaver", "saver_mode", "shelf_custom_url",
            "shelf_custom_pkg", "adb_ok", "guard", "folders", "wallpaper_video_revision")
        assertTrue(SettingsChoice.appearanceKeys.intersect(preserve).isEmpty())
        assertTrue(SettingsChoice.appearanceKeys.containsAll(setOf("performance_profile", "dock_columns", "focus_style", "shelf_rotation")))
    }

    @Test fun previewHonorsTheEffectBudgetAndFocusChoice() {
        val smooth = SettingPreviewPolicy.sample("smooth", true)
        val balanced = SettingPreviewPolicy.sample("balanced", true)
        val full = SettingPreviewPolicy.sample("full", true)
        assertFalse(smooth.glass)
        assertEquals(0f, smooth.tiltDegrees, 0f)
        assertFalse(balanced.extremeGlass)
        assertTrue(full.extremeGlass)
        assertTrue(full.scale > balanced.scale)
        assertTrue(full.liftDp > balanced.liftDp && smooth.liftDp > 0f)
        assertEquals(0f, SettingPreviewPolicy.sample("full", false).tiltDegrees, 0f)
    }
}
