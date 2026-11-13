package com.example.shindenpocket.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shindenpocket.data.*
import com.example.shindenpocket.ui.theme.PocketPalette
import com.example.shindenpocket.ui.theme.PocketRadius
import com.example.shindenpocket.ui.theme.PocketSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Stan sezonu jest zachowywany przy zmianie zakładek i korzysta z cache Shinden. */
internal class SeasonStartState {
    var season by mutableStateOf<List<SeasonAnime>>(emptyList())
    var hasMore by mutableStateOf(false)
    var loading by mutableStateOf(true)
    var cached by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var retry by mutableIntStateOf(0)
}

@Composable
internal fun rememberSeasonStartState(
    restoringSession: Boolean,
    userId: Int?,
    offlineMode: Boolean
): SeasonStartState {
    val context = LocalContext.current
    val state = remember(userId) { SeasonStartState() }
    LaunchedEffect(restoringSession, userId, offlineMode, state.retry) {
        if (restoringSession) return@LaunchedEffect
        state.loading = true
        state.error = null
        try {
            val saved = withContext(Dispatchers.IO) { CurrentSeasonApi.loadCached(context) }
            if (saved != null) {
                state.season = saved.anime
                state.cached = true
            }
            if (offlineMode) {
                if (saved == null) state.error = "Brak wcześniej zapisanej listy sezonu."
                return@LaunchedEffect
            }
            val fresh = CurrentSeasonApi.fetchCurrentPage()
            state.season = fresh.anime
            state.hasMore = fresh.hasNextPage
            state.cached = false
            withContext(Dispatchers.IO) { CurrentSeasonApi.saveCached(context, fresh.anime) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.error = e.message ?: "Nie udało się pobrać sezonu."
        } finally {
            state.loading = false
        }
    }
    return state
}

@Composable
internal fun SeasonStartScreen(
    userId: Int?,
    library: List<Anime>,
    recent: List<Pair<Anime, RecentPlayback>>,
    offlineMode: Boolean,
    seasonState: SeasonStartState,
    onMyList: () -> Unit,
    onOpenSeasonPage: () -> Unit,
    onOpenGenreSearch: () -> Unit,
    onOpenAnime: (Anime, Boolean) -> Unit,
    onSeasonClick: (SeasonAnime) -> Unit,
    onNewEpisodes: () -> Unit,
    onFavorites: () -> Unit,
    onOpenNewEpisode: (EpisodeHistoryEvent) -> Unit
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // null = aktualny sezon z cache; inaczej archiwalny sezon pobierany na żądanie.
    val currentPeriod = remember { CurrentSeasonApi.recentPeriods(1).first() }
    var selectedPeriod by remember { mutableStateOf<SeasonPeriod?>(null) }
    var pastSeason by remember { mutableStateOf<List<SeasonAnime>>(emptyList()) }
    var pastLoading by remember { mutableStateOf(false) }
    var pastError by remember { mutableStateOf<String?>(null) }
    var pastRetry by remember { mutableIntStateOf(0) }
    var pastHasMore by remember { mutableStateOf(false) }
    var yearMenuOpen by remember { mutableStateOf(false) }
    var additionalSeason by remember { mutableStateOf<List<SeasonAnime>>(emptyList()) }
    var loadedPage by remember { mutableIntStateOf(1) }
    var moreLoading by remember { mutableStateOf(false) }
    var moreError by remember { mutableStateOf<String?>(null) }

    val watching = remember(library) {
        library.filter { it.watchStatus.trim().equals("in progress", ignoreCase = true) }
    }
    val favorites = remember(library) { library.filter { it.favorite } }
    val animeById = remember(library) { library.associateBy { it.id } }
    val recentById = remember(recent) { recent.associate { it.first.id to it.second } }
    val realHistory = remember(userId, library, seasonState.retry) {
        if (userId == null) emptyList() else try {
            EpisodeSnapshotStore.getRecentHistory(context, userId)
                .filter { !it.isTest && !it.isRead && it.titleId in animeById }.take(8)
        } catch (_: Exception) {
            emptyList()
        }
    }
    val continueCandidates = remember(recent, watching) {
        (recent.map { it.first } + watching).distinctBy { it.id }.take(10)
    }
    LaunchedEffect(selectedPeriod, pastRetry, offlineMode) {
        val period = selectedPeriod ?: return@LaunchedEffect
        pastError = null
        if (offlineMode) {
            pastError = "Przeglądanie poprzednich sezonów wymaga połączenia z internetem."
            return@LaunchedEffect
        }
        pastLoading = true
        try {
            val page = CurrentSeasonApi.fetchPage(period, 1)
            pastSeason = page.anime
            pastHasMore = page.hasNextPage
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            pastError = e.message ?: "Nie udało się pobrać sezonu."
        } finally {
            pastLoading = false
        }
    }

    val firstPage = if (selectedPeriod == null) seasonState.season else pastSeason
    val catalog = (firstPage + additionalSeason).distinctBy { it.id }
    val catalogLoading = if (selectedPeriod == null) seasonState.loading else pastLoading
    val catalogError = if (selectedPeriod == null) seasonState.error else pastError
    val periodLabel = selectedPeriod?.let(::seasonPeriodLabel)
    val displayedPeriod = selectedPeriod ?: currentPeriod
    val nextPeriod = shiftSeasonPeriod(displayedPeriod, 1)
    val canGoForward = seasonPeriodIndex(nextPeriod) <= seasonPeriodIndex(currentPeriod)
    fun selectPeriod(period: SeasonPeriod) {
        val newPeriod = period.takeUnless { it == currentPeriod }
        if (newPeriod != null && newPeriod != selectedPeriod) pastSeason = catalog
        selectedPeriod = newPeriod
        query = ""
        additionalSeason = emptyList()
        loadedPage = 1
        moreError = null
    }
    val filteredSeason = remember(catalog, query) {
        if (query.isBlank()) catalog else catalog.filter {
            it.title.contains(query.trim(), ignoreCase = true)
        }
    }
    val listedSeason = filteredSeason
    val hasMore = if (selectedPeriod == null) seasonState.hasMore else pastHasMore

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 2.dp, bottom = PocketSpacing.section.dp)
    ) {
        item(key = "hero") {
            val topRecent = recent.firstOrNull()
            val topAnime = topRecent?.first ?: watching.firstOrNull()
            val topSeason = seasonState.season.firstOrNull()
            val topEpisodeLabel = topRecent?.second?.episodeNumber?.takeIf { it.isNotBlank() }?.let { num ->
                "Odcinek $num"
            } ?: topAnime?.let { a ->
                if (a.watched > 0) "Odcinek ${a.watched}" else null
            }
            when {
                topAnime != null -> PocketHero(
                    title = topAnime.title,
                    coverUrl = getCoverUrl(topAnime.coverId),
                    description = topAnime.description,
                    status = topAnime,
                    userId = userId,
                    episodeLabel = topEpisodeLabel,
                    actionText = if (offlineMode) "Szczegóły" else "▶ Wznów",
                    onPrimary = { onOpenAnime(topAnime, !offlineMode) },
                    onDetails = if (offlineMode) null else ({ onOpenAnime(topAnime, false) })
                )
                topSeason != null -> PocketHero(
                    title = topSeason.title,
                    coverUrl = topSeason.coverUrl,
                    description = listOfNotNull(
                        "Aktualny sezon Shindena",
                        topSeason.episodes?.let { "$it odc." }
                    ).joinToString(" · "),
                    actionText = "Otwórz anime",
                    onPrimary = { onSeasonClick(topSeason) }
                )
                else -> PocketCard(emphasized = true) {
                    Column(
                        Modifier.fillMaxWidth().padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Witaj w Shinden Pocket", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            "Odkrywaj nowe serie, prowadź swoją bibliotekę i wracaj do rozpoczętych odcinków.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item(key = "discover") {
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(min = 66.dp),
                shape = RoundedCornerShape(PocketRadius.card.dp),
                color = PocketPalette.surfaceRaised,
                border = BorderStroke(1.dp, PocketPalette.outlineVariant.copy(alpha = 0.72f)),
                onClick = onOpenGenreSearch,
                interactionSource = remember { MutableInteractionSource() }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(40.dp)
                            .background(
                                Brush.linearGradient(listOf(PocketPalette.primary, PocketPalette.primaryContainer)),
                                RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⌕", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Odkrywaj anime",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = PocketPalette.onBackground
                        )
                        Text(
                            text = "Przeszukaj katalog Shinden według gatunków i filtrów",
                            style = MaterialTheme.typography.bodySmall,
                            color = PocketPalette.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text("›", color = PocketPalette.primarySoft, style = MaterialTheme.typography.headlineSmall)
                }
            }
        }

        if (continueCandidates.isNotEmpty()) {
            item(key = "continue_title") {
                PocketSectionHeader("Kontynuuj oglądanie", "Więcej", onMyList)
            }
            item(key = "continue_row") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(continueCandidates, key = { it.id }) { anime ->
                        val rec = recentById[anime.id]
                        val sub = when {
                            !rec?.episodeNumber.isNullOrBlank() -> "Odc. ${rec?.episodeNumber}"
                            anime.total > 0 -> "Odc. ${anime.watched}/${anime.total}"
                            anime.watched > 0 -> "Odc. ${anime.watched}"
                            else -> null
                        }
                        PocketPoster(
                            title = anime.title,
                            coverUrl = getCoverUrl(anime.coverId),
                            libraryAnime = anime,
                            userId = userId,
                            onClick = { onOpenAnime(anime, !offlineMode) },
                            modifier = Modifier.width(114.dp),
                            style = PocketCardStyle.WIDE,
                            subtitle = sub
                        )
                    }
                }
            }
        }

        if (watching.isNotEmpty()) {
            item(key = "list_title") { PocketSectionHeader("Moja lista", "Więcej", onMyList) }
            item(key = "list_row") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(watching.take(12), key = { it.id }) { anime ->
                        PocketPoster(
                            title = anime.title,
                            coverUrl = getCoverUrl(anime.coverId),
                            libraryAnime = anime,
                            userId = userId,
                            onClick = { onOpenAnime(anime, false) },
                            modifier = Modifier.width(108.dp),
                            style = PocketCardStyle.COMPACT
                        )
                    }
                }
            }
        }

        if (realHistory.isNotEmpty()) {
            item(key = "new_title") { PocketSectionHeader("Nowe odcinki", "Więcej", onNewEpisodes) }
            item(key = "new_rows") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    realHistory.take(4).forEach { event ->
                        val anime = animeById[event.titleId] ?: return@forEach
                        HomeNewEpisodeRow(
                            event = event,
                            anime = anime,
                            userId = userId,
                            onClick = { onOpenNewEpisode(event) }
                        )
                    }
                }
            }
        }

        if (favorites.isNotEmpty()) {
            item(key = "fav_title") { PocketSectionHeader("Ulubione", "Więcej", onFavorites) }
            item(key = "fav_row") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(favorites.take(10), key = { it.id }) { anime ->
                        PocketPoster(
                            title = anime.title,
                            coverUrl = getCoverUrl(anime.coverId),
                            libraryAnime = anime,
                            userId = userId,
                            onClick = { onOpenAnime(anime, false) },
                            modifier = Modifier.width(108.dp),
                            style = PocketCardStyle.COMPACT
                        )
                    }
                }
            }
        }

        item(key = "season_title") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier.width(4.dp).height(20.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(scheme.primary)
                    )
                    Text(
                        text = "Sezon anime",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = scheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            additionalSeason = emptyList()
                            loadedPage = 1
                            moreError = null
                            if (selectedPeriod == null) seasonState.retry++ else pastRetry++
                        },
                        enabled = !catalogLoading && !offlineMode,
                        modifier = Modifier.size(48.dp).semantics {
                            contentDescription = "Odśwież sezon"
                        }
                    ) {
                        Text("↻", color = PocketPalette.primarySoft, fontSize = 18.sp)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(0.36f)) {
                        Surface(
                            onClick = { yearMenuOpen = true },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(PocketRadius.control.dp),
                            color = PocketPalette.surfaceRaised,
                            border = BorderStroke(1.dp, PocketPalette.outlineVariant)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    displayedPeriod.year.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = PocketPalette.onBackground
                                )
                                Text("⌄", color = PocketPalette.onSurfaceVariant)
                            }
                        }
                        DropdownMenu(
                            expanded = yearMenuOpen,
                            onDismissRequest = { yearMenuOpen = false }
                        ) {
                            (currentPeriod.year downTo 1990).forEach { year ->
                                val selected = year == displayedPeriod.year
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            year.toString(),
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (selected) PocketPalette.primarySoft
                                            else PocketPalette.onBackground
                                        )
                                    },
                                    onClick = {
                                        val period = SeasonPeriod(year, displayedPeriod.slug)
                                            .takeIf {
                                                seasonPeriodIndex(it) <= seasonPeriodIndex(currentPeriod)
                                            } ?: currentPeriod
                                        selectPeriod(period)
                                        yearMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                    Surface(
                        modifier = Modifier.weight(0.64f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(PocketRadius.control.dp),
                        color = PocketPalette.surfaceRaised,
                        border = BorderStroke(1.dp, PocketPalette.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { selectPeriod(shiftSeasonPeriod(displayedPeriod, -1)) },
                                modifier = Modifier.size(48.dp).semantics {
                                    contentDescription = "Poprzedni sezon"
                                }
                            ) {
                                Text(
                                    "‹",
                                    color = PocketPalette.primarySoft,
                                    style = MaterialTheme.typography.headlineMedium
                                )
                            }
                            Text(
                                seasonName(displayedPeriod.slug),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = PocketPalette.onBackground,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = { selectPeriod(nextPeriod) },
                                enabled = canGoForward,
                                modifier = Modifier.size(48.dp).semantics {
                                    contentDescription = "Następny sezon"
                                }
                            ) {
                                Text(
                                    "›",
                                    color = if (canGoForward) PocketPalette.primarySoft
                                    else PocketPalette.onSurfaceVariant.copy(alpha = 0.38f),
                                    style = MaterialTheme.typography.headlineMedium
                                )
                            }
                        }
                    }
                }
                PocketSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Szukaj w sezonie"
                )
                if (catalogLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = scheme.primary)
                }
                if (catalogError != null) {
                    PocketMessage(
                        message = when {
                            periodLabel != null && catalog.isEmpty() ->
                                "Nie udało się pobrać wybranego sezonu."
                            periodLabel != null -> "Nie udało się odświeżyć sezonu."
                            catalog.isEmpty() -> "Nie udało się pobrać aktualnego sezonu."
                            else -> "Nie udało się odświeżyć sezonu. Pokazuję ostatnio zapisane dane."
                        },
                        type = PocketMessageType.ERROR,
                        actionLabel = if (catalog.isEmpty()) {
                            if (periodLabel != null) "Spróbuj ponownie" else "Otwórz Shinden"
                        } else null,
                        onAction = if (catalog.isEmpty()) {
                            if (periodLabel != null) ({ pastRetry += 1 }) else onOpenSeasonPage
                        } else null
                    )
                }
            }
        }
        if (!catalogLoading && listedSeason.isEmpty()) {
            item(key = "season_empty") {
                PocketEmptyState(
                    title = if (query.isNotEmpty()) "Brak wyników w sezonie" else "Brak danych sezonu",
                    message = if (query.isNotEmpty()) "Spróbuj wpisać inną nazwę anime."
                    else "Odśwież dane po połączeniu z internetem.",
                    actionLabel = if (query.isNotEmpty()) "Wyczyść wyszukiwanie" else null,
                    onAction = if (query.isNotEmpty()) ({ query = "" }) else null
                )
            }
        }
        itemsIndexed(listedSeason.chunked(3), key = { index, _ -> "season_row_$index" }) { _, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { entry ->
                    PocketPoster(
                        title = entry.title,
                        coverUrl = entry.coverUrl,
                        libraryAnime = animeById[entry.id],
                        userId = userId,
                        onClick = { onSeasonClick(entry) },
                        modifier = Modifier.weight(1f),
                        style = PocketCardStyle.POSTER,
                        subtitle = entry.episodes?.let { "$it odc." }
                    )
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (moreError != null) {
            item(key = "season_more_error") {
                PocketMessage(message = moreError!!, type = PocketMessageType.ERROR)
            }
        }
        if (query.isBlank() && hasMore && !offlineMode) {
            item(key = "season_more") {
                PocketPrimaryButton(
                    label = if (moreLoading) "Pobieranie…" else "Pokaż więcej",
                    enabled = !moreLoading,
                    onClick = {
                        val period = displayedPeriod
                        val pageNumber = loadedPage + 1
                        moreLoading = true
                        moreError = null
                        scope.launch {
                            try {
                                val page = CurrentSeasonApi.fetchPage(period, pageNumber)
                                if ((selectedPeriod ?: currentPeriod) == period) {
                                    additionalSeason = (additionalSeason + page.anime)
                                        .distinctBy { it.id }
                                    loadedPage = pageNumber
                                    if (selectedPeriod == null) {
                                        seasonState.hasMore = page.hasNextPage
                                    } else {
                                        pastHasMore = page.hasNextPage
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                moreError = "Nie udało się pobrać kolejnej strony sezonu."
                            } finally {
                                moreLoading = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun HomeNewEpisodeRow(
    event: EpisodeHistoryEvent,
    anime: Anime,
    userId: Int?,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(PocketRadius.control.dp),
        color = PocketPalette.surfaceRaised,
        border = BorderStroke(1.dp, PocketPalette.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.width(86.dp).height(54.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PocketPalette.surfaceVariant)
            ) {
                AnimeCover(anime, Modifier.fillMaxSize(), userId = userId)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = anime.title,
                    color = PocketPalette.onBackground,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Odc. ${event.episodeNumber} • ${formatRelativeDetectedDate(event.detectedAt)}",
                    color = PocketPalette.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = PocketPalette.primary
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "▶ Oglądaj",
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private val seasonSlugs = listOf("winter", "spring", "summer", "autumn")

private fun seasonName(slug: String): String = when (slug) {
    "winter" -> "Zima"
    "spring" -> "Wiosna"
    "summer" -> "Lato"
    else -> "Jesień"
}

private fun seasonPeriodLabel(period: SeasonPeriod) =
    "${period.year} · ${seasonName(period.slug).lowercase(Locale.forLanguageTag("pl-PL"))}"

internal fun shiftSeasonPeriod(period: SeasonPeriod, offset: Int): SeasonPeriod {
    val shifted = seasonPeriodIndex(period) + offset
    return SeasonPeriod(shifted / seasonSlugs.size, seasonSlugs[shifted % seasonSlugs.size])
}

private fun seasonPeriodIndex(period: SeasonPeriod) =
    period.year * seasonSlugs.size + seasonSlugs.indexOf(period.slug)

internal fun formatRelativeDetectedDate(timestamp: Long): String {
    if (timestamp <= 0L) return "Niedawno"
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = timestamp }
    val timeFmt = SimpleDateFormat("HH:mm", Locale("pl", "PL")).format(Date(timestamp))
    val sameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> "Dzisiaj, $timeFmt"
        sameYear && dayDiff == 1 -> "Wczoraj, $timeFmt"
        else -> SimpleDateFormat("d MMM, HH:mm", Locale("pl", "PL")).format(Date(timestamp))
    }
}
