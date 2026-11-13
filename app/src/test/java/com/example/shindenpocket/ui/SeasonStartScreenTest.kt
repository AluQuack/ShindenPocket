package com.example.shindenpocket.ui

import com.example.shindenpocket.data.SeasonPeriod
import org.junit.Assert.assertEquals
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
}
