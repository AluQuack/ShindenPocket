package com.example.shindenpocket.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentSeasonApiTest {

    private val html = """
        <html><body>
        <ol class="sh-tagi-wyniki">
            <li class="sh-tagi-wynik">
                <a href="/series/70907-kusuriya-no-hitorigoto-3rd-season" aria-labelledby="sh-karta-1">
                    <span class="sh-okl"><img src="/res/images/280x400/441947.webp?v=1792162739" alt=""></span>
                    <span class="sh-tagi-nazwa" id="sh-karta-1">Kusuriya no Hitorigoto 3rd Season</span>
                    <span class="sh-tagi-meta"><span class="sh-tagi-typ">TV</span></span>
                    <span class="sh-karta-meta">TV &#183; 12 odc. &#183; 24 min &#183; OLM</span>
                </a>
                <a class="sh-karta-lista" href="/main/login" data-title-id="70907" data-episodes="12">Dodaj do listy</a>
            </li>
            <li class="sh-tagi-wynik">
                <a href="/series/70902-black-clover-2nd-season">
                    <span class="sh-okl"><img src="/res/images/280x400/430000.webp?v=1" alt=""></span>
                    <span class="sh-tagi-nazwa">Black Clover 2nd Season</span>
                    <span class="sh-karta-meta">TV &#183; 24 odc. &#183; 24 min</span>
                </a>
            </li>
            <li class="sh-tagi-wynik">
                <a href="/series/73355-tempal-item-no-chikara">
                    <span class="sh-tagi-nazwa">Tempal: Item no Chikara</span>
                </a>
            </li>
        </ol>
        <a href="/series/season/2026/autumn?page=2">Następna</a>
        </body></html>
    """.trimIndent()

    @Test
    fun parsesNewSeasonLayoutWithEpisodes() {
        val result = CurrentSeasonApi.parseHtml(html)
        assertEquals(3, result.size)

        val first = result.first { it.id == 70907 }
        assertEquals("Kusuriya no Hitorigoto 3rd Season", first.title)
        assertEquals("https://shinden.pl/series/70907-kusuriya-no-hitorigoto-3rd-season",
            first.detailUrl)
        assertEquals("https://shinden.pl/res/images/280x400/441947.webp?v=1792162739",
            first.coverUrl)
        assertEquals(12, first.episodes)

        val second = result.first { it.id == 70902 }
        assertEquals("Black Clover 2nd Season", second.title)
        // Bez `data-episodes` liczba odcinków pochodzi z tekstu karty.
        assertEquals(24, second.episodes)
    }

    @Test
    fun detectsNextSeasonPage() {
        assertTrue(CurrentSeasonApi.parsePage(html, 1).hasNextPage)
        assertFalse(CurrentSeasonApi.parsePage(html, 2).hasNextPage)
    }

    @Test
    fun acceptsCurrentAndCanonicalSeasonPaths() {
        assertTrue(CurrentSeasonApi.isSeasonPath("/series/season/current"))
        // `current` może przekierować na konkretny rok i porę sezonu.
        assertTrue(CurrentSeasonApi.isSeasonPath("/series/season/2026/autumn"))
        assertTrue(CurrentSeasonApi.isSeasonPath("/series/season/2026/autumn/"))
        assertFalse(CurrentSeasonApi.isSeasonPath("/series/season"))
        assertFalse(CurrentSeasonApi.isSeasonPath("/series/70907-kusuriya-no-hitorigoto-3rd-season"))
        assertFalse(CurrentSeasonApi.isSeasonPath("/manga/season/2026/autumn"))
    }

    @Test
    fun recentPeriodsStartAtCurrentSeasonAndWalkBackQuarters() {
        val periods = CurrentSeasonApi.recentPeriods(8)
        assertEquals(8, periods.size)
        assertEquals(8, periods.distinct().size)
        val current = periods.first()
        val previous = periods[1]
        val expectedPreviousYear = if (current.slug == "winter") current.year - 1 else current.year
        assertEquals(expectedPreviousYear, previous.year)
        assertTrue(periods.all { it.slug in listOf("winter", "spring", "summer", "autumn") })
    }

    @Test
    fun missingEpisodeInfoIsNull() {
        val third = CurrentSeasonApi.parseHtml(html).first { it.id == 73355 }
        assertEquals("Tempal: Item no Chikara", third.title)
        assertNull(third.episodes)
        assertNull(third.coverUrl)
    }
}
