package com.voxengine.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppThemeStyleTest {
    @Test
    fun persistedValuesRemainStable() {
        assertEquals("liquid_glass", AppThemeStyle.LIQUID_GLASS.storageValue)
        assertEquals("classic", AppThemeStyle.CLASSIC.storageValue)
    }

    @Test
    fun everyStyleRoundTripsWithoutAmbiguousValues() {
        assertEquals(AppThemeStyle.entries.size, AppThemeStyle.entries.map { it.storageValue }.toSet().size)
        AppThemeStyle.entries.forEach { style ->
            assertEquals(style, AppThemeStyle.fromStorage(style.storageValue))
        }
    }

    @Test
    fun existingInstallationsWithoutAThemeUseLiquidGlass() {
        assertEquals(AppThemeStyle.LIQUID_GLASS, AppThemeStyle.fromStorage(null))
    }

    @Test
    fun invalidOrFutureValuesFallBackSafely() {
        listOf("", " ", "unknown", "CLASSIC", " classic ", "future_theme").forEach { value ->
            assertEquals(AppThemeStyle.LIQUID_GLASS, AppThemeStyle.fromStorage(value))
        }
    }
}
