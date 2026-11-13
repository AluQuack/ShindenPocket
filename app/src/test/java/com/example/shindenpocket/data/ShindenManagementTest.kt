package com.example.shindenpocket.data

import com.example.shindenpocket.ui.libraryStatusLabel
import com.example.shindenpocket.ui.matchesLibrarySubTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShindenManagementTest {

    @Test
    fun watchStatusOptionsMapAllSevenPolishLabelsAndApiCodes() {
        assertEquals(WatchStatusOption.NONE, WatchStatusOption.fromCode(null))
        assertEquals(WatchStatusOption.NONE, WatchStatusOption.fromCode(""))
        assertEquals("Bez statusu", WatchStatusOption.NONE.label)
        assertNull(WatchStatusOption.NONE.apiValue)

        assertEquals(WatchStatusOption.IN_PROGRESS, WatchStatusOption.fromCode("in progress"))
        assertEquals("Oglądam", WatchStatusOption.IN_PROGRESS.label)
        assertEquals("in progress", WatchStatusOption.IN_PROGRESS.apiValue)

        assertEquals(WatchStatusOption.COMPLETED, WatchStatusOption.fromCode("completed"))
        assertEquals("Obejrzane", WatchStatusOption.COMPLETED.label)
        assertEquals("completed", WatchStatusOption.COMPLETED.apiValue)

        assertEquals(WatchStatusOption.PLANNED, WatchStatusOption.fromCode("plan"))
        assertEquals(WatchStatusOption.PLANNED, WatchStatusOption.fromCode("planned"))
        assertEquals("Planuję", WatchStatusOption.PLANNED.label)
        assertEquals("plan", WatchStatusOption.PLANNED.apiValue)

        assertEquals(WatchStatusOption.ON_HOLD, WatchStatusOption.fromCode("hold"))
        assertEquals(WatchStatusOption.ON_HOLD, WatchStatusOption.fromCode("on-hold"))
        assertEquals("Wstrzymane", WatchStatusOption.ON_HOLD.label)
        assertEquals("hold", WatchStatusOption.ON_HOLD.apiValue)

        assertEquals(WatchStatusOption.DROPPED, WatchStatusOption.fromCode("dropped"))
        assertEquals("Porzucone", WatchStatusOption.DROPPED.label)
        assertEquals("dropped", WatchStatusOption.DROPPED.apiValue)

        assertEquals(WatchStatusOption.SKIP, WatchStatusOption.fromCode("skip"))
        assertEquals("Pomijam", WatchStatusOption.SKIP.label)
        assertEquals("skip", WatchStatusOption.SKIP.apiValue)
    }

    @Test
    fun ratingScaleComputesWeightedSuggestionAndPolishLabels() {
        assertEquals("Arcydzieło", ShindenRatingScale.labelFor(10))
        assertEquals("Beznadziejne", ShindenRatingScale.labelFor(1))
        assertNull(ShindenRatingScale.labelFor(null))

        // Fabuła ma wagę 2, pozostałe kategorie wagę 1: (2*9 + 8 + 7 + 8) / 5 = 41 / 5 = 8.2 -> 8
        assertEquals(
            8,
            ShindenRatingScale.suggestedTotal(
                story = 9,
                graphics = 8,
                music = 7,
                characters = 8
            )
        )
        // Gdy podano tylko wybrane oceny cząstkowe: (2*10 + 7) / 3 = 9
        assertEquals(
            9,
            ShindenRatingScale.suggestedTotal(
                story = 10,
                graphics = null,
                music = 7,
                characters = null
            )
        )
        assertNull(ShindenRatingScale.suggestedTotal(null, null, null, null))
    }

    @Test
    fun parseCommentsHtmlExtractsThreadsRepliesAndMetadata() {
        val html = """
            <div class="sh-c-list" data-more="1" data-last="1001">
              <article class="sh-c-thread" data-id="1005">
                <div class="sh-c" id="comment-1005" data-id="1005" data-author="42" data-created="2026-11-10T18:30:00+01:00" data-nick="TesterPL" data-likes="3">
                  <div class="sh-c-avatar"><img src="/res/avatars/36x48/42.jpg" alt="" /></div>
                  <div class="sh-c-main">
                    <header class="sh-c-head">
                      <span class="sh-c-rank">Bywalec</span>
                      <span class="sh-c-time"><time datetime="2026-11-10T18:30:00+01:00">10.11.2026, 18:30</time></span>
                      <span class="sh-c-edited">edytowany</span>
                    </header>
                    <div class="sh-c-body">Świetna fabuła!<br />Polecam każdemu.</div>
                  </div>
                </div>
                <div class="sh-c-replies">
                  <div class="sh-c sh-c-reply" id="comment-1006" data-id="1006" data-author="77" data-created="2026-11-10T19:00:00+01:00" data-nick="InnyWidz" data-likes="1">
                    <div class="sh-c-main">
                      <header class="sh-c-head">
                        <span class="sh-c-time"><time datetime="2026-11-10T19:00:00+01:00">10.11.2026, 19:00</time></span>
                      </header>
                      <div class="sh-c-body">Zgadzam się w 100%.</div>
                    </div>
                  </div>
                </div>
              </article>
            </div>
        """.trimIndent()

        val page = ShindenManagementApi.parseCommentsHtml(html, animeId = 11)
        assertTrue(page.hasMore)
        assertEquals(1001L, page.lastId)
        assertEquals(2, page.comments.size)

        val root = page.comments[0]
        assertEquals(1005L, root.id)
        assertEquals(11, root.animeId)
        assertEquals(42, root.authorId)
        assertEquals("TesterPL", root.authorName)
        assertEquals("https://shinden.pl/res/avatars/36x48/42.jpg", root.avatarUrl)
        assertEquals("Bywalec", root.rank)
        assertEquals("10.11.2026, 18:30", root.createdAt)
        assertTrue(root.edited)
        assertEquals(3, root.likes)
        assertNull(root.parentId)
        assertEquals("Świetna fabuła!\nPolecam każdemu.", root.body)
        assertTrue(root.isOwnedBy(userId = 42, username = "InnyNick"))
        assertTrue(root.isOwnedBy(userId = null, username = "testerpl"))
        assertFalse(root.isOwnedBy(userId = 99, username = "KtośInny"))

        val reply = page.comments[1]
        assertEquals(1006L, reply.id)
        assertEquals(1005L, reply.parentId)
        assertEquals("InnyWidz", reply.authorName)
        assertEquals("Zgadzam się w 100%.", reply.body)
    }

    @Test
    fun libraryEntryOverridesSyncStatusFavoritesRatingsAndRemoval() {
        val base = Anime(
            id = 11,
            coverId = 441947,
            title = "Naruto",
            watched = 50,
            total = 220,
            favorite = false,
            watchStatus = "plan",
            status = "Finished Airing",
            description = "Opis",
            rating = "8.21"
        )
        assertFalse(base.hasUserRating)
        assertTrue(base.isInUserLibrary)

        // Aktualizacja wpisu (status, ulubione oraz oceny cząstkowe i ogólna)
        val updated = base.copy(
            watchStatus = "completed",
            watched = 220,
            favorite = true,
            rateTotal = 9,
            rateStory = 10,
            rateGraphic = 8,
            rateMusic = 9,
            rateCharacters = 9
        )
        assertTrue(updated.hasUserRating)
        assertTrue(updated.isInUserLibrary)

        val merged = OfflineLibraryStore.mergeAnimeListWithOverrides(
            listOf(base),
            mapOf(11 to updated)
        )
        assertEquals(1, merged.size)
        assertEquals("completed", merged[0].watchStatus)
        assertTrue(merged[0].favorite)
        assertEquals(9, merged[0].rateTotal)
        assertEquals(10, merged[0].rateStory)
        assertEquals(8, merged[0].rateGraphic)
        assertEquals(9, merged[0].rateMusic)
        assertEquals(9, merged[0].rateCharacters)

        
        val newEntry = Anime(
            id = 70907,
            coverId = 441948,
            title = "Kusuriya no Hitorigoto 3rd Season",
            watched = 0,
            total = 12,
            favorite = false,
            watchStatus = "skip",
            status = "Currently Airing",
            description = "",
            rating = "9.19"
        )
        val withNew = OfflineLibraryStore.mergeAnimeListWithOverrides(
            merged,
            mapOf(11 to updated, 70907 to newEntry)
        )
        assertEquals(2, withNew.size)
        assertEquals(11, withNew[0].id)
        assertEquals(70907, withNew[1].id)
        assertEquals("skip", withNew[1].watchStatus)

        // Usunięcie z listy (Bez statusu, brak ulubionych i brak ocen) usuwa pozycję z widoku biblioteki
        val removedOverride = updated.copy(
            watchStatus = "",
            favorite = false,
            rateTotal = null,
            rateStory = null,
            rateGraphic = null,
            rateMusic = null,
            rateCharacters = null
        )
        assertFalse(removedOverride.isInUserLibrary)
        val afterRemoval = OfflineLibraryStore.mergeAnimeListWithOverrides(
            listOf(updated),
            mapOf(11 to removedOverride)
        )
        assertTrue(afterRemoval.isEmpty())
    }

    @Test
    fun combineCommentsHandlesAddEditAndDeleteWithPersistence() {
        val remote = listOf(
            AnimeComment(
                id = 500L,
                animeId = 11,
                authorId = 99,
                authorName = "Ktoś",
                avatarUrl = null,
                rank = null,
                body = "Pierwszy komentarz",
                createdAt = "10.11.2026, 12:00",
                edited = false,
                likes = 2,
                parentId = null
            )
        )
        val ownEdited = AnimeComment(
            id = -10L,
            animeId = 11,
            authorId = 7,
            authorName = "Ja",
            avatarUrl = null,
            rank = "Autor",
            body = "Mój zaktualizowany komentarz",
            createdAt = "12.11.2026, 15:00",
            edited = true,
            likes = 0,
            parentId = null,
            isLocalOnly = true
        )

        val combined = OfflineLibraryStore.combineComments(
            remoteOrCached = remote,
            own = listOf(ownEdited),
            deletedIds = emptySet()
        )
        assertEquals(2, combined.size)
        assertEquals("Mój zaktualizowany komentarz", combined.first().body)

        val afterDelete = OfflineLibraryStore.combineComments(
            remoteOrCached = remote,
            own = emptyList(),
            deletedIds = setOf(500L)
        )
        assertTrue(afterDelete.isEmpty())
    }

    @Test
    fun reconcileRemoteWithOverridesRespectsServerUpdatesAndPreservesPendingLocalEdits() {
        val staleLocalSynced = Anime(
            id = 11,
            coverId = 441947,
            title = "Naruto",
            watched = 10,
            total = 220,
            favorite = false,
            watchStatus = "in progress",
            status = "Finished Airing",
            description = "Opis",
            rating = "8.21",
            rateTotal = 7
        )
        // Użytkownik zmienił na stronie Shinden.pl status na "completed", dodał do ulubionych i ocenił na 9
        val freshFromServer = staleLocalSynced.copy(
            watched = 220,
            favorite = true,
            watchStatus = "completed",
            rateTotal = 9,
            rateStory = 9
        )
        // Druga seria ma lokalną, jeszcze niezsynchronizowaną zmianę (pendingSync = true)
        val remoteSecond = Anime(
            id = 25,
            coverId = 100,
            title = "Bleach",
            watched = 5,
            total = 366,
            favorite = false,
            watchStatus = "plan",
            status = "Finished Airing",
            description = "",
            rating = "8.00",
            rateTotal = null
        )
        val pendingLocalSecond = remoteSecond.copy(
            watchStatus = "hold",
            favorite = true,
            rateTotal = 8
        )
        // Trzecia seria ma wyczyszczony status ("Bez statusu"), ale ma ocenę 10 (lista.shinden.pl jej nie zwraca)
        val ratedWithoutStatus = Anime(
            id = 99,
            coverId = 200,
            title = "One Piece",
            watched = 0,
            total = 1100,
            favorite = false,
            watchStatus = "",
            status = "Currently Airing",
            description = "",
            rating = "9.00",
            rateTotal = 10
        )

        val (merged, remaining) = OfflineLibraryStore.reconcileRemoteWithOverrides(
            remoteAnime = listOf(freshFromServer, remoteSecond),
            overrides = mapOf(
                11 to OfflineLibraryStore.UserEntryOverride(staleLocalSynced, pendingSync = false),
                25 to OfflineLibraryStore.UserEntryOverride(pendingLocalSecond, pendingSync = true),
                99 to OfflineLibraryStore.UserEntryOverride(ratedWithoutStatus, pendingSync = false)
            )
        )

        assertEquals(3, merged.size)
        // 1) Zsynchronizowany wcześniej wpis (pendingSync = false) przyjmuje świeże dane z Shinden.pl
        val mergedNaruto = merged.first { it.id == 11 }
        assertEquals("completed", mergedNaruto.watchStatus)
        assertTrue(mergedNaruto.favorite)
        assertEquals(9, mergedNaruto.rateTotal)
        assertEquals(9, mergedNaruto.rateStory)
        assertFalse(remaining.containsKey(11))

        // 2) Niezsynchronizowany lokalny wpis (pendingSync = true) zostaje zachowany do czasu wysyłki
        val mergedBleach = merged.first { it.id == 25 }
        assertEquals("hold", mergedBleach.watchStatus)
        assertTrue(mergedBleach.favorite)
        assertEquals(8, mergedBleach.rateTotal)
        assertTrue(remaining[25]?.pendingSync == true)

        // Pozycja bez statusu pozostaje w bibliotece, jeśli ma ocenę.
        val mergedOnePiece = merged.first { it.id == 99 }
        assertEquals(10, mergedOnePiece.rateTotal)
        assertTrue(remaining.containsKey(99))
    }

    @Test
    fun matchesLibrarySubTabAndStatusLabelsStayConsistentAcrossLibraryAndAccount() {
        val completedByProgress = Anime(
            id = 1,
            coverId = 1,
            title = "A",
            watched = 12,
            total = 12,
            favorite = true,
            watchStatus = "in progress",
            status = "Finished Airing",
            description = "",
            rating = "8.0",
            rateTotal = 8
        )
        val skippedAnime = completedByProgress.copy(
            id = 2,
            watched = 0,
            favorite = false,
            watchStatus = "skip",
            rateTotal = null
        )

        // Statystyki obejrzanych uwzględniają pełny postęp odcinków.
        assertTrue(matchesLibrarySubTab(completedByProgress, 0)) 
        assertTrue(matchesLibrarySubTab(completedByProgress, 1)) 
        assertTrue(matchesLibrarySubTab(completedByProgress, 2)) 
        assertFalse(matchesLibrarySubTab(completedByProgress, 3)) 
        assertTrue(matchesLibrarySubTab(completedByProgress, 4)) 
        assertTrue(matchesLibrarySubTab(completedByProgress, 9)) 

        assertTrue(matchesLibrarySubTab(skippedAnime, 8)) 
        assertFalse(matchesLibrarySubTab(skippedAnime, 9)) 
        assertEquals("Pomijam", libraryStatusLabel(skippedAnime))
        assertNull(libraryStatusLabel(skippedAnime.copy(watchStatus = "")))
    }
}
