
package com.example.shindenpocket.ui

import androidx.activity.compose.BackHandler
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shindenpocket.data.Anime
import com.example.shindenpocket.data.EpisodeNotification
import com.example.shindenpocket.data.OfflineLibraryStore
import com.example.shindenpocket.data.PlaybackPreferences
import com.example.shindenpocket.data.SeasonAnime
import com.example.shindenpocket.data.ShindenApi
import com.example.shindenpocket.data.ShindenAuth
import com.example.shindenpocket.data.WatchStatusOption
import com.example.shindenpocket.ui.theme.PocketPalette
import kotlinx.coroutines.CancellationException

private data class DetailBackTarget(val library: Anime?, val season: SeasonAnime?)

internal fun matchesLibrarySubTab(anime: Anime, subTab: Int): Boolean {
    val statusOpt = WatchStatusOption.fromCode(anime.watchStatus)
    val isCompleted = statusOpt == WatchStatusOption.COMPLETED ||
        (anime.total > 0 && anime.watched >= anime.total)
    return when (subTab) {
        0 -> anime.favorite
        1 -> true
        2 -> isCompleted
        3 -> !isCompleted && statusOpt != WatchStatusOption.DROPPED && statusOpt != WatchStatusOption.SKIP
        4 -> statusOpt == WatchStatusOption.IN_PROGRESS
        5 -> statusOpt == WatchStatusOption.PLANNED
        6 -> statusOpt == WatchStatusOption.ON_HOLD
        7 -> statusOpt == WatchStatusOption.DROPPED
        8 -> statusOpt == WatchStatusOption.SKIP
        9 -> anime.hasUserRating
        else -> true
    }
}





