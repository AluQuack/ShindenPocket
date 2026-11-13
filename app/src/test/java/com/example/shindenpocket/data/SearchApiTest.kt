package com.example.shindenpocket.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchApiTest {

    @Test
    fun buildUrl_encodesQueryFiltersAndPage() {
        val url = SearchApi.buildUrl(
            query = "atak tytanów",
            filters = SearchFilters(
                sort = SearchSort.RATING,
                types = setOf(SearchType.TV),
                statuses = setOf(SearchStatus.FINISHED),
                yearFrom = 2013,
                yearTo = 2014,
                exactYear = 2013,
                season = SearchSeason.SPRING,
                tags = listOf(
                    SearchTagFilter(5, "Akcja"),
                    SearchTagFilter(8, "Dramat", SearchTagMode.EXCLUDE)
                ),
                subtitleGroupId = 12,
                onlineOnly = true,
                ratingFrom = 7,
                ratingTo = 10,
                episodesFrom = 1,
                episodesTo = 25,
                lengthFrom = 20,
                lengthTo = 30
            ),
            page = 2
        )
        assertTrue(url.startsWith("https://shinden.pl/series?"))
        assertTrue(url.contains("search=atak+tytan%C3%B3w"))
        assertTrue(url.contains("page=2"))
        assertTrue(url.contains("sort=ocena"))
        assertTrue(url.contains("series_type%5B%5D=TV"))
        assertTrue(url.contains("series_status%5B%5D=Finished+Airing"))
        assertTrue(url.contains("year_from=2013"))
        assertTrue(url.contains("year_to=2014"))
        assertTrue(url.contains("year=2013"))
        assertTrue(url.contains("season=spring"))
        assertTrue(url.contains("genres=i5%3Be8"))
        assertTrue(url.contains("grupa=12"))
        assertTrue(url.contains("one_online=true"))
        assertTrue(url.contains("rating_from=7"))
        assertTrue(url.contains("rating_to=10"))
        assertTrue(url.contains("series_number_from=1"))
        assertTrue(url.contains("series_number_to=25"))
        assertTrue(url.contains("length_from=20"))
        assertTrue(url.contains("length_to=30"))
    }

    @Test
    fun buildUrl_omitsPageOneAndEmptyFilters() {
        val url = SearchApi.buildUrl("naruto", SearchFilters(), 1)
        assertTrue(url.contains("search=naruto"))
        assertTrue(!url.contains("page="))
        assertTrue(!url.contains("sort="))
        assertTrue(!url.contains("series_type"))
    }

    @Test
    fun validate_rejectsSeasonWithoutExactYearAndReversedRanges() {
        assertEquals(
            "Wybierz rok dla wybranego sezonu.",
            SearchApi.validate(SearchFilters(season = SearchSeason.WINTER))
        )
        assertEquals(
            "Minimalna ocena nie może być wyższa niż maksymalna.",
            SearchApi.validate(SearchFilters(ratingFrom = 9, ratingTo = 4))
        )
    }

    @Test
    fun parseSubtitleGroups_usesLiveSelectValues() {
        val groups = SearchApi.parseSubtitleGroups(
            """<select name="grupa"><option value="">Wszystkie</option>
                <option value="12">Grupa A</option><option value="31">Grupa B</option></select>"""
        )
        assertEquals(listOf(SubtitleGroup(12, "Grupa A"), SubtitleGroup(31, "Grupa B")), groups)
    }

    @Test
    fun parseSearchPage_extractsCardsAndPagination() {
        val html = """
            <html><body>
            <ul class="div-row">
              <li class="cover-col"><a href="/res/images/genuine/186153.jpg">&nbsp;</a></li>
              <li class="desc-col">
                <h3><a href="/series/44471-boruto-naruto-the-movie"><em>Boruto</em>: <em>Naruto</em> the Movie</a></h3>
                <ul class="tags">
                  <li><a href="/genre/5-action">Akcja</a></li>
                  <li><a href="/genre/8-drama">Dramat</a></li>
                </ul>
              </li>
              <li class="title-kind-col">Movie</li>
              <li class="episodes-col" title="1 x 95min">1 </li>
              <li class="ratings-col"><div class="rating rating-total"><span><em>Ogolna: </em>7,57</span></div></li>
              <li class="title-status-col">Zakonczone</li>
            </ul>
            <ul class="div-row">
              <li class="cover-col"><a href="/res/images/225x350/12345.jpg">&nbsp;</a></li>
              <li class="desc-col">
                <h3><a href="/series/12345-naruto">Naruto</a></h3>
                <ul class="tags"></ul>
              </li>
              <li class="title-kind-col">TV</li>
              <li class="episodes-col">220</li>
              <li class="ratings-col"><div class="rating rating-total"><span>Ogolna: 9.18</span></div></li>
              <li class="title-status-col">Emitowane</li>
            </ul>
            <ul class="div-row">
              <li class="desc-col"><h3><a href="/manga/9999-naruto">Manga</a></h3></li>
            </ul>
            <a href="/series?q=naruto&amp;r307=1&amp;page=2" rel="next">2</a>
            <a href="/series?q=naruto&amp;r307=1&amp;page=7" rel="last">7</a>
            </body></html>
        """.trimIndent()

        val (anime, maxPage) = SearchApi.parseSearchPage(html, "https://shinden.pl/series")

        assertEquals(2, anime.size)
        assertEquals(7, maxPage)

        val boruto = anime[0]
        assertEquals(44471, boruto.id)
        assertEquals("Boruto: Naruto the Movie", boruto.title)
        assertEquals("https://shinden.pl/series/44471-boruto-naruto-the-movie", boruto.detailUrl)
        assertEquals(186153, boruto.coverId)
        assertEquals(listOf("Akcja", "Dramat"), boruto.tags)
        assertEquals("Movie", boruto.kind)
        assertEquals(1, boruto.episodes)
        assertEquals(7.57f, boruto.rating ?: 0f, 0.001f)
        assertEquals("Zakonczone", boruto.status)

        val naruto = anime[1]
        assertEquals(12345, naruto.id)
        assertEquals("Naruto", naruto.title)
        assertEquals(12345, naruto.coverId)
        assertEquals("TV", naruto.kind)
        assertEquals(220, naruto.episodes)
        assertEquals(9.18f, naruto.rating ?: 0f, 0.001f)
        assertEquals("Emitowane", naruto.status)
        assertTrue(naruto.tags.isEmpty())
    }

    @Test
    fun parseSearchPage_handlesCardWithoutCoverOrMetadata() {
        val html = """
            <html><body>
            <ul class="div-row">
              <li class="desc-col"><h3><a href="/series/777-bez-okladki">Bez okładki</a></h3></li>
            </ul>
            </body></html>
        """.trimIndent()

        val (anime, maxPage) = SearchApi.parseSearchPage(html, "https://shinden.pl/series")

        assertEquals(1, anime.size)
        assertEquals(1, maxPage)
        assertNull(anime[0].coverId)
        assertNull(anime[0].rating)
        assertNull(anime[0].kind)
        assertNull(anime[0].episodes)
    }
}
