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

    @Test
    fun discoveredLatestPeriod_doesNotRegressWhenReturningThroughOlderSeasons() {
        assertEquals(
            SeasonPeriod(2028, "autumn"),
            laterSeasonPeriod(
                knownPeriod = SeasonPeriod(2028, "autumn"),
                discoveredPeriod = SeasonPeriod(2026, "autumn")
            )
        )
    }

    @Test
    fun catalogScrollsToEndOnlyWhenNextSeasonIsShorterOrEmpty() {
        assertTrue(shouldScrollCatalogToEnd(previousCount = 12, newCount = 5))
        assertTrue(shouldScrollCatalogToEnd(previousCount = 12, newCount = 0))
        assertFalse(shouldScrollCatalogToEnd(previousCount = 12, newCount = 12))
        assertFalse(shouldScrollCatalogToEnd(previousCount = 12, newCount = 20))
    }

    @Test
    fun emptyStateStaysVisibleWhileLoadingAnotherSelectedSeason() {
        assertTrue(
            shouldShowSeasonEmptyState(
                catalogLoading = true,
                selectedPeriod = SeasonPeriod(2028, "summer"),
                listedSeasonEmpty = true
            )
        )
        assertFalse(
            shouldShowSeasonEmptyState(
                catalogLoading = true,
                selectedPeriod = null,
                listedSeasonEmpty = true
            )
        )
    }
}
