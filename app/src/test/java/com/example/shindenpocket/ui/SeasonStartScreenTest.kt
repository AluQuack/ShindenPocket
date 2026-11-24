package com.example.shindenpocket.ui

import com.example.shindenpocket.data.SeasonPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonStartScreenTest {
    @Test
    fun shiftSeasonPeriod_crossesYearBoundaries() {
        assertEquals(
            SeasonPeriod(2025, "autumn"),
            shiftSeasonPeriod(SeasonPeriod(2026, "winter"), -1)
        )
        assertEquals(
            SeasonPeriod(2027, "winter"),
            shiftSeasonPeriod(SeasonPeriod(2026, "autumn"), 1)
        )
    }

    @Test
    fun forwardNavigation_isEnabledBeforeLatestPeriod() {
        assertTrue(
            canNavigateToNextSeason(
                displayedPeriod = SeasonPeriod(2026, "autumn"),
                latestPeriod = SeasonPeriod(2027, "winter")
            )
        )
    }

    @Test
    fun forwardNavigation_isDisabledAtLatestPeriod() {
        assertFalse(
            canNavigateToNextSeason(
                displayedPeriod = SeasonPeriod(2027, "winter"),
                latestPeriod = SeasonPeriod(2027, "winter")
            )
        )
    }
}
