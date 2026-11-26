package com.example.shindenpocket.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShindenApiTest {

    @Test
    fun newAnimeListResponseIsParsedAtRoot() {
        val (anime, count) = ShindenApi.parseAnimePage(
            """
            {
              "count": 2,
              "items": [{
                "titleId": 7829,
                "coverId": 373555,
                "title": "07-Ghost",
                "watchedEpisodesCnt": 25,
                "episodes": 25,
                "isFavourite": 1,
                "watchStatus": "completed",
                "titleStatus": "Finished Airing",
                "summaryRatingTotal": 7.32,
                "rateTotal": 8
              }]
            }
            """.trimIndent()
        )

        assertEquals(2, count)
        assertEquals(1, anime.size)
        assertEquals(7829, anime.single().id)
        assertEquals("07-Ghost", anime.single().title)
        assertEquals(25, anime.single().watched)
        assertTrue(anime.single().favorite)
        assertEquals(8, anime.single().rateTotal)
    }

    @Test
    fun newEpisodeCatalogResponseIsParsedAtRoot() {
        val episodes = ShindenEpisodesApi.parseCatalog(
            """
            {"items":[{
              "episodeId":67416,
              "episodeNo":1,
              "titlePL":{"title":"Przyszłość bolesnych myśli"},
              "titleEN":{"title":"The Future Of Painful Thoughts Is"},
              "watched":{"episodeId":67416,"viewCnt":2}
            }]}
            """.trimIndent()
        )

        assertEquals(1, episodes.size)
        assertEquals(67416, episodes.single().episodeId)
        assertEquals("1", episodes.single().number)
        assertEquals("Przyszłość bolesnych myśli", episodes.single().title)
        assertTrue(episodes.single().watched)
    }

    @Test
    fun legacyAnimeListEnvelopeRemainsSupported() {
        val (anime, count) = ShindenApi.parseAnimePage(
            """{"success":true,"result":{"count":0,"items":[]}}"""
        )

        assertEquals(0, count)
        assertTrue(anime.isEmpty())
    }
}