@Composable
internal fun ShindenPocketApp(
    notificationTarget: EpisodeNotification.Target? = null,
    onNotificationHandled: () -> Unit = {}
) {

    val context = LocalContext.current
    var restoringSession by remember { mutableStateOf(true) }
    var offlineProfile by remember { mutableStateOf<OfflineLibraryStore.Profile?>(null) }
    var libraryFromCache by remember { mutableStateOf(false) }
    var sessionRetryKey by remember { mutableIntStateOf(0) }

    // Aktualna zakładka: 0=Start, 1=Biblioteka, 2=Nowe Odcinki, 3=Konto
    var selectedTab by rememberSaveable {
        mutableIntStateOf(0)
    }

    
    var librarySubTab by rememberSaveable {
        mutableIntStateOf(1)
    }
    var libraryGridMode by rememberSaveable {
        mutableStateOf(false)
    }
    var librarySortIndex by rememberSaveable {
        mutableIntStateOf(0) // 0=Domyślnie, 1=Tytuł A-Z, 2=Postęp oglądania, 3=Ocena
    }
    var libraryShowSearch by rememberSaveable {
        mutableStateOf(false)
    }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    var detailBackStack by remember { mutableStateOf<List<DetailBackTarget>>(emptyList()) }

    
    var searchText by rememberSaveable {
        mutableStateOf("")
    }

    
    var selectedAnime by remember {
        mutableStateOf<Anime?>(null)
    }
    var selectedSeasonAnime by remember { mutableStateOf<SeasonAnime?>(null) }
    // Wyszukiwanie pełnego katalogu jest otwierane z karty na ekranie Start.
    var genreSearchOpen by rememberSaveable { mutableStateOf(false) }
    // ID wybranego wpisu historii — otwórz dokładnie ten odcinek, nie samą serię.
    var historyEpisodeToOpen by remember { mutableStateOf<Int?>(null) }
    var continueOnOpen by remember { mutableStateOf(false) }
    var recentPlaybackVersion by remember { mutableIntStateOf(0) }

    // Konto ustawiamy dopiero po potwierdzeniu przez serwer.
    var loggedInUser by remember {
        mutableStateOf<ShindenAuth.LoginResult.Success?>(null)
    }

    // Przy starcie próbujemy odtworzyć sesję z zaszyfrowanych cookies.
    LaunchedEffect(sessionRetryKey) {
        try {
            val verified = ShindenAuth.restoreSession(context)
            loggedInUser = verified
            if (verified != null) {
                OfflineLibraryStore.rememberProfile(context, verified.userId, verified.username)
                offlineProfile = null
            } else {
                // Nie udajemy aktywnej sesji. Pokazujemy wyłącznie dane do odczytu.
                offlineProfile = if (ShindenAuth.hasSavedSession(context))
                    OfflineLibraryStore.lastProfile(context) else null
            }
        } finally {
            restoringSession = false
        }
    }

    val offlineMode = loggedInUser == null && offlineProfile != null
    val activeUserId = loggedInUser?.userId ?: offlineProfile?.userId
    val activeUsername = loggedInUser?.username ?: offlineProfile?.name
    val scope = rememberCoroutineScope()

    // Stan sezonu trzymany nad pod-ekranami (szczegóły, gatunki, zakładki),
    // więc powrót na Start nie pobiera danych ponownie ani nie odtwarza listy.
    val seasonStartState = rememberSeasonStartState(
        restoringSession = restoringSession,
        userId = activeUserId,
        offlineMode = offlineMode
    )

    
    var allAnime by remember {
        mutableStateOf<List<Anime>>(emptyList())
    }
    // Otwieramy anime z powiadomienia DOPIERO po udanym pobraniu biblioteki.
    var libraryReadyForUserId by remember { mutableIntStateOf(-1) }
    var navigationNotice by remember { mutableStateOf<String?>(null) }

    
    var loading by remember {
        mutableStateOf(false)
    }

    
    var error by remember {
        mutableStateOf<String?>(null)
    }

    
    var refreshKey by remember {
        mutableIntStateOf(0)
    }

    
    val libraryScroll = rememberLazyListState()

    // Zmiana konta resetuje pozycję list, ręczne odświeżenie nie.
    LaunchedEffect(activeUserId) {
        libraryScroll.scrollToItem(0)
    }

    
    
    

    LaunchedEffect(activeUserId, offlineMode, refreshKey) {
        val userId = activeUserId
        // Dane biblioteki pozostają widoczne wyłącznie dla tego samego konta.
        // Zmiana konta / wylogowanie natychmiast usuwa cudze dane z UI.
        if (userId == null || libraryReadyForUserId != userId) {
            allAnime = emptyList()
            libraryReadyForUserId = -1
            libraryFromCache = false
        }
        error = null
        if (userId == null) {
            loading = false
            return@LaunchedEffect
        }

        loading = true
        try {
            // Najpierw przywrócenie cache — lista jest widoczna nawet bez sieci.
            if (allAnime.isEmpty()) {
                val saved = OfflineLibraryStore.loadLibrary(context, userId)
                if (saved != null) {
                    allAnime = saved
                    libraryReadyForUserId = userId
                    libraryFromCache = true
                }
            }
            if (offlineMode) {
                if (allAnime.isEmpty()) error = "Brak pobranej biblioteki offline."
                return@LaunchedEffect
            }
            // API nadal jest źródłem prawdy po odzyskaniu łączności.
            // Najpierw wypychamy ewentualne lokalne zmiany z trybu offline.
            OfflineLibraryStore.syncPendingUserEntries(context, userId)
            val fresh = ShindenApi.fetchAllAnime(userId)
            val merged = OfflineLibraryStore.mergeWithUserEntries(context, userId, fresh)
            allAnime = merged
            libraryReadyForUserId = userId
            libraryFromCache = false
            try { OfflineLibraryStore.saveLibrary(context, userId, merged) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Nie wstrzymuj poprawnych danych przy błędzie dysku */ }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Nieznany błąd"
        } finally {
            loading = false
        }
    }

    // Po synchronizacji odcinków podmieniamy również dane nagłówka
    // otwartych szczegółów, bez zamykania tego ekranu.
    LaunchedEffect(allAnime, activeUserId) {
        if (libraryReadyForUserId == activeUserId) {
            val opened = selectedAnime
            if (opened != null) {
                val updated = allAnime.firstOrNull { it.id == opened.id }
                if (updated != null && updated != opened) selectedAnime = updated
            }
        }
    }

    fun applyAnimeUpdate(updated: Anime) {
        val nextList = if (updated.isInUserLibrary) {
            val idx = allAnime.indexOfFirst { it.id == updated.id }
            if (idx >= 0) {
                allAnime.toMutableList().also { it[idx] = updated }
            } else {
                listOf(updated) + allAnime
            }
        } else {
            allAnime.filterNot { it.id == updated.id }
        }
        allAnime = nextList
        if (selectedAnime?.id == updated.id) {
            selectedAnime = updated
        }
    }

    fun openRelatedAnime(entry: SeasonAnime) {
        detailBackStack = detailBackStack + DetailBackTarget(selectedAnime, selectedSeasonAnime)
        val owned = allAnime.firstOrNull { it.id == entry.id }
        selectedAnime = owned
        selectedSeasonAnime = if (owned == null) entry else null
        continueOnOpen = false
        historyEpisodeToOpen = null
    }

    fun closeDetails() {
        val previous = detailBackStack.lastOrNull()
        detailBackStack = detailBackStack.dropLast(1)
        selectedAnime = previous?.library
        selectedSeasonAnime = previous?.season
        historyEpisodeToOpen = null
        continueOnOpen = false
    }

    // Powiadomienie jest obsługiwane przy zimnym starcie i w `onNewIntent`.
    // Nie otwieramy danych innego konta i nie próbujemy szukać anime przed pobraniem listy.
    LaunchedEffect(notificationTarget, restoringSession, loggedInUser?.userId,
        libraryReadyForUserId, error) {
        val target = notificationTarget ?: return@LaunchedEffect
        detailBackStack = emptyList()
        selectedSeasonAnime = null
        if (restoringSession) return@LaunchedEffect

        val activeId = loggedInUser?.userId
        if (activeId == null) {
            selectedAnime = null
            selectedTab = 3 // Konto — można zalogować się i wrócić do powiadomienia.
            return@LaunchedEffect
        }
        if (activeId != target.userId) {
            selectedAnime = null
            selectedTab = 3
            navigationNotice = "Powiadomienie dotyczy innego konta Shinden. " +
                "Przełącz konto i otwórz powiadomienie ponownie."
            onNotificationHandled()
            return@LaunchedEffect
        }
        selectedTab = 2 // Po cofnięciu ze szczegółów wracamy do Nowych Odcinków.
        if (libraryReadyForUserId != activeId) {
            if (error != null) {
                navigationNotice = "Nie udało się pobrać biblioteki. " +
                    "Wciśnij Odśwież bibliotekę, aby otworzyć anime z powiadomienia."
            }
            return@LaunchedEffect
        }
        navigationNotice = null
        val anime = allAnime.firstOrNull { it.id == target.animeId }
        if (anime != null) {
            historyEpisodeToOpen = target.episodeId
            continueOnOpen = false
            selectedAnime = anime
            searchText = ""
            target.episodeId?.let { epId ->
                try {
                    com.example.shindenpocket.data.EpisodeSnapshotStore.markHistoryEpisodeRead(
                        context, activeId, target.animeId, epId
                    )
                } catch (_: Exception) {
                }
            }
        } else {
            historyEpisodeToOpen = null
            selectedAnime = null
            navigationNotice = "Nie ma już tego anime w Twojej bibliotece."
        }
        onNotificationHandled()
    }

    
    
    

    val detailsOpen = selectedAnime != null || selectedSeasonAnime != null
    BackHandler(enabled = detailsOpen || genreSearchOpen) {
        if (detailsOpen) closeDetails() else genreSearchOpen = false
    }

    
    val tabs = listOf("Start", "Biblioteka", "Nowe Odcinki", "Konto")

    // Ekran Start i lista anime korzystają z tej samej historii.
    val recentPlayback = remember(
        context, activeUserId, libraryReadyForUserId, allAnime, recentPlaybackVersion
    ) {
        val id = activeUserId
        if (id == null || libraryReadyForUserId != id) emptyList() else {
            val byId = allAnime.associateBy { it.id }
            PlaybackPreferences.recent(context, id).mapNotNull { record ->
                byId[record.animeId]?.let { anime -> anime to record }
            }.take(6)
        }
    }

    Scaffold(
        bottomBar = {
            if (selectedAnime == null && selectedSeasonAnime == null && !genreSearchOpen) {
                PocketNavigationBar(selectedTab = selectedTab) { index ->
                    selectedTab = index
                    searchText = ""
                    navigationNotice = null
                }
            }
        }
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {

            val detailsAnime = selectedAnime

            
            
            

            if (detailsAnime != null) {

                AnimeDetails(
                    anime = detailsAnime,
                    userId = activeUserId,
                    username = activeUsername,
                    offlineMode = offlineMode,
                    onBack = { closeDetails() },
                    onRelatedClick = { entry -> openRelatedAnime(entry) },
                    onWatchedChanged = { refreshKey++ },
                    onAnimeUpdated = { updated -> applyAnimeUpdate(updated) },
                    continueOnOpen = continueOnOpen,
                    initialEpisodeId = historyEpisodeToOpen,
                    onInitialEpisodeOpened = { historyEpisodeToOpen = null },
                    onPlaybackOpened = { recentPlaybackVersion++ }
                )

            } else if (selectedSeasonAnime != null) {

                val seasonTarget = selectedSeasonAnime!!
                SeasonAnimeDetailsScreen(
                    anime = seasonTarget,
                    userId = activeUserId,
                    username = activeUsername,
                    libraryAnime = allAnime.firstOrNull { it.id == seasonTarget.id },
                    offlineMode = offlineMode,
                    onBack = { closeDetails() },
                    onAnimeUpdated = { updated -> applyAnimeUpdate(updated) },
                    onRelatedClick = { entry -> openRelatedAnime(entry) }
                )

            } else if (genreSearchOpen) {

                
                SearchScreen(
                    offlineMode = offlineMode,
                    library = allAnime,
                    userId = activeUserId,
                    onBack = { genreSearchOpen = false },
                    onAnimeClick = { entry ->
                        val known = allAnime.firstOrNull { it.id == entry.id }
                        if (known != null) {
                            continueOnOpen = false
                            selectedAnime = known
                        } else {
                            // Szczegóły są dostępne również dla anime spoza biblioteki.
                            selectedSeasonAnime = entry
                        }
                    }
                )

            } else {

                
                
                

                if (selectedTab == 0) {
                    PocketTopBar(
                        onSearch = { genreSearchOpen = true },
                        onAccount = { selectedTab = 3 }
                    )
                    Spacer(Modifier.height(4.dp))
                }

                if (offlineMode || libraryFromCache) {
                    PocketMessage(
                        message = if (offlineMode) "Tryb offline · biblioteka tylko do odczytu"
                        else "Zapisana biblioteka · sprawdzam aktualizacje",
                        modifier = Modifier.padding(bottom = 10.dp),
                        actionLabel = if (offlineMode) "Połącz" else null,
                        onAction = if (offlineMode) ({ sessionRetryKey++ }) else null
                    )
                }
                navigationNotice?.let { message ->
                    PocketMessage(message = message, modifier = Modifier.padding(bottom = 8.dp))
                }

                if (restoringSession) {

                    PocketLoadingState("Sprawdzam zapisaną sesję Shindena…")

                } else if (selectedTab == 3) {

                    LoginScreen(
                        loggedInUser = loggedInUser,
                        library = allAnime,
                        onSearch = { genreSearchOpen = true },
                        onOpenFavorites = {
                            librarySubTab = 0
                            selectedTab = 1
                            searchText = ""
                        },
                        onOpenLibrarySubTab = { subTabIdx ->
                            librarySubTab = subTabIdx
                            selectedTab = 1
                            searchText = ""
                        },
                        onLoginSuccess = { user ->
                            offlineProfile = null
                            OfflineLibraryStore.rememberProfile(context, user.userId, user.username)
                            loggedInUser = user
                            detailBackStack = emptyList()
                            refreshKey++
                        },
                        onClearSession = {
                            loggedInUser = null
                            detailBackStack = emptyList()
                            offlineProfile = null
                            allAnime = emptyList()
                            selectedAnime = null
                            selectedSeasonAnime = null
                            continueOnOpen = false
                            navigationNotice = null
                            error = null
                        }
                    )

                } else if (selectedTab == 2 && offlineMode) {
                    PocketScreenHeader(
                        title = "Nowe odcinki",
                        subtitle = "Premiery obserwowanych serii",
                        actions = {
                            IconButton(
                                onClick = { genreSearchOpen = true },
                                modifier = Modifier.size(48.dp)
                            ) { Text("⌕", fontSize = 22.sp) }
                        }
                    )
                    PocketEmptyState(
                        title = "Premiery są niedostępne offline",
                        message = "Połącz się z internetem i przywróć sesję, aby sprawdzić nowe odcinki.",
                        symbol = "⚡"
                    )
                } else if (selectedTab == 2) {

                    NewEpisodesScreen(
                        allAnime = allAnime,
                        userId = loggedInUser?.userId,
                        loading = loading,
                        error = error,
                        onSearch = { genreSearchOpen = true },
                        onAnimeClick = { anime ->
                            historyEpisodeToOpen = null
                            continueOnOpen = false
                            selectedAnime = anime
                        },
                        onEpisodeClick = { anime, episodeId ->
                            historyEpisodeToOpen = episodeId
                            continueOnOpen = false
                            selectedAnime = anime
                        }
                    )

                } else if (selectedTab == 0) {

                    
                    SeasonStartScreen(
                        userId = activeUserId,
                        library = allAnime,
                        recent = recentPlayback,
                        offlineMode = offlineMode,
                        seasonState = seasonStartState,
                        onMyList = {
                            librarySubTab = 3 
                            selectedTab = 1
                            searchText = ""
                        },
                        onNewEpisodes = { selectedTab = 2 },
                        onFavorites = {
                            librarySubTab = 0 
                            selectedTab = 1
                            searchText = ""
                        },
                        onOpenNewEpisode = { event ->
                            val known = allAnime.firstOrNull { it.id == event.titleId }
                            if (known != null) {
                                historyEpisodeToOpen = event.episodeId
                                continueOnOpen = false
                                selectedAnime = known
                                activeUserId?.let { id ->
                                    try { com.example.shindenpocket.data.EpisodeSnapshotStore.markHistoryEpisodeRead(
                                        context, id, event.titleId, event.episodeId
                                    ) } catch (_: Exception) { }
                                }
                            }
                        },
                        onOpenGenreSearch = { genreSearchOpen = true },
                        onOpenSeasonPage = {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://shinden.pl/series/season/current")))
                            } catch (_: Exception) {
                                navigationNotice = "Nie można otworzyć strony sezonu."
                            }
                        },
                        onOpenAnime = { anime, shouldContinue ->
                            continueOnOpen = shouldContinue
                            selectedAnime = anime
                        },
                        onSeasonClick = { entry ->
                            val known = allAnime.firstOrNull { it.id == entry.id }
                            if (known != null) {
                                continueOnOpen = false
                                selectedAnime = known
                            } else {
                                // Natywne szczegóły są dostępne również dla anime spoza biblioteki.
                                // W trybie offline ekran wyświetli stosowny komunikat.
                                selectedSeasonAnime = entry
                            }
                        }
                    )

                } else if (activeUserId == null) {

                    PocketScreenHeader(title = "Biblioteka")
                    PocketEmptyState(
                        title = "Twoja biblioteka czeka na Ciebie",
                        message = "Zaloguj się w zakładce Konto, aby pobrać swoją listę i ulubione anime z Shindena.",
                        symbol = "▣",
                        actionLabel = "Przejdź do konta",
                        onAction = { selectedTab = 3 }
                    )

                } else {

                    
                    
                    
                    // 4 = Oglądam, 5 = Planuję, 6 = Wstrzymane, 7 = Porzucone, 8 = Pomijam, 9 = Ocenione
                    
                    val visibleAnime = remember(allAnime, librarySubTab, searchText, librarySortIndex) {
                        val filtered = allAnime.filter { anime ->
                            matchesLibrarySubTab(anime, librarySubTab) &&
                                anime.title.contains(searchText.trim(), ignoreCase = true)
                        }
                        when (librarySortIndex) {
                            1 -> filtered.sortedBy { it.title.lowercase() }
                            2 -> filtered.sortedByDescending { a ->
                                if (a.total > 0) a.watched.toFloat() / a.total else 0f
                            }
                            3 -> filtered.sortedWith(
                                compareByDescending<Anime> { it.rateTotal ?: 0 }
                                    .thenByDescending { it.rating.replace(',', '.').toFloatOrNull() ?: 0f }
                            )
                            else -> filtered
                        }
                    }

                    val visibleRecent = remember(recentPlayback, searchText) {
                        if (searchText.isBlank()) recentPlayback
                        else recentPlayback.filter { it.first.title.contains(searchText.trim(), ignoreCase = true) }
                    }

                    val subTabCounts = remember(allAnime) {
                        (0..9).map { tabIdx -> allAnime.count { matchesLibrarySubTab(it, tabIdx) } }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        PocketScreenHeader(
                            title = "Biblioteka",
                            subtitle = "${visibleAnime.size} pozycji • ${
                                when (librarySortIndex) {
                                    1 -> "Sortowanie: A–Z"
                                    2 -> "Sortowanie: Postęp"
                                    3 -> "Sortowanie: Ocena"
                                    else -> "Zsynchronizowano z Shinden"
                                }
                            }",
                            actions = {
                                IconButton(
                                    onClick = { libraryShowSearch = !libraryShowSearch },
                                    modifier = Modifier.size(42.dp).semantics {
                                        contentDescription = "Filtruj bibliotekę"
                                    }
                                ) {
                                    Text(
                                        "⌕",
                                        fontSize = 21.sp,
                                        color = if (libraryShowSearch || searchText.isNotBlank()) PocketPalette.primary else PocketPalette.onBackground
                                    )
                                }
                                Box {
                                    IconButton(
                                        onClick = { sortMenuExpanded = true },
                                        modifier = Modifier.size(42.dp).semantics {
                                            contentDescription = "Sortuj bibliotekę"
                                        }
                                    ) {
                                        Text(
                                            "⇅",
                                            fontSize = 19.sp,
                                            color = if (librarySortIndex != 0) PocketPalette.primary else PocketPalette.onBackground
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = sortMenuExpanded,
                                        onDismissRequest = { sortMenuExpanded = false }
                                    ) {
                                        listOf(
                                            0 to "Domyślna kolejność",
                                            1 to "Tytuł (A–Z)",
                                            2 to "Postęp oglądania",
                                            3 to "Najwyższa ocena"
                                        ).forEach { (idx, label) ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        label,
                                                        fontWeight = if (librarySortIndex == idx) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (librarySortIndex == idx) PocketPalette.primarySoft else PocketPalette.onBackground
                                                    )
                                                },
                                                onClick = {
                                                    librarySortIndex = idx
                                                    sortMenuExpanded = false
                                                }
                                            )
                                        }
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("Szukaj w całym katalogu Shinden ↗") },
                                            onClick = {
                                                sortMenuExpanded = false
                                                genreSearchOpen = true
                                            }
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { libraryGridMode = !libraryGridMode },
                                    modifier = Modifier.size(42.dp).semantics {
                                        contentDescription = if (libraryGridMode) "Widok listy" else "Widok siatki plakatów"
                                    }
                                ) {
                                    Text(
                                        if (libraryGridMode) "☰" else "⊞",
                                        fontSize = 19.sp,
                                        color = if (libraryGridMode) PocketPalette.primarySoft else PocketPalette.onBackground
                                    )
                                }
                                IconButton(
                                    onClick = { refreshKey++ },
                                    enabled = !loading && !offlineMode,
                                    modifier = Modifier.size(42.dp).semantics {
                                        contentDescription = "Odśwież bibliotekę"
                                    }
                                ) { Text("↻", fontSize = 20.sp) }
                            }
                        )

                        
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            listOf(
                                Triple(0, "♥", "Ulubione"),
                                Triple(1, "⊞", "Wszystkie"),
                                Triple(2, "✓", "Obejrzane"),
                                Triple(3, "◉", "Nieobejrzane"),
                                Triple(4, "▶", "Oglądam"),
                                Triple(5, "◷", "Planuję"),
                                Triple(6, "⏸", "Wstrzymane"),
                                Triple(7, "✕", "Porzucone"),
                                Triple(8, "⏭", "Pomijam"),
                                Triple(9, "★", "Ocenione")
                            ).forEach { (idx, icon, label) ->
                                val active = librarySubTab == idx
                                val count = subTabCounts.getOrElse(idx) { 0 }
                                Surface(
                                    onClick = { librarySubTab = idx },
                                    shape = CircleShape,
                                    color = if (active) PocketPalette.primary else PocketPalette.surfaceRaised,
                                    border = if (active) null else BorderStroke(1.dp, PocketPalette.outlineVariant.copy(alpha = 0.72f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = icon,
                                            color = if (active) Color.White else PocketPalette.primarySoft,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = label,
                                            color = if (active) Color.White else PocketPalette.onSurfaceVariant,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium
                                        )
                                        if (count > 0) {
                                            Text(
                                                text = count.toString(),
                                                color = if (active) Color.White.copy(alpha = 0.85f) else PocketPalette.onSurfaceVariant.copy(alpha = 0.7f),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        if (libraryShowSearch || searchText.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            PocketSearchField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                placeholder = "Szukaj w bibliotece…"
                            )
                        }
                        Spacer(Modifier.height(10.dp))

                        if (loading && allAnime.isNotEmpty()) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                        }
                        if (error != null && allAnime.isNotEmpty()) {
                            PocketMessage(
                                message = "Nie udało się odświeżyć. Widoczne są ostatnio pobrane dane.",
                                type = PocketMessageType.ERROR,
                                actionLabel = "Ponów",
                                onAction = { refreshKey++ }
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        when {
                            loading && allAnime.isEmpty() -> {
                                PocketLoadingState("Pobieram Twoją bibliotekę…", Modifier.weight(1f))
                            }
                            error != null && allAnime.isEmpty() -> {
                                PocketErrorState(
                                    title = "Nie udało się pobrać biblioteki",
                                    message = "Sprawdź połączenie z internetem i spróbuj ponownie. Twoje zapisane dane pozostają bez zmian.",
                                    onRetry = { refreshKey++ }
                                )
                            }
                            visibleAnime.isEmpty() && visibleRecent.isEmpty() -> {
                                PocketEmptyState(
                                    title = if (searchText.isNotBlank()) "Brak wyników wyszukiwania"
                                    else when (librarySubTab) {
                                        0 -> "Nie masz jeszcze ulubionych anime"
                                        2 -> "Brak obejrzanych serii"
                                        3 -> "Brak nieobejrzanych serii"
                                        else -> "Twoja biblioteka jest pusta"
                                    },
                                    message = if (searchText.isNotBlank()) "Spróbuj wpisać inną nazwę anime lub wyczyść filtr."
                                    else "Serie z Twojego konta Shinden pojawią się w tej zakładce automatycznie.",
                                    symbol = if (librarySubTab == 0) "♥" else "▣",
                                    actionLabel = if (searchText.isNotBlank()) "Wyczyść wyszukiwanie" else null,
                                    onAction = if (searchText.isNotBlank()) ({ searchText = "" }) else null
                                )
                            }
                            else -> {
                                LazyColumn(
                                    modifier = Modifier.weight(1f),
                                    state = libraryScroll,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    contentPadding = PaddingValues(bottom = 20.dp)
                                ) {
                                    if (visibleRecent.isNotEmpty() && librarySubTab != 2) {
                                        item(key = "recent_playback_8b") {
                                            Column(
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.padding(bottom = 4.dp)
                                            ) {
                                                PocketSectionHeader("Ostatnio odtwarzane")
                                                LazyRow(
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    items(
                                                        items = visibleRecent,
                                                        key = { it.first.id }
                                                    ) { (recentAnime, record) ->
                                                        RecentPlaybackCard(
                                                            anime = recentAnime,
                                                            recent = record,
                                                            onContinue = {
                                                                continueOnOpen = !offlineMode
                                                                selectedAnime = recentAnime
                                                            },
                                                            onDetails = {
                                                                continueOnOpen = false
                                                                selectedAnime = recentAnime
                                                            },
                                                            userId = activeUserId,
                                                            offlineMode = offlineMode
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    if (visibleAnime.isEmpty()) {
                                        item(key = "empty_library_section") {
                                            PocketEmptyState(
                                                title = "Brak pasujących anime w tej zakładce",
                                                message = "Przełącz zakładkę powyżej lub zmień kryteria wyszukiwania.",
                                                symbol = if (librarySubTab == 0) "♥" else "▣"
                                            )
                                        }
                                    } else if (libraryGridMode) {
                                        items(
                                            items = visibleAnime.chunked(3),
                                            key = { row -> "grid_row_${row.first().id}" }
                                        ) { row ->
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                row.forEach { anime ->
                                                    PocketPoster(
                                                        title = anime.title,
                                                        coverUrl = getCoverUrl(anime.coverId),
                                                        libraryAnime = anime,
                                                        userId = activeUserId,
                                                        onClick = {
                                                            continueOnOpen = false
                                                            selectedAnime = anime
                                                        },
                                                        modifier = Modifier.weight(1f),
                                                        style = PocketCardStyle.POSTER
                                                    )
                                                }
                                                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                            }
                                        }
                                    } else {
                                        items(items = visibleAnime, key = { it.id }) { anime ->
                                            AnimeCard(
                                                anime = anime,
                                                onClick = {
                                                    continueOnOpen = false
                                                    selectedAnime = anime
                                                },
                                                onContinueClick = if (!offlineMode) ({
                                                    continueOnOpen = true
                                                    selectedAnime = anime
                                                }) else null,
                                                userId = activeUserId
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
