package com.example.shindenpocket.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.shindenpocket.data.Anime
import com.example.shindenpocket.data.SeasonAnime
import com.example.shindenpocket.data.SeasonSeriesApi
import com.example.shindenpocket.data.SeasonSeriesInfo
import com.example.shindenpocket.data.ShindenEpisodesApi
import com.example.shindenpocket.data.ShindenWatchedApi
import com.example.shindenpocket.data.EpisodeSource
import com.example.shindenpocket.data.EpisodeSourcesApi
import com.example.shindenpocket.data.PlaybackPreferences
import com.example.shindenpocket.data.OfflineLibraryStore
import com.example.shindenpocket.data.TrackedEpisode
import com.example.shindenpocket.data.TrackedEpisodesResult
import com.example.shindenpocket.ui.theme.PocketPalette
import com.example.shindenpocket.ui.theme.PocketRadius
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Przy zmianie odcinka pyta o opuszczany, a przy zamknięciu o bieżący; nigdy nie zapisuje automatycznie. */
private enum class PlayerExitAction { NEXT, CLOSE }

private data class WatchDecision(
    val episodeId: Int,
    val episodeNumber: String,
    val action: PlayerExitAction
)

private data class PlayerSession(
    val episodeId: Int,
    val episodeUrl: String,
    val episodeNumber: String,
    val sources: List<EpisodeSource>,
    val selectedIndex: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnimeDetails(
    anime: Anime,
    userId: Int?,
    username: String? = null,
    onBack: () -> Unit,
    onWatchedChanged: () -> Unit = {},
    onAnimeUpdated: ((Anime) -> Unit)? = null,
    continueOnOpen: Boolean = false,
    onPlaybackOpened: () -> Unit = {},
    offlineMode: Boolean = false,
    initialEpisodeId: Int? = null,
    onInitialEpisodeOpened: () -> Unit = {},
    onRelatedClick: (SeasonAnime) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable(anime.id) { mutableIntStateOf(if (continueOnOpen || initialEpisodeId != null) 1 else 0) }
    var filter by rememberSaveable(anime.id) { mutableIntStateOf(0) }
    var sortDescending by rememberSaveable(anime.id) { mutableStateOf(false) }
    var result by remember(anime.id, userId) { mutableStateOf<TrackedEpisodesResult?>(null) }
    var fetching by remember(anime.id, userId) { mutableStateOf(false) }
    var fetchError by remember(anime.id, userId) { mutableStateOf<String?>(null) }
    var refresh by remember(anime.id, userId) { mutableIntStateOf(0) }
    var lastLoadedRefresh by remember(anime.id, userId) { mutableIntStateOf(-1) }
    var selectedEpisodeId by remember(anime.id, userId) { mutableStateOf<Int?>(null) }
    var savingEpisodeId by remember(anime.id, userId) { mutableStateOf<Int?>(null) }
    var watchSaveError by remember(anime.id, userId) { mutableStateOf<String?>(null) }
    var watchSaveNotice by remember(anime.id, userId) { mutableStateOf<String?>(null) }
    var playback by remember(anime.id, userId) { mutableStateOf<PlayerSession?>(null) }
    var movingEpisode by remember(anime.id, userId) { mutableStateOf(false) }
    var watchDecision by remember(anime.id, userId) { mutableStateOf<WatchDecision?>(null) }
    var decisionSaving by remember(anime.id, userId) { mutableStateOf(false) }
    var decisionError by remember(anime.id, userId) { mutableStateOf<String?>(null) }
    var continueHandled by remember(anime.id, userId) { mutableStateOf(false) }
    var continueNotice by remember(anime.id, userId) { mutableStateOf<String?>(null) }
    var showingCachedEpisodes by remember(anime.id, userId) { mutableStateOf(false) }
    var seriesInfo by remember(anime.id) { mutableStateOf<SeasonSeriesInfo?>(null) }
    var infoLoading by remember(anime.id) { mutableStateOf(false) }

    LaunchedEffect(anime.id, offlineMode) {
        if (offlineMode) return@LaunchedEffect
        infoLoading = true
        try {
            seriesInfo = SeasonSeriesApi.fetchInfo(anime.id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Dane karty z biblioteki pozostają dostępne nawet przy błędzie pobrania strony serii
        } finally {
            infoLoading = false
        }
    }

    // Nowość z historii otwiera właściwy panel odcinka po pobraniu katalogu.
    // Nie uruchamiamy automatycznie nieznanego źródła wideo.
    LaunchedEffect(initialEpisodeId, anime.id) {
        if (initialEpisodeId != null) tab = 1
    }

    // Katalog jest pobierany dopiero po otwarciu zakładki „Odcinki” i nie zmienia historii premier.
    
    LaunchedEffect(anime.id, userId, tab, refresh) {
        if (tab != 1 || userId == null || lastLoadedRefresh == refresh) return@LaunchedEffect
        fetching = true
        fetchError = null
        try {
            if (offlineMode) {
                val cached = OfflineLibraryStore.loadEpisodes(context, userId, anime.id)
                if (cached != null) {
                    result = cached
                    showingCachedEpisodes = true
                } else {
                    fetchError = "Brak zapisanej listy odcinków. Otwórz ją raz z internetem."
                }
            } else {
                val fresh = ShindenEpisodesApi.fetchTrackedEpisodes(anime.id, userId)
                result = fresh
                showingCachedEpisodes = false
                try { OfflineLibraryStore.saveEpisodes(context, userId, anime.id, fresh) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Cache nie blokuje prawidłowego odczytu */ }
            }
            lastLoadedRefresh = refresh
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fetchError = e.message ?: "Nie udało się pobrać odcinków"
            if (result == null) {
                val cached = OfflineLibraryStore.loadEpisodes(context, userId, anime.id)
                if (cached != null) { result = cached; showingCachedEpisodes = true }
            }
        } finally {
            fetching = false
        }
    }

    val episodes = result?.items.orEmpty().sortedWith(
        compareBy({ it.number.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.episodeId })
    )
    val filteredEpisodes = episodes.filter {
        when (filter) {
            1 -> it.hasOnlineVersion == true
            2 -> it.hasOnlineVersion == true && !it.watched
            3 -> it.watched
            else -> true
        }
    }
    val visibleEpisodes = if (sortDescending) filteredEpisodes.asReversed() else filteredEpisodes
    val nextResumeEpisode = remember(episodes) {
        episodes.firstOrNull { it.hasOnlineVersion == true && !it.watched }
    }
    val selectedEpisode = selectedEpisodeId?.let { chosenId ->
        episodes.firstOrNull { it.episodeId == chosenId }
    }
    LaunchedEffect(initialEpisodeId, result, fetching, lastLoadedRefresh, refresh) {
        val requested = initialEpisodeId ?: return@LaunchedEffect
        val loaded = result ?: return@LaunchedEffect
        if (loaded.items.any { it.episodeId == requested }) {
            selectedEpisodeId = requested
            onInitialEpisodeOpened()
        } else if (!fetching && lastLoadedRefresh == refresh) {
            // Wpis historii może być starszy niż aktualny katalog; zostajemy
            // na liście, bez wyświetlania błędnego odcinka.
            continueNotice = "Nie znaleziono tego odcinka w aktualnym katalogu."
            onInitialEpisodeOpened()
        }
    }
    val knownTotal: Int? = anime.total.takeIf { it > 0 }
        ?: result?.items?.size?.takeIf { it > 0 }
    val effectiveWatched = result?.watched ?: anime.watched

    // Nie wysyłamy POST, zanim nie zweryfikujemy obecnego statusu na serwerze.
    // Ten sam kod obsługuje przycisk w panelu i potwierdzenie po oglądaniu.
    fun syncWatched(episode: TrackedEpisode, wantWatched: Boolean) {
        val account = userId ?: return
        if (offlineMode || savingEpisodeId != null || fetching) return
        val id = episode.episodeId
        val watchedBefore = episode.watched
        watchSaveError = null
        watchSaveNotice = null
        savingEpisodeId = id
        scope.launch {
            try {
                ShindenWatchedApi.setWatched(
                    titleId = anime.id,
                    userId = account,
                    episodeId = id,
                    wasWatched = watchedBefore,
                    wantWatched = wantWatched
                )
                try {
                    val refreshed = ShindenEpisodesApi.fetchTrackedEpisodes(anime.id, account)
                    result = refreshed
                    try { OfflineLibraryStore.saveEpisodes(context, account, anime.id, refreshed) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Zapis na serwerze jest ważniejszy niż cache */ }
                    if (refreshed.items.firstOrNull { it.episodeId == id }?.watched == wantWatched) {
                        watchSaveNotice = "Zsynchronizowano z Shinden."
                        onWatchedChanged()
                    } else {
                        watchSaveError = "Shinden przyjął zapis, ale katalog jeszcze nie potwierdza " +
                            "zmiany. Odśwież listę przed ponowieniem."
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    watchSaveError = "Zapis wysłany. Nie udało się potwierdzić nowego statusu. " +
                        "Odśwież listę przed ponowieniem."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                watchSaveError = "Nie udało się zmienić obejrzenia. Sprawdź połączenie i odśwież listę przed ponowieniem."
            } finally {
                savingEpisodeId = null
            }
        }
    }

    fun openPlayer(
        episode: TrackedEpisode,
        sources: List<EpisodeSource>,
        sourceIndex: Int,
    ) {
        if (offlineMode || userId == null || sources.isEmpty() || episode.detailUrl == null) return
        val index = sourceIndex.coerceIn(0, sources.lastIndex)
        playback = PlayerSession(episode.episodeId, episode.detailUrl, episode.number, sources, index)
        PlaybackPreferences.saveService(context, userId, anime.id, sources[index].service)
        PlaybackPreferences.saveOpened(
            context, userId, anime.id, episode.episodeId,
            episodeNumber = episode.number, service = sources[index].service
        )
        onPlaybackOpened()
        selectedEpisodeId = null
    }

    // Kolejność z katalogu; pomijamy odcinki bez potwierdzonej wersji online.
    val playableEpisodes = episodes.filter { it.hasOnlineVersion == true && it.detailUrl != null }

    fun navigateInPlayer(direction: Int) {
        val current = playback ?: return
        val account = userId ?: return
        if (offlineMode || movingEpisode) return
        val currentPosition = playableEpisodes.indexOfFirst { it.episodeId == current.episodeId }
        val target = playableEpisodes.getOrNull(currentPosition + direction) ?: return
        movingEpisode = true
        scope.launch {
            try {
                val fetchedSources = EpisodeSourcesApi.fetch(
                    target.detailUrl!!, anime.id, target.episodeId, account
                )
                if (fetchedSources.isEmpty()) {
                    Toast.makeText(context, "Brak źródeł następnego odcinka", Toast.LENGTH_SHORT).show()
                } else {
                    val preferred = PlaybackPreferences.preferredService(context, account, anime.id)
                    val matching = fetchedSources.indexOfFirst { it.service.equals(preferred, true) }
                    openPlayer(
                        target, fetchedSources, if (matching >= 0) matching else 0
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context,
                    "Nie udało się zmienić odcinka: ${e.message.orEmpty()}", Toast.LENGTH_LONG).show()
            } finally {
                movingEpisode = false
            }
        }
    }

    fun performExitAction(action: PlayerExitAction) {
        when (action) {
            PlayerExitAction.NEXT -> navigateInPlayer(1)
            PlayerExitAction.CLOSE -> playback = null
        }
    }

    fun askBeforeLeaving(action: PlayerExitAction) {
        val current = playback ?: return
        if (watchDecision != null || decisionSaving || movingEpisode) return
        val episode = episodes.firstOrNull { it.episodeId == current.episodeId }
        val shouldAsk = userId != null && when (action) {
            PlayerExitAction.NEXT -> PlaybackPreferences.askOnNext(context, userId)
            PlayerExitAction.CLOSE -> PlaybackPreferences.askOnClose(context, userId)
        }
        if (!shouldAsk) { performExitAction(action); return }
        // Pytanie o obejrzenie wymaga bieżącego statusu z katalogu; nie zgadujemy brakujących danych.
        
        if (userId == null || episode == null || episode.watched) {
            performExitAction(action)
            return
        }
        watchSaveError = null
        watchSaveNotice = null
        decisionError = null
        watchDecision = WatchDecision(episode.episodeId, episode.number, action)
    }

    fun saveDecisionAndContinue(decision: WatchDecision) {
        val account = userId ?: return
        if (offlineMode || decisionSaving || savingEpisodeId != null || fetching || decisionError != null) return
        if (watchDecision != decision) return
        val current = result?.items?.firstOrNull { it.episodeId == decision.episodeId } ?: return
        if (current.watched) {
            watchDecision = null
            performExitAction(decision.action)
            return
        }
        decisionSaving = true
        decisionError = null
        scope.launch {
            try {
                // Bezpośrednio przed POST stan strony Shinden jest sprawdzany ponownie.
                ShindenWatchedApi.setWatched(
                    titleId = anime.id,
                    userId = account,
                    episodeId = decision.episodeId,
                    wasWatched = false,
                    wantWatched = true
                )
                // Po zapisie odświeżamy katalog, aby potwierdzić stan serwera.
                val refreshed = ShindenEpisodesApi.fetchTrackedEpisodes(anime.id, account)
                result = refreshed
                try { OfflineLibraryStore.saveEpisodes(context, account, anime.id, refreshed) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Nie blokuj nawigacji po udanym zapisie */ }
                if (refreshed.items.firstOrNull { it.episodeId == decision.episodeId }?.watched != true) {
                    throw IllegalStateException(
                        "Nie udało się potwierdzić statusu na Shinden. " +
                            "Odśwież listę przed kolejną próbą."
                    )
                }
                watchSaveNotice = "Odcinek ${decision.episodeNumber} oznaczono jako obejrzany."
                onWatchedChanged()
                watchDecision = null
                performExitAction(decision.action)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Po błędzie sieci nie ponawiamy automatycznie POST, który mógł dotrzeć do serwera.
                
                decisionError = "Nie udało się potwierdzić synchronizacji. Odśwież listę przed ponowieniem."
                watchSaveError = "Zapis jest niepotwierdzony. Odśwież listę odcinków przed ponowieniem."
            } finally {
                decisionSaving = false
            }
        }
    }

    // Przycisk „Kontynuuj” pobiera katalog i źródła; samo wejście w szczegóły nie otwiera playera.
    
    LaunchedEffect(anime.id, userId, continueOnOpen, lastLoadedRefresh, result, refresh) {
        val account = userId ?: return@LaunchedEffect
        if (offlineMode || !continueOnOpen || continueHandled || lastLoadedRefresh != refresh) return@LaunchedEffect
        continueHandled = true
        val lastOpened = PlaybackPreferences.lastEpisode(context, account, anime.id)
        val sameUnwatched = playableEpisodes.firstOrNull {
            it.episodeId == lastOpened && !it.watched
        }
        val afterLast = playableEpisodes.indexOfFirst { it.episodeId == lastOpened }
        val nextUnwatched = playableEpisodes.drop(if (afterLast >= 0) afterLast + 1 else 0)
            .firstOrNull { !it.watched }
        val target = sameUnwatched ?: nextUnwatched
            ?: playableEpisodes.firstOrNull { !it.watched }
        if (target == null) {
            continueNotice = "Nie ma aktualnie nieobejrzanego odcinka z wersją online."
            return@LaunchedEffect
        }
        try {
            val fetchedSources = EpisodeSourcesApi.fetch(
                target.detailUrl!!, anime.id, target.episodeId, account
            )
            if (fetchedSources.isNotEmpty()) {
                val preferred = PlaybackPreferences.preferredService(context, account, anime.id)
                val matching = fetchedSources.indexOfFirst { it.service.equals(preferred, true) }
                openPlayer(target, fetchedSources, if (matching >= 0) matching else 0)
            } else {
                selectedEpisodeId = target.episodeId
                continueNotice = "Nie znaleziono źródeł. Wybierz je w panelu odcinka."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            selectedEpisodeId = target.episodeId
            continueNotice = "Nie udało się uruchomić źródła. Wybierz je ręcznie."
        }
    }

    
    
    val scheme = MaterialTheme.colorScheme
    val airingLabel = when (anime.status.trim().lowercase()) {
        "currently airing" -> "W emisji"
        "finished airing" -> "Zakończone"
        "not yet aired" -> "Zapowiedziane"
        else -> anime.status.ifBlank { "Anime" }
    }

    val episodesTabLabel = if (knownTotal != null) "Odcinki $knownTotal" else "Odcinki"

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PocketBackButton(label = "Wróć do listy", onClick = onBack)
            if (anime.favorite) {
                Surface(
                    color = scheme.primary.copy(alpha = 0.13f),
                    shape = RoundedCornerShape(50)
                ) {
                    Text(
                        "♥  Ulubione",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        color = scheme.primary,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        PocketTabs(
            labels = listOf("Opis", episodesTabLabel),
            selectedIndex = tab.coerceIn(0, 1),
            onSelect = { tab = it }
        )

        if (offlineMode || showingCachedEpisodes) {
            PocketMessage("Kopia offline — status odcinków może być nieaktualny. Zapis obejrzeń i wideo wymagają internetu.")
        }

        if (tab == 0) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    PocketDetailsHeader(
                        title = anime.title,
                        coverUrl = getCoverUrl(anime.coverId),
                        userId = userId,
                        libraryAnime = anime,
                        subtitle = airingLabel,
                        watched = effectiveWatched,
                        total = knownTotal,
                        seriesInfo = seriesInfo,
                        expanded = true
                    )
                }
                item {
                    SeriesOverviewContent(
                        titleId = anime.id,
                        seriesTitle = anime.title,
                        userId = userId,
                        username = username,
                        offlineMode = offlineMode,
                        libraryAnime = anime,
                        seriesInfo = seriesInfo,
                        infoLoading = infoLoading,
                        fallbackDescription = anime.description,
                        fallbackCoverUrl = getCoverUrl(anime.coverId),
                        fallbackTotalEpisodes = knownTotal ?: 0,
                        onAnimeUpdated = onAnimeUpdated,
                        onOpenEpisodes = { tab = 1 },
                        onRelatedClick = onRelatedClick
                    )
                }
            }
        } else {
            // Natywne `pullToRefresh` Material 3 obsługuje gest odświeżania `LazyColumn`.
            
            PullToRefreshBox(
                isRefreshing = fetching,
                onRefresh = {
                    if (userId != null && !offlineMode && !fetching) refresh++
                },
                modifier = Modifier.fillMaxWidth().weight(1f)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        PocketDetailsHeader(
                            title = anime.title,
                            coverUrl = getCoverUrl(anime.coverId),
                            userId = userId,
                            libraryAnime = anime,
                            subtitle = airingLabel,
                            watched = effectiveWatched,
                            total = knownTotal,
                            seriesInfo = seriesInfo,
                            expanded = false
                        )
                    }
                    if (userId == null) {
                        item {
                            PocketEmptyState(
                                title = "Wymagane logowanie",
                                message = "Zaloguj się, aby zobaczyć odcinki i zsynchronizować postęp.",
                                symbol = "◇"
                            )
                        }
                    } else {
                        if (fetchError != null) {
                            item {
                                PocketMessage(
                                    message = if (result != null)
                                        "Nie udało się odświeżyć odcinków. Poniżej widzisz dane z poprzedniego odczytu."
                                    else "Nie udało się pobrać odcinków. Sprawdź połączenie i spróbuj ponownie.",
                                    type = PocketMessageType.ERROR,
                                    actionLabel = if (!offlineMode && !fetching) "Ponów" else null,
                                    onAction = if (!offlineMode && !fetching) ({ refresh++ }) else null
                                )
                            }
                        }
                        if (continueNotice != null || watchSaveError != null || watchSaveNotice != null) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    continueNotice?.let { PocketMessage(it) }
                                    watchSaveNotice?.let { PocketMessage(it, type = PocketMessageType.SUCCESS) }
                                    watchSaveError?.let { PocketMessage(it, type = PocketMessageType.ERROR) }
                                }
                            }
                        }
                        if (result != null) {
                            item {
                                val current = result!!
                                val filterItems = listOf(
                                    "Wszystkie" to current.items.size,
                                    "Online" to current.withOnlineVersion,
                                    "Do obejrzenia" to current.availableUnwatched,
                                    "Obejrzane" to current.watched
                                )
                                SeriesEpisodesDashboardCard(
                                    seriesInfo = seriesInfo,
                                    filters = filterItems,
                                    selectedFilter = filter,
                                    onSelectFilter = { filter = it },
                                    sortDescending = sortDescending,
                                    onToggleSort = { sortDescending = !sortDescending },
                                    onRefresh = if (!offlineMode) ({ if (!fetching) refresh++ }) else null,
                                    refreshEnabled = !fetching,
                                    resumeEpisodeNumber = nextResumeEpisode?.number,
                                    resumeEpisodeTitle = nextResumeEpisode?.title,
                                    onResumeClick = nextResumeEpisode?.let { nextEp ->
                                        { selectedEpisodeId = nextEp.episodeId }
                                    }
                                )
                            }
                            if (visibleEpisodes.isEmpty() && !fetching) {
                                item {
                                    PocketEmptyState(
                                        title = "Brak odcinków",
                                        message = "Nie znaleziono odcinków pasujących do wybranego filtra.",
                                        symbol = "◌"
                                    )
                                }
                            }
                            items(visibleEpisodes, key = { it.episodeId }) { episode ->
                                PocketEpisodeCard(
                                    number = episode.number,
                                    title = episode.title,
                                    online = episode.hasOnlineVersion,
                                    airDate = episode.airDate,
                                    languages = episode.languageLabels,
                                    watched = episode.watched,
                                    onClick = { selectedEpisodeId = episode.episodeId },
                                    modifier = Modifier.animateItem()
                                )
                            }
                            item {
                                Text(
                                    "Przeciągnij listę od góry, aby odświeżyć. " +
                                        "Online oznacza status Shindena, nie gwarancję działania wideo.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PocketPalette.onSurfaceVariant
                                )
                                Spacer(Modifier.height(16.dp))
                            }
                        }
                        if (result == null && fetching) {
                            item {
                                PocketLoadingState(message = "Pobieranie listy odcinków z Shindena…")
                            }
                        }
                        if (result == null && !fetching && fetchError == null) {
                            item {
                                PocketEmptyState(
                                    title = "Otwieram listę odcinków",
                                    message = "Za chwilę pojawią się dane z katalogu Shindena.",
                                    symbol = "…"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedEpisode != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedEpisodeId = null },
            containerColor = PocketPalette.surfaceRaised,
            contentColor = Color.White
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        SeriesEpisodeSheetHeader(
                            episodeNumber = selectedEpisode.number,
                            episodeTitle = selectedEpisode.title,
                            seriesTitle = anime.title,
                            online = selectedEpisode.hasOnlineVersion,
                            airDate = selectedEpisode.airDate,
                            languages = selectedEpisode.languageLabels,
                            watched = selectedEpisode.watched
                        )
                        if (userId != null && !offlineMode) {
                            val saving = savingEpisodeId == selectedEpisode.episodeId
                            Surface(
                                onClick = {
                                    if (savingEpisodeId == null && !fetching) {
                                        syncWatched(selectedEpisode, !selectedEpisode.watched)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                color = if (selectedEpisode.watched) PocketPalette.surface
                                else PocketPalette.primaryContainer,
                                shape = RoundedCornerShape(PocketRadius.control.dp),
                                border = BorderStroke(
                                    1.dp,
                                    if (selectedEpisode.watched) PocketPalette.outlineVariant
                                    else PocketPalette.primary.copy(alpha = 0.65f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 13.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (saving) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.width(18.dp).height(18.dp),
                                            strokeWidth = 2.dp,
                                            color = Color.White
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            "Synchronizuję…",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    } else {
                                        Text(
                                            text = if (selectedEpisode.watched) {
                                                "↺  Cofnij obejrzenie na Shinden"
                                            } else {
                                                "✓  Oznacz jako obejrzany na Shinden"
                                            },
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                            watchSaveNotice?.let { notice ->
                                PocketMessage(notice, type = PocketMessageType.SUCCESS)
                            }
                            watchSaveError?.let { error ->
                                PocketMessage(error, type = PocketMessageType.ERROR)
                            }
                        }
                        if (userId != null && !offlineMode) {
                            EpisodeSourcesSection(
                                episode = selectedEpisode,
                                titleId = anime.id,
                                userId = userId,
                                onPlay = { sources, index -> openPlayer(selectedEpisode, sources, index) }
                            )
                        }
                        if (selectedEpisode.detailUrl != null && !offlineMode) {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                                shape = RoundedCornerShape(PocketRadius.control.dp),
                                border = BorderStroke(1.dp, PocketPalette.outlineVariant),
                                onClick = {
                                    val url = selectedEpisode.detailUrl
                                    // Link zweryfikowany w ShindenEpisodesApi.
                                    try {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        )
                                        selectedEpisodeId = null
                                    } catch (_: Exception) {
                                        Toast.makeText(
                                            context,
                                            "Nie udało się otworzyć przeglądarki",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            ) {
                                Text(
                                    "Otwórz odcinek na Shinden ↗",
                                    color = PocketPalette.primarySoft,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        Text(
                            "Przy przejściu do następnego odcinka aplikacja zapyta " +
                                "o poprzedni, a przy zamknięciu — o ostatni. " +
                                "Zapis wymaga Twojego potwierdzenia.",
                            style = MaterialTheme.typography.labelSmall,
                            color = PocketPalette.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    val currentPlayback = playback
    if (currentPlayback != null) {
        val currentIndex = playableEpisodes.indexOfFirst {
            it.episodeId == currentPlayback.episodeId
        }
        EpisodePlayerDialog(
            userId = userId,
            source = currentPlayback.sources[currentPlayback.selectedIndex],
            sourceIndex = currentPlayback.selectedIndex,
            episodeUrl = currentPlayback.episodeUrl,
            episodeNumber = currentPlayback.episodeNumber,
            sources = currentPlayback.sources,
            onSourceChange = { index ->
                if (index in currentPlayback.sources.indices) {
                    playback = currentPlayback.copy(selectedIndex = index)
                    userId?.let {
                        PlaybackPreferences.saveService(context, it, anime.id, currentPlayback.sources[index].service)
                    }
                }
            },
            onPreviousEpisode = if (currentIndex > 0) ({ navigateInPlayer(-1) }) else null,
            onNextEpisode = if (currentIndex >= 0 && currentIndex < playableEpisodes.lastIndex)
                ({ askBeforeLeaving(PlayerExitAction.NEXT) }) else null,
            movingEpisode = movingEpisode,
            onDismiss = { askBeforeLeaving(PlayerExitAction.CLOSE) }
        )
    }

    val decision = watchDecision
    if (decision != null) {
        AlertDialog(
            onDismissRequest = {
                if (!decisionSaving) {
                    // Dotknięcie poza oknem lub Wstecz wraca do filmu zamiast zamknąć player.
                    watchDecision = null
                    decisionError = null
                }
            },
            title = { Text("Odcinek ${decision.episodeNumber} — obejrzany?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (decision.action == PlayerExitAction.NEXT)
                            "Czy oznaczyć ten odcinek jako obejrzany na Shinden przed przejściem do następnego?"
                        else
                            "Czy oznaczyć ten odcinek jako obejrzany na Shinden przed zamknięciem odtwarzacza?"
                    )
                    Text(
                        "Samo uruchomienie filmu niczego nie zapisuje.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (decisionSaving) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("Synchronizuję z Shinden…", style = MaterialTheme.typography.bodySmall)
                    }
                    decisionError?.let { error ->
                        PocketMessage(error, type = PocketMessageType.ERROR)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !decisionSaving && decisionError == null &&
                        savingEpisodeId == null && !fetching && userId != null,
                    onClick = { saveDecisionAndContinue(decision) }
                ) {
                    Text(if (decision.action == PlayerExitAction.NEXT)
                        "Oznacz i dalej" else "Oznacz i zamknij")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        enabled = !decisionSaving,
                        onClick = {
                            watchDecision = null
                            decisionError = null
                        }
                    ) { Text("Zostań") }
                    TextButton(
                        enabled = !decisionSaving,
                        onClick = {
                            watchDecision = null
                            decisionError = null
                            performExitAction(decision.action)
                        }
                    ) { Text("Nie teraz") }
                }
            }
        )
    }
}

