package com.example.shindenpocket.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeNotificationTest {

    @Test
    fun `formatContent formats single episode with title and language`() {
        val episode = TrackedEpisode(
            episodeId = 501,
            number = "12",
            title = "Początek nowej wyprawy",
            watched = false,
            hasOnlineVersion = true,
            airDate = "2026-11-11",
            languageLabels = listOf("PL", "EN"),
            detailUrl = "/e/501"
        )

        val content = EpisodeNotification.formatContent(listOf(episode))

        assertEquals("NOWY ODCINEK", content.badgeText)
        assertEquals("PL/EN", content.metaText)
        assertEquals("Odc. 12 • Początek nowej wyprawy", content.collapsedSubtitle)
        assertEquals("Odcinek 12", content.episodePillText)
        assertEquals("Początek nowej wyprawy", content.expandedEpisodeTitle)
        assertEquals("Premiera: 2026-11-11", content.hintText)
        assertEquals("▶ Oglądaj", content.watchButtonText)
        assertEquals("▶ Oglądaj odc. 12", content.bigWatchButtonText)
        assertEquals(501, content.targetEpisodeId)
    }

    @Test
    fun `formatContent cleans redundant episode title`() {
        val episode = TrackedEpisode(
            episodeId = -1,
            number = "7",
            title = "Odcinek 7",
            watched = false,
            hasOnlineVersion = true,
            airDate = "",
            languageLabels = emptyList(),
            detailUrl = ""
        )

        val content = EpisodeNotification.formatContent(listOf(episode), isPreview = true)

        assertEquals("NOWY ODCINEK", content.badgeText)
        assertEquals("", content.metaText)
        assertEquals("Odcinek 7 • Dostępny online", content.collapsedSubtitle)
        assertEquals("Odcinek 7", content.episodePillText)
        assertEquals("Nowa wersja online gotowa do odtworzenia", content.expandedEpisodeTitle)
        assertEquals("Podgląd powiadomienia", content.hintText)
        assertNull(content.targetEpisodeId)
    }

    @Test
    fun `formatContent formats multiple episodes and picks first unwatched target`() {
        val episodes = listOf(
            TrackedEpisode(
                episodeId = 101,
                number = "4",
                title = "Cisza przed burzą",
                watched = false,
                hasOnlineVersion = true,
                airDate = "2026-11-10",
                languageLabels = listOf("PL"),
                detailUrl = ""
            ),
            TrackedEpisode(
                episodeId = 102,
                number = "5",
                title = "Przebudzenie",
                watched = false,
                hasOnlineVersion = true,
                airDate = "2026-11-11",
                languageLabels = listOf("PL"),
                detailUrl = ""
            )
        )

        val content = EpisodeNotification.formatContent(episodes)

        assertEquals("NOWE ODCINKI (2)", content.badgeText)
        assertEquals("Odcinki: 4, 5", content.collapsedSubtitle)
        assertEquals("Odc. 4, 5", content.episodePillText)
        assertEquals("Od odc. 4: Cisza przed burzą", content.expandedEpisodeTitle)
        assertEquals("▶ Oglądaj odc. 4", content.bigWatchButtonText)
        assertEquals(101, content.targetEpisodeId)
    }
}
