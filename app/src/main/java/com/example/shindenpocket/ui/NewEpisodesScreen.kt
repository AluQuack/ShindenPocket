package com.example.shindenpocket.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shindenpocket.data.Anime
import com.example.shindenpocket.data.EpisodeSnapshotStore
import com.example.shindenpocket.data.ShindenEpisodesApi
import com.example.shindenpocket.ui.theme.PocketPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val SecondaryText = PocketPalette.onSurfaceVariant

/** Ekran nowych odcinków i historii premier. */
@Composable
fun NewEpisodesScreen(
    allAnime: List<Anime>,
    userId: Int?,
    loading: Boolean,
    error: String?,
    onAnimeClick: (Anime) -> Unit,
    onEpisodeClick: (Anime, Int) -> Unit,
    onSearch: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanJob by remember { mutableStateOf<Job?>(null) }
    var checking by remember { mutableStateOf(false) }
    var checkedCount by remember { mutableStateOf(0) }
    var totalToCheck by remember { mutableStateOf(0) }
    var scanMessage by remember(userId) { mutableStateOf<String?>(null) }
    var historyRefreshKey by remember(userId) { mutableStateOf(0) }

    val observed = remember(allAnime) {
        allAnime.filter { anime ->
            anime.status.trim().equals("Currently Airing", ignoreCase = true) &&
                (anime.watchStatus.trim().equals("in progress", ignoreCase = true) || anime.favorite)
        }
    }
    val animeById = remember(allAnime) { allAnime.associateBy { it.id } }
    LaunchedEffect(userId) {
        scanJob?.cancel()
        scanJob = null
        checking = false
        checkedCount = 0
        totalToCheck = 0
    }
    val scanNow: () -> Unit = {
        val account = userId
        if (account != null && !checking && !loading && observed.isNotEmpty()) {
            val toCheck = observed.toList()
            scanJob = scope.launch {
                checking = true
                checkedCount = 0
                totalToCheck = toCheck.size
                scanMessage = null
                var failures = 0
                var scanned = 0
                try {
                    for (anime in toCheck) {
                        try {
                            val fetched = ShindenEpisodesApi.fetchTrackedEpisodes(
                                titleId = anime.id, userId = account
                            )
                            EpisodeSnapshotStore.compareAndSave(
                                context = context,
                                userId = account,
                                titleId = anime.id,
                                current = fetched
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            failures++
                        }
                        scanned++
                        checkedCount = scanned
                    }
                } finally {
                    // Odczytujemy także wyniki częściowego skanowania.
                    historyRefreshKey++
                    scanMessage = if (failures > 0)
                        "Nie udało się sprawdzić $failures z $scanned serii."
                    else null
                    checking = false
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(PocketBackground)
    ) {
        PocketScreenHeader(
            title = "Nowe odcinki",
            subtitle = if (checking) "Skanuję $checkedCount z $totalToCheck serii…" else "Premiere obserwowanych serii",
            modifier = Modifier.padding(bottom = 12.dp),
            actions = {
                if (onSearch != null) {
                    IconButton(
                        onClick = onSearch,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "Szukaj w katalogu anime" }
                    ) { Text("⌕", fontSize = 22.sp) }
                }
                if (userId != null) {
                    IconButton(
                        onClick = scanNow,
                        enabled = !checking && !loading && observed.isNotEmpty(),
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "Skanuj nowe odcinki" }
                    ) {
                        Text("↻", color = if (checking || loading || observed.isEmpty()) SecondaryText else PocketPalette.primary, fontSize = 22.sp)
                    }
                }
            }
        )

        if (checking) {
            LinearProgressIndicator(
                progress = { if (totalToCheck == 0) 0f else checkedCount.toFloat() / totalToCheck },
                color = PocketPalette.primary,
                trackColor = PocketPalette.surfaceVariant,
                modifier = Modifier.fillMaxWidth().height(4.dp)
            )
            Spacer(Modifier.height(10.dp))
        }
        scanMessage?.let { value ->
            PocketMessage(
                message = value,
                type = PocketMessageType.ERROR,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }

        if (userId == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PocketEmptyState(
                    title = "Zaloguj się, aby śledzić premiery",
                    message = "Logowanie znajdziesz w zakładce Konto.",
                    symbol = "✦"
                )
            }
        } else {
            EpisodeHistorySection(
                userId = userId,
                refreshKey = historyRefreshKey,
                animeById = animeById,
                modifier = Modifier.weight(1f),
                onOpenEpisode = { event ->
                    val anime = animeById[event.titleId]
                    if (anime != null) {
                        onEpisodeClick(anime, event.episodeId)
                        true
                    } else false
                }
            )
        }
    }

}
