package com.example.shindenpocket.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shindenpocket.data.Anime
import com.example.shindenpocket.data.CatalogTag
import com.example.shindenpocket.data.GenreCatalogApi
import com.example.shindenpocket.data.SearchAnime
import com.example.shindenpocket.data.SearchApi
import com.example.shindenpocket.data.SearchFilters
import com.example.shindenpocket.data.SearchSeason
import com.example.shindenpocket.data.SearchSort
import com.example.shindenpocket.data.SearchStatus
import com.example.shindenpocket.data.SearchTagFilter
import com.example.shindenpocket.data.SearchTagMode
import com.example.shindenpocket.data.SearchType
import com.example.shindenpocket.data.SeasonAnime
import com.example.shindenpocket.data.SubtitleGroup
import com.example.shindenpocket.ui.theme.PocketPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val SearchAccent = PocketPalette.primary
private val SearchChipBg = PocketPalette.surfaceVariant
private val SearchChipText = PocketPalette.primarySoft
private val SearchMuted = PocketPalette.onSurfaceVariant
private val SearchTitle = PocketPalette.onBackground
private val SearchHairline = PocketPalette.outlineVariant

private val SearchFiltersSaver = Saver<SearchFilters, List<String>>(
    save = {
        listOf(
            it.sort.name,
            it.tags.joinToString("\u001e") { tag -> "${tag.id}\u001f${tag.mode.name}\u001f${tag.name}" },
            it.types.joinToString(",") { type -> type.name },
            it.statuses.joinToString(",") { status -> status.name },
            it.yearFrom?.toString().orEmpty(),
            it.yearTo?.toString().orEmpty(),
            it.exactYear?.toString().orEmpty(),
            it.season?.name.orEmpty(),
            it.subtitleGroupId?.toString().orEmpty(),
            it.subtitleGroupName.orEmpty(),
            it.onlineOnly.toString(),
            it.ratingFrom?.toString().orEmpty(),
            it.ratingTo?.toString().orEmpty(),
            it.episodesFrom?.toString().orEmpty(),
            it.episodesTo?.toString().orEmpty(),
            it.lengthFrom?.toString().orEmpty(),
            it.lengthTo?.toString().orEmpty()
        )
    },
    restore = { values ->
        SearchFilters(
            sort = values.getOrNull(0)?.let { runCatching { SearchSort.valueOf(it) }.getOrNull() }
                ?: SearchSort.POPULARITY,
            tags = values.getOrNull(1).orEmpty().split('\u001e').mapNotNull { encoded ->
                val parts = encoded.split('\u001f', limit = 3)
                val id = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
                val mode = parts.getOrNull(1)?.let {
                    runCatching { SearchTagMode.valueOf(it) }.getOrNull()
                } ?: SearchTagMode.INCLUDE
                parts.getOrNull(2)?.takeIf(String::isNotEmpty)?.let { SearchTagFilter(id, it, mode) }
            },
            types = values.getOrNull(2).orEmpty().split(',').mapNotNull {
                runCatching { SearchType.valueOf(it) }.getOrNull()
            }.toSet(),
            statuses = values.getOrNull(3).orEmpty().split(',').mapNotNull {
                runCatching { SearchStatus.valueOf(it) }.getOrNull()
            }.toSet(),
            yearFrom = values.getOrNull(4)?.toIntOrNull(),
            yearTo = values.getOrNull(5)?.toIntOrNull(),
            exactYear = values.getOrNull(6)?.toIntOrNull(),
            season = values.getOrNull(7)?.takeIf(String::isNotEmpty)?.let {
                runCatching { SearchSeason.valueOf(it) }.getOrNull()
            },
            subtitleGroupId = values.getOrNull(8)?.toIntOrNull(),
            subtitleGroupName = values.getOrNull(9)?.takeIf(String::isNotEmpty),
            onlineOnly = values.getOrNull(10).toBoolean(),
            ratingFrom = values.getOrNull(11)?.toIntOrNull(),
            ratingTo = values.getOrNull(12)?.toIntOrNull(),
            episodesFrom = values.getOrNull(13)?.toIntOrNull(),
            episodesTo = values.getOrNull(14)?.toIntOrNull(),
            lengthFrom = values.getOrNull(15)?.toIntOrNull(),
            lengthTo = values.getOrNull(16)?.toIntOrNull()
        )
    }
)

