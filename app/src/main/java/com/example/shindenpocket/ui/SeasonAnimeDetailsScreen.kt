package com.example.shindenpocket.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.shindenpocket.data.*
import com.example.shindenpocket.ui.theme.PocketPalette
import com.example.shindenpocket.ui.theme.PocketRadius
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class SeasonPlayback(
    val episode: SeasonSeriesEpisode,
    val sources: List<EpisodeSource>,
    val selectedIndex: Int
)

/** Szczegóły serii katalogowej z odcinkami, źródłami i playerem biblioteki. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SeasonAnimeDetailsScreen(
    anime: SeasonAnime,
    userId: Int?,
    username: String? = null,
    libraryAnime: Anime? = null,
    offlineMode: Boolean,
    onBack: () -> Unit,
    onAnimeUpdated: ((Anime) -> Unit)? = null,
    onRelatedClick: (SeasonAnime) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentLibraryAnime by remember(anime.id, libraryAnime) { mutableStateOf(libraryAnime) }
    var tab by remember(anime.id) { mutableIntStateOf(0) }
    var info by remember(anime.id) { mutableStateOf<SeasonSeriesInfo?>(null) }
    var episodes by remember(anime.id) { mutableStateOf<List<SeasonSeriesEpisode>>(emptyList()) }
    var loading by remember(anime.id) { mutableStateOf(false) }
    var error by remember(anime.id) { mutableStateOf<String?>(null) }
    var revision by remember(anime.id) { mutableIntStateOf(0) }
    var selectedEpisodeId by remember(anime.id) { mutableStateOf<Int?>(null) }
    var playback by remember(anime.id) { mutableStateOf<SeasonPlayback?>(null) }
    var switching by remember(anime.id) { mutableStateOf(false) }
    var playerNotice by remember(anime.id) { mutableStateOf<String?>(null) }
    var filter by remember(anime.id) { mutableIntStateOf(0) }
    var sortDescending by remember(anime.id) { mutableStateOf(false) }

    LaunchedEffect(anime.id, offlineMode, userId, revision) {
        if (offlineMode) {
            error = "Lista odcinków anime spoza biblioteki wymaga internetu."
            loading = false
            return@LaunchedEffect
        }
        loading = true
        error = null
        try {
            // Brak opisu lub metadanych nie może zablokować listy odcinków.
            try { info = SeasonSeriesApi.fetchInfo(anime.id) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { info = null }
            episodes = SeasonSeriesApi.fetchEpisodes(anime.id, userId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            error = "Nie udało się pobrać szczegółów anime. Spróbuj ponownie."
        } finally {
            loading = false
        }
    }

    val ordered = remember(episodes) {
        episodes.sortedWith(compareBy({ it.number.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.id }))
    }
    val onlineEpisodes = remember(ordered) {
        ordered.filter { it.online == true && it.detailUrl != null }
    }
    val filtered = remember(ordered, filter) {
        when (filter) {
            1 -> ordered.filter { it.online == true }
            2 -> ordered.filter { it.online == false }
            else -> ordered
        }
    }
    val displayed = remember(filtered, sortDescending) {
        if (sortDescending) filtered.asReversed() else filtered
    }
    val selectedEpisode = selectedEpisodeId?.let { id -> ordered.firstOrNull { it.id == id } }

    fun openPlayer(episode: SeasonSeriesEpisode, sources: List<EpisodeSource>, index: Int) {
        if (offlineMode || userId == null || sources.isEmpty() || episode.detailUrl == null) return
        val safeIndex = index.coerceIn(0, sources.lastIndex)
        playback = SeasonPlayback(episode, sources, safeIndex)
        PlaybackPreferences.saveService(context, userId, anime.id, sources[safeIndex].service)
        selectedEpisodeId = null
        playerNotice = null
    }

    fun changeEpisode(direction: Int) {
        val active = playback ?: return
        val account = userId ?: return
        if (switching || offlineMode) return
        val pos = onlineEpisodes.indexOfFirst { it.id == active.episode.id }
        val target = onlineEpisodes.getOrNull(pos + direction) ?: return
        val url = target.detailUrl ?: return
        switching = true
        scope.launch {
            try {
                val sources = EpisodeSourcesApi.fetch(url, anime.id, target.id, account)
                if (sources.isEmpty()) {
                    playerNotice = "Brak źródeł dla odcinka ${target.number}."
                } else {
                    val preferred = PlaybackPreferences.preferredService(context, account, anime.id)
                    val selected = sources.indexOfFirst { it.service.equals(preferred, ignoreCase = true) }
                    openPlayer(target, sources, selected.coerceAtLeast(0))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                playerNotice = "Nie udało się otworzyć kolejnego odcinka. Spróbuj ponownie."
            } finally { switching = false }
        }
    }

    BackHandler(enabled = selectedEpisodeId != null && playback == null) {
        selectedEpisodeId = null
    }

    val scheme = MaterialTheme.colorScheme
    val episodesTabLabel = if (ordered.isNotEmpty()) "Odcinki ${ordered.size}" else "Odcinki"
    val headerSubtitle = "Aktualny sezon · ${ordered.count { it.online == true }} online · ${ordered.size} w katalogu"
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PocketBackButton(label = "Wróć do sezonu", onClick = onBack)
        PocketTabs(
            labels = listOf("Opis", episodesTabLabel),
            selectedIndex = tab.coerceIn(0, 1),
            onSelect = { tab = it }
        )
        error?.let { message ->
            PocketMessage(
                message = message,
                type = PocketMessageType.ERROR,
                actionLabel = if (!offlineMode && !loading) "Spróbuj ponownie" else null,
                onAction = if (!offlineMode && !loading) ({ revision++ }) else null
            )
        }
        if (tab == 0) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    PocketDetailsHeader(
                        title = anime.title,
                        coverUrl = anime.coverUrl,
                        userId = userId,
                        libraryAnime = currentLibraryAnime,
                        subtitle = headerSubtitle,
                        watched = currentLibraryAnime?.watched,
                        total = currentLibraryAnime?.total?.takeIf { it > 0 } ?: ordered.size.takeIf { it > 0 },
                        seriesInfo = info,
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
                        libraryAnime = currentLibraryAnime,
                        seriesInfo = info,
                        infoLoading = loading,
                        fallbackDescription = null,
                        fallbackCoverUrl = anime.coverUrl,
                        fallbackTotalEpisodes = ordered.size,
                        onAnimeUpdated = { updated ->
                            currentLibraryAnime = updated.takeIf { it.isInUserLibrary }
                            onAnimeUpdated?.invoke(updated)
                        },
                        onOpenEpisodes = { tab = 1 },
                        onRelatedClick = onRelatedClick
                    )
                }
            }
        } else {
            val onlineCount = remember(ordered) { ordered.count { it.online == true } }
            val upcomingCount = remember(ordered) { ordered.count { it.online == false } }
            val firstOnlineEpisode = remember(onlineEpisodes) { onlineEpisodes.firstOrNull() }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    PocketDetailsHeader(
                        title = anime.title,
                        coverUrl = anime.coverUrl,
                        userId = userId,
                        libraryAnime = currentLibraryAnime,
                        subtitle = headerSubtitle,
                        watched = currentLibraryAnime?.watched,
                        total = currentLibraryAnime?.total?.takeIf { it > 0 } ?: ordered.size.takeIf { it > 0 },
                        seriesInfo = info,
                        expanded = false
                    )
                }
                item {
                    val filterItems = buildList {
                        add("Wszystkie" to ordered.size)
                        add("Online" to onlineCount)
                        if (upcomingCount > 0) {
                            add("Zapowiedziane" to upcomingCount)
                        }
                    }
                    val statItems = buildList {
                        add("W katalogu" to ordered.size.toString())
                        add("Online" to onlineCount.toString())
                        if (upcomingCount > 0) {
                            add("Zapowiedziane" to upcomingCount.toString())
                        }
                    }
                    SeriesEpisodesDashboardCard(
                        seriesInfo = info,
                        stats = statItems,
                        filters = filterItems,
                        selectedFilter = filter,
                        onSelectFilter = { filter = it },
                        sortDescending = sortDescending,
                        onToggleSort = { sortDescending = !sortDescending },
                        onRefresh = if (!offlineMode) ({ if (!loading) revision++ }) else null,
                        refreshEnabled = !loading,
                        resumeEpisodeNumber = firstOnlineEpisode?.number,
                        resumeEpisodeTitle = firstOnlineEpisode?.title,
                        onResumeClick = firstOnlineEpisode?.let { ep ->
                            { selectedEpisodeId = ep.id }
                        }
                    )
                }
                if (loading && displayed.isEmpty()) {
                    item {
                        PocketLoadingState(message = "Pobieranie listy odcinków…")
                    }
                }
                if (loading && displayed.isNotEmpty()) {
                    item {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                if (displayed.isEmpty() && !loading && error == null) {
                    item {
                        PocketEmptyState(
                            title = "Brak odcinków",
                            message = "Katalog nie zawiera pozycji pasujących do tego filtra.",
                            symbol = "◌"
                        )
                    }
                }
                items(displayed, key = { it.id }) { episode ->
                    PocketEpisodeCard(
                        number = episode.number,
                        title = episode.title,
                        online = episode.online,
                        airDate = episode.airDate,
                        languages = episode.languages,
                        onClick = { selectedEpisodeId = episode.id }
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
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
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .padding(bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    SeriesEpisodeSheetHeader(
                        episodeNumber = selectedEpisode.number,
                        episodeTitle = selectedEpisode.title,
                        seriesTitle = anime.title,
                        online = selectedEpisode.online,
                        airDate = selectedEpisode.airDate,
                        languages = selectedEpisode.languages,
                        watched = null
                    )
                }
                if (userId == null || offlineMode) {
                    item {
                        PocketMessage(
                            "Aby oglądać, zaloguj się w zakładce Konto i połącz z internetem."
                        )
                    }
                } else {
                    if (selectedEpisode.online == true && selectedEpisode.detailUrl != null) {
                        item {
                            EpisodeSourcesSection(
                                episode = TrackedEpisode(
                                    episodeId = selectedEpisode.id,
                                    number = selectedEpisode.number,
                                    title = selectedEpisode.title,
                                    watched = false,
                                    hasOnlineVersion = selectedEpisode.online,
                                    airDate = selectedEpisode.airDate,
                                    languageLabels = selectedEpisode.languages,
                                    detailUrl = selectedEpisode.detailUrl
                                ),
                                titleId = anime.id,
                                userId = userId,
                                onPlay = { sources, index -> openPlayer(selectedEpisode, sources, index) }
                            )
                        }
                    } else {
                        item {
                            PocketMessage("Brak potwierdzonej wersji online dla tego odcinka.")
                        }
                    }
                    if (selectedEpisode.detailUrl != null) {
                        item {
                            OutlinedButton(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                                shape = RoundedCornerShape(PocketRadius.control.dp),
                                border = BorderStroke(1.dp, PocketPalette.outlineVariant),
                                onClick = {
                                    val url = selectedEpisode.detailUrl
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
                    }
                    item {
                        Text(
                            "Oznaczanie obejrzenia będzie dostępne po dodaniu anime do Mojej listy.",
                            style = MaterialTheme.typography.labelSmall,
                            color = PocketPalette.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    val playing = playback
    if (playing != null) {
        val position = onlineEpisodes.indexOfFirst { it.id == playing.episode.id }
        EpisodePlayerDialog(
            source = playing.sources[playing.selectedIndex],
            sourceIndex = playing.selectedIndex,
            episodeUrl = playing.episode.detailUrl.orEmpty(),
            episodeNumber = playing.episode.number,
            userId = userId,
            sources = playing.sources,
            onSourceChange = { index ->
                if (index in playing.sources.indices) {
                    playback = playing.copy(selectedIndex = index)
                    userId?.let { id ->
                        PlaybackPreferences.saveService(context, id, anime.id, playing.sources[index].service)
                    }
                }
            },
            onPreviousEpisode = if (position > 0) ({ changeEpisode(-1) }) else null,
            onNextEpisode = if (position >= 0 && position < onlineEpisodes.lastIndex) {
                ({ changeEpisode(1) })
            } else null,
            movingEpisode = switching,
            onDismiss = { playback = null }
        )
    }
    if (playerNotice != null) {
        AlertDialog(
            onDismissRequest = { playerNotice = null },
            title = { Text("Odtwarzacz") },
            text = { Text(playerNotice.orEmpty()) },
            confirmButton = {
                TextButton(onClick = { playerNotice = null }) { Text("OK") }
            }
        )
    }
}