@Composable
internal fun SearchScreen(
    offlineMode: Boolean,
    library: List<Anime>,
    userId: Int?,
    onBack: () -> Unit,
    onAnimeClick: (SeasonAnime) -> Unit
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val initialFilters = remember { SearchApi.loadSavedFilters(context) ?: SearchFilters() }

    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    var filters by rememberSaveable(stateSaver = SearchFiltersSaver) { mutableStateOf(initialFilters) }
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    var results by remember { mutableStateOf<List<SearchAnime>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var maxPage by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cached by remember { mutableStateOf(false) }
    var moreError by remember { mutableStateOf<String?>(null) }
    var moreToken by remember { mutableIntStateOf(0) }

    var tags by remember { mutableStateOf(GenreCatalogApi.loadCachedTags(context)) }
    var subtitleGroups by remember { mutableStateOf(SearchApi.loadSubtitleGroups(context)) }
    val libraryById = remember(library) { library.associateBy { it.id } }

    LaunchedEffect(query) {
        val value = query.trim()
        if (value == submitted) return@LaunchedEffect
        if (value.isNotEmpty()) delay(450)
        submitted = value
    }

    LaunchedEffect(Unit) {
        if (!offlineMode) {
            launch {
                runCatching { GenreCatalogApi.fetchTags() }.getOrNull()?.let { fresh ->
                    tags = fresh
                    withContext(Dispatchers.IO) { GenreCatalogApi.saveTags(context, fresh) }
                }
            }
            launch {
                runCatching { SearchApi.fetchSubtitleGroups() }.getOrNull()?.let { fresh ->
                    subtitleGroups = fresh
                    withContext(Dispatchers.IO) { SearchApi.saveSubtitleGroups(context, fresh) }
                }
            }
        }
    }

    LaunchedEffect(submitted, filters, reload) {
        results = emptyList()
        page = 1
        maxPage = 1
        error = null
        moreError = null
        cached = false
        listState.scrollToItem(0)
        loading = true
        try {
            val result = SearchApi.search(context, submitted, filters, 1, offlineMode)
            results = result.anime
            maxPage = result.maxPage.coerceAtLeast(1)
            cached = result.cached
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Nie udało się pobrać wyników."
        } finally {
            loading = false
        }
    }

    if (showFilters) {
        SearchFilterSheet(
            applied = filters,
            tags = tags,
            subtitleGroups = subtitleGroups,
            onDismiss = { showFilters = false },
            onApply = {
                filters = it
                showFilters = false
            },
            onSave = {
                SearchApi.saveFilters(context, it)
                filters = it
                showFilters = false
            }
        )
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
    ) {
        item(key = "search_header") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PocketBackButton(label = "Wróć", onClick = onBack)
                PocketScreenHeader(
                    title = "Szukaj anime",
                    subtitle = "Pełny katalog Shinden z łączonymi filtrami."
                )
                if (offlineMode) {
                    PocketMessage("Tryb offline — dostępne są tylko wcześniej zapisane wyniki.")
                }
            }
        }

        item(key = "search_controls") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PocketSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Tytuł anime…",
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        submitted = query.trim()
                        keyboard?.hide()
                    })
                )
                Surface(
                    onClick = { showFilters = true },
                    modifier = Modifier.size(56.dp).semantics {
                        contentDescription = "Otwórz filtry wyszukiwania"
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = if (filters.activeCount > 0) SearchAccent else SearchChipBg,
                    border = BorderStroke(1.dp, if (filters.activeCount > 0) SearchAccent else SearchHairline)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            if (filters.activeCount > 0) "≡ ${filters.activeCount}" else "≡",
                            color = if (filters.activeCount > 0) Color.White else SearchChipText,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (filters.activeCount > 0) {
            item(key = "active_filters") {
                ActiveFilterChips(
                    filters = filters,
                    onChange = { filters = it },
                    onOpen = { showFilters = true }
                )
            }
        }

        item(key = "sort_and_count") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (submitted.isBlank()) "Katalog anime" else "Wyniki dla „$submitted”",
                        style = MaterialTheme.typography.titleMedium,
                        color = SearchTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Strona $page z $maxPage · ${results.size} pozycji",
                        fontSize = 11.sp,
                        color = SearchMuted
                    )
                }
                Box {
                    TextButton(
                        onClick = { sortMenu = true },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Text("Sortuj: ${filters.sort.label}")
                    }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SearchSort.entries.forEach { sort ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        sort.label,
                                        fontWeight = if (filters.sort == sort) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    filters = filters.copy(sort = sort)
                                    sortMenu = false
                                }
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { reload++ },
                    modifier = Modifier.size(48.dp).semantics {
                        contentDescription = "Odśwież wyniki wyszukiwania"
                    }
                ) {
                    Text("↻", fontSize = 18.sp, color = SearchChipText)
                }
            }
        }

        if (cached) {
            item(key = "cached") {
                PocketMessage("Wyniki z pamięci — wyświetlamy ostatnio zapisane dane.")
            }
        }
        if (loading && results.isEmpty()) {
            item(key = "loading") { PocketLoadingState("Szukam anime w katalogu…") }
        }
        error?.let { message ->
            item(key = "error") { InlineError(message) { reload++ } }
        }
        if (!loading && error == null && results.isEmpty()) {
            item(key = "empty") {
                PocketEmptyState(
                    title = "Brak wyników",
                    message = "Zmień frazę lub usuń część filtrów.",
                    symbol = "⌕"
                )
            }
        }
        itemsIndexed(results.chunked(2), key = { index, _ -> "search_row_$index" }) { _, row ->
            PosterRow(row, libraryById, userId, onAnimeClick)
        }
        if (results.isNotEmpty() && page < maxPage) {
            item(key = "more_$page") {
                if (moreError != null) {
                    InlineError(moreError!!) {
                        moreError = null
                        moreToken++
                    }
                } else {
                    Box(
                        Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = SearchAccent,
                            strokeWidth = 2.dp
                        )
                    }
                    LaunchedEffect(page, moreToken) {
                        if (loading) return@LaunchedEffect
                        loading = true
                        try {
                            val next = SearchApi.search(
                                context, submitted, filters, page + 1, offlineMode
                            )
                            val known = results.mapTo(hashSetOf()) { it.id }
                            results = results + next.anime.filter { it.id !in known }
                            page++
                            maxPage = next.maxPage.coerceAtLeast(1)
                            cached = cached || next.cached
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            moreError = e.message ?: "Nie udało się pobrać kolejnej strony."
                        } finally {
                            loading = false
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActiveFilterChips(
    filters: SearchFilters,
    onChange: (SearchFilters) -> Unit,
    onOpen: () -> Unit
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(filters.tags, key = { "tag_${it.id}" }) { tag ->
            RemovableChip(
                "${tagModeLabel(tag.mode)}: ${tag.name}",
                onRemove = { onChange(filters.copy(tags = filters.tags.filterNot { it.id == tag.id })) }
            )
        }
        if (filters.types.isNotEmpty()) {
            item { RemovableChip(filters.types.joinToString { it.label }) { onChange(filters.copy(types = emptySet())) } }
        }
        if (filters.statuses.isNotEmpty()) {
            item { RemovableChip(filters.statuses.joinToString { it.label }) { onChange(filters.copy(statuses = emptySet())) } }
        }
        if (filters.yearFrom != null || filters.yearTo != null) {
            item {
                RemovableChip("Lata ${filters.yearFrom ?: "…"}–${filters.yearTo ?: "…"}") {
                    onChange(filters.copy(yearFrom = null, yearTo = null))
                }
            }
        }
        if (filters.exactYear != null || filters.season != null) {
            item {
                RemovableChip(
                    listOfNotNull(filters.season?.label, filters.exactYear?.toString()).joinToString(" ")
                ) { onChange(filters.copy(exactYear = null, season = null)) }
            }
        }
        filters.subtitleGroupName?.let { name ->
            item {
                RemovableChip("Napisy: $name") {
                    onChange(filters.copy(subtitleGroupId = null, subtitleGroupName = null))
                }
            }
        }
        if (filters.onlineOnly) {
            item { RemovableChip("Dostępne online") { onChange(filters.copy(onlineOnly = false)) } }
        }
        if (filters.ratingFrom != null || filters.ratingTo != null) {
            item {
                RemovableChip("Ocena ${filters.ratingFrom ?: "…"}–${filters.ratingTo ?: "…"}") {
                    onChange(filters.copy(ratingFrom = null, ratingTo = null))
                }
            }
        }
        if (filters.episodesFrom != null || filters.episodesTo != null) {
            item {
                RemovableChip("Odc. ${filters.episodesFrom ?: "…"}–${filters.episodesTo ?: "…"}") {
                    onChange(filters.copy(episodesFrom = null, episodesTo = null))
                }
            }
        }
        if (filters.lengthFrom != null || filters.lengthTo != null) {
            item {
                RemovableChip("Minuty ${filters.lengthFrom ?: "…"}–${filters.lengthTo ?: "…"}") {
                    onChange(filters.copy(lengthFrom = null, lengthTo = null))
                }
            }
        }
        item { SelectChip("Edytuj", false, onOpen) }
    }
}

@Composable
private fun RemovableChip(label: String, onRemove: () -> Unit) {
    Surface(
        onClick = onRemove,
        modifier = Modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(50),
        color = SearchChipBg,
        border = BorderStroke(1.dp, SearchAccent)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, maxLines = 1, color = SearchChipText)
            Text("×", color = SearchAccent, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SearchFilterSheet(
    applied: SearchFilters,
    tags: List<CatalogTag>,
    subtitleGroups: List<SubtitleGroup>,
    onDismiss: () -> Unit,
    onApply: (SearchFilters) -> Unit,
    onSave: (SearchFilters) -> Unit
) {
    var draft by remember(applied) { mutableStateOf(applied) }
    var tagQuery by remember { mutableStateOf("") }
    var tagType by remember { mutableStateOf("genre") }
    var showAllTags by remember { mutableStateOf(false) }
    var subtitleMenu by remember { mutableStateOf(false) }
    var validation by remember { mutableStateOf<String?>(null) }
    val matchingTags = remember(tags, tagQuery, tagType) {
        tags.asSequence()
            .filter { tagType == "all" || it.type == tagType }
            .filter { tagQuery.isBlank() || it.name.contains(tagQuery.trim(), ignoreCase = true) }
            .take(80)
            .toList()
    }
    val visibleTags = if (showAllTags) matchingTags else matchingTags.take(6)

    LaunchedEffect(tagQuery, tagType) {
        showAllTags = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = PocketPalette.surface) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().imePadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Filtry katalogu", style = MaterialTheme.typography.headlineSmall, color = SearchTitle)
                Text(
                    "Filtry są łączone przez wyszukiwarkę Shinden.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SearchMuted
                )
            }
            item { FilterHeading("Gatunki i tagi") }
            item {
                OutlinedTextField(
                    value = tagQuery,
                    onValueChange = { tagQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Szukaj tagu…") },
                    singleLine = true,
                    colors = filterFieldColors()
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(
                        listOf(
                            "genre" to "Gatunki", "tag" to "Motywy", "place" to "Miejsce",
                            "entity" to "Postacie", "target_group" to "Grupy",
                            "source" to "Pierwowzór", "production_type" to "Produkcja", "all" to "Wszystkie"
                        )
                    ) { (value, label) ->
                        SelectChip(label, tagType == value) { tagType = value }
                    }
                }
            }
            if (tags.isEmpty()) {
                item { Text("Lista tagów jest niedostępna.", color = SearchMuted) }
            } else {
                items(visibleTags, key = { "sheet_tag_${it.id}" }) { tag ->
                    val selected = draft.tags.firstOrNull { it.id == tag.id }
                    Surface(
                        onClick = {
                            val next = when (selected?.mode) {
                                null -> SearchTagMode.INCLUDE
                                SearchTagMode.INCLUDE -> SearchTagMode.ONLY
                                SearchTagMode.ONLY -> SearchTagMode.EXCLUDE
                                SearchTagMode.EXCLUDE -> null
                            }
                            draft = draft.copy(
                                tags = draft.tags.filterNot { it.id == tag.id } +
                                    listOfNotNull(next?.let { SearchTagFilter(tag.id, tag.name, it) })
                            )
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = if (selected != null) SearchChipBg else Color.Transparent,
                        border = BorderStroke(1.dp, if (selected != null) SearchAccent else SearchHairline)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(tag.name, Modifier.weight(1f), color = SearchTitle)
                            Text(
                                selected?.mode?.let(::tagModeLabel) ?: "Dodaj",
                                color = if (selected != null) SearchAccent else SearchMuted,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                if (matchingTags.size > 6) {
                    item {
                        TextButton(
                            onClick = { showAllTags = !showAllTags },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Text(if (showAllTags) "Pokaż mniej" else "Więcej (${matchingTags.size - 6})")
                        }
                    }
                }
                if (showAllTags && matchingTags.size == 80) {
                    item { Text("Wpisz nazwę, aby zawęzić listę tagów.", color = SearchMuted, fontSize = 12.sp) }
                }
            }

            item { HorizontalDivider(color = SearchHairline) }
            item { FilterHeading("Rok i sezon") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Rok od", draft.yearFrom, Modifier.weight(1f), 4) {
                        draft = draft.copy(yearFrom = it)
                    }
                    NumberField("Rok do", draft.yearTo, Modifier.weight(1f), 4) {
                        draft = draft.copy(yearTo = it)
                    }
                    NumberField("Dokładny", draft.exactYear, Modifier.weight(1f), 4) {
                        draft = draft.copy(exactYear = it)
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        SelectChip("Dowolny sezon", draft.season == null) {
                            draft = draft.copy(season = null)
                        }
                    }
                    items(SearchSeason.entries) { season ->
                        SelectChip(season.label, draft.season == season) {
                            draft = draft.copy(season = season)
                        }
                    }
                }
            }

            item { FilterHeading("Format") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(SearchType.entries) { type ->
                        SelectChip(type.label, type in draft.types) {
                            draft = draft.copy(
                                types = if (type in draft.types) draft.types - type else draft.types + type
                            )
                        }
                    }
                }
            }

            item { FilterHeading("Status emisji") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(SearchStatus.entries) { status ->
                        SelectChip(status.label, status in draft.statuses) {
                            draft = draft.copy(
                                statuses = if (status in draft.statuses) {
                                    draft.statuses - status
                                } else {
                                    draft.statuses + status
                                }
                            )
                        }
                    }
                }
            }

            item { FilterHeading("Grupa napisów") }
            item {
                Box {
                    Surface(
                        onClick = { subtitleMenu = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = SearchChipBg,
                        border = BorderStroke(1.dp, SearchHairline)
                    ) {
                        Text(
                            draft.subtitleGroupName ?: "Dowolna grupa",
                            Modifier.padding(14.dp),
                            color = SearchTitle
                        )
                    }
                    DropdownMenu(
                        expanded = subtitleMenu,
                        onDismissRequest = { subtitleMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Dowolna grupa") },
                            onClick = {
                                draft = draft.copy(subtitleGroupId = null, subtitleGroupName = null)
                                subtitleMenu = false
                            }
                        )
                        subtitleGroups.forEach { group ->
                            DropdownMenuItem(
                                text = { Text(group.name) },
                                onClick = {
                                    draft = draft.copy(
                                        subtitleGroupId = group.id,
                                        subtitleGroupName = group.name
                                    )
                                    subtitleMenu = false
                                }
                            )
                        }
                    }
                }
            }

            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = SearchChipBg
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Dostępne online", color = SearchTitle)
                            Text("Co najmniej jeden odcinek online", color = SearchMuted, fontSize = 12.sp)
                        }
                        Switch(
                            checked = draft.onlineOnly,
                            onCheckedChange = { draft = draft.copy(onlineOnly = it) }
                        )
                    }
                }
            }

            item { FilterHeading("Opcje zaawansowane") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Ocena od", draft.ratingFrom, Modifier.weight(1f), 2) {
                        draft = draft.copy(ratingFrom = it)
                    }
                    NumberField("Ocena do", draft.ratingTo, Modifier.weight(1f), 2) {
                        draft = draft.copy(ratingTo = it)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Odcinki od", draft.episodesFrom, Modifier.weight(1f), 4) {
                        draft = draft.copy(episodesFrom = it)
                    }
                    NumberField("Odcinki do", draft.episodesTo, Modifier.weight(1f), 4) {
                        draft = draft.copy(episodesTo = it)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Minuty od", draft.lengthFrom, Modifier.weight(1f), 3) {
                        draft = draft.copy(lengthFrom = it)
                    }
                    NumberField("Minuty do", draft.lengthTo, Modifier.weight(1f), 3) {
                        draft = draft.copy(lengthTo = it)
                    }
                }
            }
            validation?.let { message ->
                item { PocketMessage(message, type = PocketMessageType.ERROR) }
            }
            item {
                PocketPrimaryButton(
                    label = "Zastosuj filtry",
                    onClick = {
                        validation = SearchApi.validate(draft)
                        if (validation == null) onApply(draft)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { draft = SearchFilters(sort = draft.sort) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Text("Wyczyść")
                    }
                    TextButton(
                        onClick = {
                            validation = SearchApi.validate(draft)
                            if (validation == null) onSave(draft)
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Text("Zapisz domyślne")
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = SearchTitle,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun NumberField(
    label: String,
    value: Int?,
    modifier: Modifier,
    maxLength: Int,
    onChange: (Int?) -> Unit
) {
    OutlinedTextField(
        value = value?.toString().orEmpty(),
        onValueChange = { raw -> onChange(raw.filter(Char::isDigit).take(maxLength).toIntOrNull()) },
        modifier = modifier,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = filterFieldColors()
    )
}

@Composable
private fun filterFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = SearchAccent,
    unfocusedBorderColor = SearchHairline,
    focusedTextColor = SearchTitle,
    unfocusedTextColor = SearchTitle,
    cursorColor = SearchAccent
)

private fun tagModeLabel(mode: SearchTagMode): String = when (mode) {
    SearchTagMode.INCLUDE -> "Z podtagami"
    SearchTagMode.ONLY -> "Tylko ten"
    SearchTagMode.EXCLUDE -> "Wyklucz"
}

@Composable
private fun PosterRow(
    row: List<SearchAnime>,
    libraryById: Map<Int, Anime>,
    userId: Int?,
    onAnimeClick: (SeasonAnime) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        row.forEach { entry ->
            val cover = entry.coverId?.let(::getCoverUrl)
            PocketPoster(
                title = entry.title,
                coverUrl = cover,
                libraryAnime = libraryById[entry.id],
                userId = userId,
                onClick = {
                    onAnimeClick(
                        SeasonAnime(entry.id, entry.title, entry.detailUrl, cover, entry.episodes)
                    )
                },
                modifier = Modifier.weight(1f),
                subtitle = entry.subtitleLabel(),
                style = PocketCardStyle.POSTER
            )
        }
        if (row.size == 1) Spacer(Modifier.weight(1f))
    }
}

private fun SearchAnime.subtitleLabel(): String? {
    val parts = buildList {
        rating?.let { add("★ " + ((it * 10).roundToInt() / 10f)) }
        kind?.let(::add)
        episodes?.let { add("$it odc.") }
        if (isEmpty() && !status.isNullOrBlank()) add(status)
    }
    return parts.joinToString(" · ").ifBlank { null }
}

@Composable
private fun SelectChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(50),
        color = if (selected) SearchAccent else SearchChipBg,
        contentColor = if (selected) Color.White else SearchChipText,
        border = BorderStroke(1.dp, if (selected) SearchAccent else SearchHairline)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun InlineError(message: String, onRetry: () -> Unit) {
    val friendly = if (message.startsWith("Brak zapisanych")) message
    else "Nie udało się pobrać wyników. Sprawdź połączenie i spróbuj ponownie."
    PocketMessage(
        message = friendly,
        type = PocketMessageType.ERROR,
        actionLabel = "Ponów",
        onAction = onRetry
    )
}
