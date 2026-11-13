package com.example.shindenpocket.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shindenpocket.data.*
import com.example.shindenpocket.ui.theme.PocketPalette
import com.example.shindenpocket.ui.theme.PocketRadius
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun extractCoverIdFromUrl(url: String?): Int {
    if (url.isNullOrBlank()) return 0
    return Regex("""/(\d+)\.(?:jpg|jpeg|png|webp)""", RegexOption.IGNORE_CASE)
        .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AnimeManagementSection(
    titleId: Int,
    seriesTitle: String,
    userId: Int?,
    offlineMode: Boolean,
    libraryAnime: Anime?,
    seriesInfo: SeasonSeriesInfo?,
    fallbackCoverUrl: String? = null,
    fallbackTotalEpisodes: Int = 0,
    fallbackDescription: String? = null,
    onAnimeUpdated: ((Anime) -> Unit)? = null
) {
    if (userId == null || userId <= 0) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val resolvedCoverId = remember(libraryAnime?.coverId, seriesInfo?.coverUrl, fallbackCoverUrl) {
        when {
            (libraryAnime?.coverId ?: 0) > 0 -> libraryAnime!!.coverId
            else -> extractCoverIdFromUrl(seriesInfo?.coverUrl ?: fallbackCoverUrl)
        }
    }

    var localEntry by remember(titleId, userId) {
        mutableStateOf(libraryAnime)
    }

    LaunchedEffect(libraryAnime) {
        if (libraryAnime != null) {
            localEntry = libraryAnime
        }
    }

    var partialRatingsExpanded by remember(titleId) {
        mutableStateOf(
            libraryAnime?.let {
                it.rateStory != null || it.rateGraphic != null ||
                    it.rateMusic != null || it.rateCharacters != null
            } ?: false
        )
    }
    var totalAutoSuggested by remember(titleId) { mutableStateOf(false) }
    var syncStatusMessage by remember(titleId) { mutableStateOf<String?>(null) }
    var syncStatusIsError by remember(titleId) { mutableStateOf(false) }
    var isSyncing by remember(titleId) { mutableStateOf(false) }

    fun currentOrBaseAnime(): Anime {
        val existing = localEntry ?: libraryAnime
        if (existing != null) {
            return existing.copy(
                coverId = if (existing.coverId > 0) existing.coverId else resolvedCoverId,
                title = existing.title.ifBlank { seriesTitle },
                total = if (existing.total > 0) existing.total else fallbackTotalEpisodes,
                description = existing.description.ifBlank {
                    seriesInfo?.description?.ifBlank { null } ?: fallbackDescription.orEmpty()
                },
                rating = existing.rating.ifBlank { seriesInfo?.rating.orEmpty() }
            )
        }
        return Anime(
            id = titleId,
            coverId = resolvedCoverId,
            title = seriesTitle,
            watched = 0,
            total = fallbackTotalEpisodes,
            favorite = false,
            watchStatus = "",
            status = "",
            description = seriesInfo?.description?.ifBlank { null } ?: fallbackDescription.orEmpty(),
            rating = seriesInfo?.rating.orEmpty()
        )
    }

    // Stan lokalny jest łączony z odpowiedzią `/v1/me/list/anime/{id}`.
    LaunchedEffect(titleId, userId, offlineMode) {
        if (titleId <= 0) return@LaunchedEffect
        val savedLocal = OfflineLibraryStore.loadUserEntry(context, userId, titleId)
        val hasPendingLocal = OfflineLibraryStore.hasPendingUserEntry(context, userId, titleId)
        if (savedLocal != null) {
            localEntry = savedLocal
            if (savedLocal.rateStory != null || savedLocal.rateGraphic != null ||
                savedLocal.rateMusic != null || savedLocal.rateCharacters != null
            ) {
                partialRatingsExpanded = true
            }
        }
        if (offlineMode) return@LaunchedEffect
        try {
            if (hasPendingLocal && savedLocal != null) {
                // Zachowujemy niezsynchronizowane lokalne zmiany i wypychamy je na serwer Shinden.
                val statusOpt = WatchStatusOption.fromCode(savedLocal.watchStatus)
                if (statusOpt == WatchStatusOption.NONE && !savedLocal.favorite && !savedLocal.hasUserRating) {
                    ShindenManagementApi.deleteListEntry(titleId)
                } else {
                    ShindenManagementApi.updateListEntry(
                        titleId = titleId,
                        statusOption = statusOpt,
                        favourite = savedLocal.favorite,
                        includeRatings = true,
                        rateTotal = savedLocal.rateTotal,
                        rateStory = savedLocal.rateStory,
                        rateGraphic = savedLocal.rateGraphic,
                        rateMusic = savedLocal.rateMusic,
                        rateCharacters = savedLocal.rateCharacters
                    )
                }
                OfflineLibraryStore.markUserEntrySynced(context, userId, savedLocal)
                return@LaunchedEffect
            }

            val remote = ShindenManagementApi.fetchListEntry(titleId) ?: return@LaunchedEffect
            // Jeśli w trakcie zapytania sieciowego użytkownik wykonał lokalną zmianę, nie nadpisujemy jej.
            if (OfflineLibraryStore.hasPendingUserEntry(context, userId, titleId)) {
                return@LaunchedEffect
            }
            val base = currentOrBaseAnime()
            val merged = base.copy(
                watchStatus = remote.status,
                watched = maxOf(base.watched, remote.progress),
                favorite = remote.favourite,
                rateTotal = remote.rateTotal,
                rateStory = remote.rateStory,
                rateGraphic = remote.rateGraphic,
                rateMusic = remote.rateMusic,
                rateCharacters = remote.rateCharacters
            )
            if (merged.rateStory != null || merged.rateGraphic != null ||
                merged.rateMusic != null || merged.rateCharacters != null
            ) {
                partialRatingsExpanded = true
            }
            if (merged != localEntry) {
                localEntry = merged
                OfflineLibraryStore.updateCachedLibraryAnime(context, userId, merged)
                if (merged.isInUserLibrary || libraryAnime != null) {
                    onAnimeUpdated?.invoke(merged)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Błąd sieci pozostawia dane lokalne.
        }
    }

    fun persistAndSync(
        updated: Anime,
        statusChanged: Boolean = false,
        favoriteChanged: Boolean = false,
        ratingsChanged: Boolean = false,
        confirmationText: String
    ) {
        localEntry = updated
        syncStatusMessage = confirmationText
        syncStatusIsError = false
        onAnimeUpdated?.invoke(updated)

        scope.launch {
            isSyncing = true
            try {
                withContext(NonCancellable) {
                    OfflineLibraryStore.saveUserEntry(
                        context = context,
                        userId = userId,
                        anime = updated,
                        pendingSync = true
                    )
                }
                if (!offlineMode) {
                    val statusOpt = WatchStatusOption.fromCode(updated.watchStatus)
                    if (statusChanged && statusOpt == WatchStatusOption.NONE &&
                        !updated.favorite && !updated.hasUserRating
                    ) {
                        ShindenManagementApi.deleteListEntry(titleId)
                    } else {
                        ShindenManagementApi.updateListEntry(
                            titleId = titleId,
                            statusOption = if (statusChanged) statusOpt else null,
                            favourite = if (favoriteChanged || statusChanged) updated.favorite else null,
                            includeRatings = ratingsChanged,
                            rateTotal = updated.rateTotal,
                            rateStory = updated.rateStory,
                            rateGraphic = updated.rateGraphic,
                            rateMusic = updated.rateMusic,
                            rateCharacters = updated.rateCharacters
                        )
                    }
                    withContext(NonCancellable) {
                        OfflineLibraryStore.markUserEntrySynced(context, userId, updated)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Dane są trwale zapisane lokalnie z flagą pendingSync = true
                syncStatusMessage = "$confirmationText (zapisano lokalnie)"
                syncStatusIsError = false
            } finally {
                isSyncing = false
            }
        }
    }

    val current = currentOrBaseAnime()
    val currentStatusOption = WatchStatusOption.fromCode(current.watchStatus)

    PocketCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            Modifier.width(4.dp).height(18.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(PocketPalette.primary)
                        )
                        Text(
                            text = "Moja lista i ocena",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = PocketPalette.onBackground
                        )
                    }
                    Text(
                        text = "Status oglądania, ulubione oraz oceny cząstkowe",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketPalette.onSurfaceVariant
                    )
                }

                
                Surface(
                    onClick = {
                        val nextFav = !current.favorite
                        val updated = current.copy(favorite = nextFav)
                        persistAndSync(
                            updated = updated,
                            favoriteChanged = true,
                            confirmationText = if (nextFav) "Dodano do ulubionych ♥" else "Usunięto z ulubionych"
                        )
                    },
                    shape = CircleShape,
                    color = if (current.favorite) PocketPalette.primary.copy(alpha = 0.22f)
                    else PocketPalette.surfaceRaised,
                    border = BorderStroke(
                        1.dp,
                        if (current.favorite) PocketPalette.primary
                        else PocketPalette.outlineVariant.copy(alpha = 0.75f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = if (current.favorite) "♥" else "♡",
                            color = if (current.favorite) PocketPalette.primarySoft else PocketPalette.onSurfaceVariant,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (current.favorite) "W ulubionych" else "Ulubione",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (current.favorite) PocketPalette.primarySoft else PocketPalette.onBackground
                        )
                    }
                }
            }

            HorizontalDivider(color = PocketPalette.outlineVariant.copy(alpha = 0.45f))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Status oglądania",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = PocketPalette.onBackground
                    )
                    Text(
                        text = currentStatusOption.label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (currentStatusOption == WatchStatusOption.NONE) PocketPalette.onSurfaceVariant
                        else PocketPalette.primarySoft
                    )
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    WatchStatusOption.entries.forEach { option ->
                        val selected = currentStatusOption == option
                        Surface(
                            onClick = {
                                if (currentStatusOption != option) {
                                    val updated = current.copy(watchStatus = option.code)
                                    persistAndSync(
                                        updated = updated,
                                        statusChanged = true,
                                        confirmationText = "Status: ${option.label}"
                                    )
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) PocketPalette.primary
                            else PocketPalette.surfaceRaised.copy(alpha = 0.85f),
                            border = if (selected) null
                            else BorderStroke(1.dp, PocketPalette.outlineVariant.copy(alpha = 0.65f))
                        ) {
                            Text(
                                text = option.label,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) Color.White else PocketPalette.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = PocketPalette.outlineVariant.copy(alpha = 0.45f))

            
            val suggestedScore = ShindenRatingScale.suggestedTotal(
                story = current.rateStory,
                graphics = current.rateGraphic,
                music = current.rateMusic,
                characters = current.rateCharacters
            )
            val effectiveTotal = current.rateTotal
            val verbalLabel = ShindenRatingScale.labelFor(effectiveTotal)
            val verbalDesc = ShindenRatingScale.descriptionFor(effectiveTotal)

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Ocena ogólna",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = PocketPalette.onBackground
                        )
                        Text(
                            text = if (effectiveTotal != null) {
                                buildString {
                                    append("★ $effectiveTotal / 10")
                                    if (verbalLabel != null) append(" · $verbalLabel")
                                    if (totalAutoSuggested) append(" (sugerowana)")
                                }
                            } else {
                                "Brak oceny — wybierz od 1 do 10"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (effectiveTotal != null) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (effectiveTotal != null) PocketPalette.warning else PocketPalette.onSurfaceVariant
                        )
                    }

                    if (current.hasUserRating) {
                        TextButton(
                            onClick = {
                                totalAutoSuggested = false
                                val updated = current.copy(
                                    rateTotal = null,
                                    rateStory = null,
                                    rateGraphic = null,
                                    rateMusic = null,
                                    rateCharacters = null
                                )
                                persistAndSync(
                                    updated = updated,
                                    ratingsChanged = true,
                                    confirmationText = "Wyczyszczono oceny"
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.heightIn(min = 32.dp)
                        ) {
                            Text(
                                text = "Wyczyść",
                                style = MaterialTheme.typography.labelMedium,
                                color = PocketPalette.onSurfaceVariant
                            )
                        }
                    }
                }

                RatingScoreRow(
                    selectedScore = current.rateTotal,
                    onSelectScore = { score ->
                        val nextScore = if (current.rateTotal == score) null else score
                        totalAutoSuggested = false
                        val updated = current.copy(rateTotal = nextScore)
                        persistAndSync(
                            updated = updated,
                            ratingsChanged = true,
                            confirmationText = if (nextScore != null) {
                                "Ocena ogólna: $nextScore/10 (${ShindenRatingScale.labelFor(nextScore).orEmpty()})"
                            } else {
                                "Usunięto ocenę ogólną"
                            }
                        )
                    }
                )

                if (verbalDesc != null) {
                    Text(
                        text = verbalDesc,
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketPalette.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }

                val partialCount = listOfNotNull(
                    current.rateStory,
                    current.rateGraphic,
                    current.rateMusic,
                    current.rateCharacters
                ).size

                Surface(
                    onClick = { partialRatingsExpanded = !partialRatingsExpanded },
                    shape = RoundedCornerShape(10.dp),
                    color = PocketPalette.surfaceRaised.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, PocketPalette.outlineVariant.copy(alpha = 0.55f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (partialCount > 0) "Oceny cząstkowe ($partialCount/4)"
                                else "Oceny cząstkowe (opcjonalne)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = PocketPalette.onBackground
                            )
                            Text(
                                text = "Fabuła, Grafika, Muzyka, Postacie",
                                style = MaterialTheme.typography.bodySmall,
                                color = PocketPalette.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            text = if (partialRatingsExpanded) "Ukryj ▲" else "Rozwiń ▼",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = PocketPalette.primarySoft
                        )
                    }
                }

                AnimatedVisibility(visible = partialRatingsExpanded) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        fun updatePartialRatings(
                            story: Int? = current.rateStory,
                            graphic: Int? = current.rateGraphic,
                            music: Int? = current.rateMusic,
                            characters: Int? = current.rateCharacters,
                            criterionLabel: String,
                            newScore: Int?
                        ) {
                            val newSuggested = ShindenRatingScale.suggestedTotal(
                                story = story,
                                graphics = graphic,
                                music = music,
                                characters = characters
                            )
                            val shouldAutoUpdateTotal = current.rateTotal == null || totalAutoSuggested
                            val nextTotal = if (shouldAutoUpdateTotal) {
                                totalAutoSuggested = newSuggested != null
                                newSuggested
                            } else {
                                current.rateTotal
                            }
                            val updated = current.copy(
                                rateTotal = nextTotal,
                                rateStory = story,
                                rateGraphic = graphic,
                                rateMusic = music,
                                rateCharacters = characters
                            )
                            persistAndSync(
                                updated = updated,
                                ratingsChanged = true,
                                confirmationText = if (newScore != null) "$criterionLabel: $newScore/10"
                                else "Usunięto ocenę: $criterionLabel"
                            )
                        }

                        PartialCriterionRow(
                            label = "Fabuła",
                            score = current.rateStory,
                            onSelectScore = { s ->
                                val next = if (current.rateStory == s) null else s
                                updatePartialRatings(story = next, criterionLabel = "Fabuła", newScore = next)
                            }
                        )
                        PartialCriterionRow(
                            label = "Grafika",
                            score = current.rateGraphic,
                            onSelectScore = { s ->
                                val next = if (current.rateGraphic == s) null else s
                                updatePartialRatings(graphic = next, criterionLabel = "Grafika", newScore = next)
                            }
                        )
                        PartialCriterionRow(
                            label = "Muzyka",
                            score = current.rateMusic,
                            onSelectScore = { s ->
                                val next = if (current.rateMusic == s) null else s
                                updatePartialRatings(music = next, criterionLabel = "Muzyka", newScore = next)
                            }
                        )
                        PartialCriterionRow(
                            label = "Postacie",
                            score = current.rateCharacters,
                            onSelectScore = { s ->
                                val next = if (current.rateCharacters == s) null else s
                                updatePartialRatings(characters = next, criterionLabel = "Postacie", newScore = next)
                            }
                        )

                        if (suggestedScore != null && current.rateTotal != suggestedScore) {
                            TextButton(
                                onClick = {
                                    totalAutoSuggested = true
                                    val updated = current.copy(rateTotal = suggestedScore)
                                    persistAndSync(
                                        updated = updated,
                                        ratingsChanged = true,
                                        confirmationText = "Zastosowano sugerowaną ocenę ogólną: $suggestedScore/10"
                                    )
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Ustaw sugerowaną ocenę ogólną z cząstkowych ($suggestedScore/10)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = PocketPalette.primarySoft
                                )
                            }
                        }
                    }
                }
            }

            if (syncStatusMessage != null || isSyncing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = PocketPalette.primary
                        )
                    }
                    syncStatusMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (syncStatusIsError) PocketPalette.error else PocketPalette.success,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PartialCriterionRow(
    label: String,
    score: Int?,
    onSelectScore: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = PocketPalette.onBackground
            )
            Text(
                text = if (score != null) {
                    "$score/10 · ${ShindenRatingScale.labelFor(score).orEmpty()}"
                } else {
                    "Nie oceniono"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (score != null) PocketPalette.primarySoft else PocketPalette.onSurfaceVariant
            )
        }
        RatingScoreRow(
            selectedScore = score,
            onSelectScore = onSelectScore,
            compact = true
        )
    }
}

@Composable
private fun RatingScoreRow(
    selectedScore: Int?,
    onSelectScore: (Int) -> Unit,
    compact: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (score in 1..10) {
            val isSelected = selectedScore == score
            val isFilled = selectedScore != null && score <= selectedScore
            Surface(
                onClick = { onSelectScore(score) },
                shape = RoundedCornerShape(if (compact) 8.dp else 10.dp),
                color = when {
                    isSelected -> PocketPalette.primary
                    isFilled -> PocketPalette.primary.copy(alpha = 0.24f)
                    else -> PocketPalette.surfaceRaised
                },
                border = BorderStroke(
                    width = 1.dp,
                    color = when {
                        isSelected -> PocketPalette.primarySoft
                        isFilled -> PocketPalette.primary.copy(alpha = 0.55f)
                        else -> PocketPalette.outlineVariant.copy(alpha = 0.65f)
                    }
                ),
                modifier = Modifier.size(if (compact) 32.dp else 36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = score.toString(),
                        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        color = when {
                            isSelected -> Color.White
                            isFilled -> PocketPalette.primarySoft
                            else -> PocketPalette.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun AnimeCommentsSection(
    titleId: Int,
    seriesTitle: String,
    userId: Int?,
    username: String?,
    offlineMode: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val effectiveUserId = userId ?: 0
    val effectiveUsername = username?.takeIf { it.isNotBlank() } ?: "Użytkownik"

    var comments by remember(titleId, effectiveUserId) { mutableStateOf<List<AnimeComment>>(emptyList()) }
    var loading by remember(titleId, effectiveUserId) { mutableStateOf(true) }
    var hasMore by remember(titleId, effectiveUserId) { mutableStateOf(false) }
    var lastId by remember(titleId, effectiveUserId) { mutableStateOf<Long?>(null) }
    var loadingMore by remember(titleId, effectiveUserId) { mutableStateOf(false) }

    var newCommentText by remember(titleId) { mutableStateOf("") }
    var submittingNew by remember(titleId) { mutableStateOf(false) }

    var editingCommentId by remember(titleId) { mutableStateOf<Long?>(null) }
    var editingText by remember(titleId) { mutableStateOf("") }
    var submittingEdit by remember(titleId) { mutableStateOf(false) }

    var feedbackMessage by remember(titleId) { mutableStateOf<String?>(null) }
    var feedbackIsError by remember(titleId) { mutableStateOf(false) }

    
    LaunchedEffect(titleId, effectiveUserId, offlineMode) {
        loading = true
        if (effectiveUserId > 0) {
            val cached = OfflineLibraryStore.loadComments(context, effectiveUserId, titleId)
            if (cached.isNotEmpty()) {
                comments = cached
            }
        }
        if (!offlineMode && titleId > 0) {
            try {
                val page = ShindenManagementApi.fetchComments(titleId)
                hasMore = page.hasMore
                lastId = page.lastId
                comments = if (effectiveUserId > 0) {
                    OfflineLibraryStore.saveRemoteComments(context, effectiveUserId, titleId, page.comments)
                } else {
                    page.comments
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                
            }
        }
        loading = false
    }

    PocketCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier.width(4.dp).height(18.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(PocketPalette.primary)
                    )
                    Text(
                        text = "Komentarze",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = PocketPalette.onBackground
                    )
                    if (comments.isNotEmpty()) {
                        Surface(
                            shape = CircleShape,
                            color = PocketPalette.primary.copy(alpha = 0.18f)
                        ) {
                            Text(
                                text = comments.size.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = PocketPalette.primarySoft,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            
            if (effectiveUserId > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newCommentText,
                        onValueChange = { newCommentText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                "Napisz komentarz do serii $seriesTitle…",
                                color = PocketPalette.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        minLines = 2,
                        maxLines = 6,
                        shape = RoundedCornerShape(PocketRadius.control.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = PocketPalette.surfaceRaised,
                            unfocusedContainerColor = PocketPalette.surfaceRaised,
                            focusedBorderColor = PocketPalette.primary,
                            unfocusedBorderColor = PocketPalette.outlineVariant,
                            focusedTextColor = PocketPalette.onBackground,
                            unfocusedTextColor = PocketPalette.onBackground,
                            cursorColor = PocketPalette.primary
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val textToPost = newCommentText.trim()
                                if (textToPost.isBlank() || submittingNew) return@Button
                                submittingNew = true
                                feedbackMessage = null
                                scope.launch {
                                    try {
                                        var remoteCommentId: Long? = null
                                        var postError: String? = null
                                        if (!offlineMode) {
                                            try {
                                                remoteCommentId = ShindenManagementApi.postComment(
                                                    titleId = titleId,
                                                    body = textToPost
                                                )
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Exception) {
                                                postError = e.message
                                            }
                                        }
                                        val isLocalFallback = offlineMode || postError != null
                                        val nowFormatted = SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.getDefault())
                                            .format(Date())
                                        val createdComment = AnimeComment(
                                            id = if (isLocalFallback) -System.currentTimeMillis()
                                            else (remoteCommentId ?: System.currentTimeMillis()),
                                            animeId = titleId,
                                            authorId = effectiveUserId,
                                            authorName = effectiveUsername,
                                            avatarUrl = null,
                                            rank = null,
                                            body = textToPost,
                                            createdAt = nowFormatted,
                                            edited = false,
                                            likes = 0,
                                            parentId = null,
                                            isLocalOnly = isLocalFallback
                                        )
                                        comments = OfflineLibraryStore.addOwnComment(
                                            context = context,
                                            userId = effectiveUserId,
                                            animeId = titleId,
                                            comment = createdComment
                                        )
                                        newCommentText = ""
                                        if (postError != null) {
                                            feedbackMessage = "$postError (zapisano lokalnie)"
                                            feedbackIsError = true
                                        } else {
                                            feedbackMessage = if (offlineMode) "Dodano komentarz (lokalnie)" else "Dodano komentarz"
                                            feedbackIsError = false
                                        }
                                    } finally {
                                        submittingNew = false
                                    }
                                }
                            },
                            enabled = newCommentText.isNotBlank() && !submittingNew,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = if (submittingNew) "Wysyłanie…" else "Dodaj komentarz",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            } else {
                Text(
                    text = "Zaloguj się na konto Shinden, aby dodawać własne komentarze.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PocketPalette.onSurfaceVariant
                )
            }

            feedbackMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (feedbackIsError) PocketPalette.error else PocketPalette.success,
                    fontWeight = FontWeight.Medium
                )
            }

            HorizontalDivider(color = PocketPalette.outlineVariant.copy(alpha = 0.45f))

            if (loading && comments.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = PocketPalette.primary
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Wczytywanie komentarzy…",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketPalette.onSurfaceVariant
                    )
                }
            } else if (comments.isEmpty()) {
                Text(
                    text = "Brak komentarzy dla tego anime. Dodaj pierwszy wpis powyżej!",
                    style = MaterialTheme.typography.bodySmall,
                    color = PocketPalette.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    comments.forEach { comment ->
                        val isOwn = comment.isOwnedBy(effectiveUserId, effectiveUsername)
                        val isEditingThis = editingCommentId == comment.id

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isOwn) PocketPalette.primaryContainer.copy(alpha = 0.22f)
                            else PocketPalette.surfaceRaised.copy(alpha = 0.72f),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isOwn) PocketPalette.primary.copy(alpha = 0.45f)
                                else PocketPalette.outlineVariant.copy(alpha = 0.55f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = if (comment.parentId != null) 14.dp else 0.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        // Awatar autora; dla wpisów bez adresu litera z nicku.
                                        PocketAvatar(
                                            url = comment.avatarUrl
                                                ?: comment.authorId.takeIf { it > 0 }?.let(ShindenUrls::avatarUrl),
                                            name = comment.authorName,
                                            modifier = Modifier.size(28.dp),
                                            backgroundColor = if (isOwn) PocketPalette.primary
                                            else PocketPalette.surfaceVariant
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = comment.authorName,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isOwn) PocketPalette.primarySoft else PocketPalette.onBackground,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (isOwn) {
                                                    Text(
                                                        text = "• Twój wpis",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = PocketPalette.primarySoft,
                                                        fontSize = 10.sp
                                                    )
                                                } else if (!comment.rank.isNullOrBlank()) {
                                                    Text(
                                                        text = "• ${comment.rank}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = PocketPalette.onSurfaceVariant,
                                                        fontSize = 10.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                if (comment.createdAt.isNotBlank()) {
                                                    Text(
                                                        text = comment.createdAt,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = PocketPalette.onSurfaceVariant,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                                if (comment.edited) {
                                                    Text(
                                                        text = "(edytowany)",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = PocketPalette.onSurfaceVariant,
                                                        fontSize = 10.sp
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    
                                    if (isOwn && !isEditingThis) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            TextButton(
                                                onClick = {
                                                    editingCommentId = comment.id
                                                    editingText = comment.body
                                                    if (!offlineMode && !comment.isLocalOnly) {
                                                        scope.launch {
                                                            val raw = runCatching {
                                                                ShindenManagementApi.fetchCommentRawBody(comment.id)
                                                            }.getOrNull()
                                                            if (!raw.isNullOrBlank() && editingCommentId == comment.id) {
                                                                editingText = raw
                                                            }
                                                        }
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.heightIn(min = 30.dp)
                                            ) {
                                                Text(
                                                    text = "Edytuj",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = PocketPalette.primarySoft
                                                )
                                            }
                                            TextButton(
                                                onClick = {
                                                    if (!comment.isLocalOnly && offlineMode) {
                                                        feedbackMessage = "Usuwanie komentarzy z Shinden wymaga połączenia z internetem."
                                                        feedbackIsError = true
                                                        return@TextButton
                                                    }
                                                    scope.launch {
                                                        if (!comment.isLocalOnly) {
                                                            try {
                                                                ShindenManagementApi.deleteComment(comment.id, titleId)
                                                            } catch (e: CancellationException) {
                                                                throw e
                                                            } catch (e: Exception) {
                                                                feedbackMessage = e.message
                                                                    ?: "Nie udało się usunąć komentarza na Shinden."
                                                                feedbackIsError = true
                                                                return@launch
                                                            }
                                                        }
                                                        comments = if (effectiveUserId > 0) {
                                                            OfflineLibraryStore.deleteOwnComment(
                                                                context = context,
                                                                userId = effectiveUserId,
                                                                animeId = titleId,
                                                                commentId = comment.id
                                                            )
                                                        } else {
                                                            comments.filterNot { it.id == comment.id }
                                                        }
                                                        if (editingCommentId == comment.id) {
                                                            editingCommentId = null
                                                            editingText = ""
                                                        }
                                                        feedbackMessage = "Usunięto komentarz"
                                                        feedbackIsError = false
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                modifier = Modifier.heightIn(min = 30.dp)
                                            ) {
                                                Text(
                                                    text = "Usuń",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = PocketPalette.error
                                                )
                                            }
                                        }
                                    }
                                }

                                if (isEditingThis) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = editingText,
                                            onValueChange = { editingText = it },
                                            modifier = Modifier.fillMaxWidth(),
                                            minLines = 2,
                                            maxLines = 6,
                                            shape = RoundedCornerShape(PocketRadius.control.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedContainerColor = PocketPalette.surface,
                                                unfocusedContainerColor = PocketPalette.surface,
                                                focusedBorderColor = PocketPalette.primary,
                                                unfocusedBorderColor = PocketPalette.outlineVariant,
                                                focusedTextColor = PocketPalette.onBackground,
                                                unfocusedTextColor = PocketPalette.onBackground,
                                                cursorColor = PocketPalette.primary
                                            )
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            TextButton(
                                                onClick = {
                                                    editingCommentId = null
                                                    editingText = ""
                                                }
                                            ) {
                                                Text("Anuluj", style = MaterialTheme.typography.labelMedium)
                                            }
                                            Spacer(Modifier.width(6.dp))
                                            Button(
                                                onClick = {
                                                    val updatedBody = editingText.trim()
                                                    if (updatedBody.isBlank() || submittingEdit) return@Button
                                                    if (!comment.isLocalOnly && offlineMode) {
                                                        feedbackMessage = "Edycja komentarzy z Shinden wymaga połączenia z internetem."
                                                        feedbackIsError = true
                                                        return@Button
                                                    }
                                                    submittingEdit = true
                                                    feedbackMessage = null
                                                    scope.launch {
                                                        try {
                                                            if (!comment.isLocalOnly) {
                                                                try {
                                                                    ShindenManagementApi.editComment(
                                                                        commentId = comment.id,
                                                                        body = updatedBody,
                                                                        titleId = titleId
                                                                    )
                                                                } catch (e: CancellationException) {
                                                                    throw e
                                                                } catch (e: Exception) {
                                                                    feedbackMessage = e.message
                                                                        ?: "Nie udało się zapisać zmian komentarza na Shinden."
                                                                    feedbackIsError = true
                                                                    return@launch
                                                                }
                                                            }
                                                            comments = if (effectiveUserId > 0) {
                                                                OfflineLibraryStore.editOwnComment(
                                                                    context = context,
                                                                    userId = effectiveUserId,
                                                                    animeId = titleId,
                                                                    commentId = comment.id,
                                                                    newBody = updatedBody
                                                                )
                                                            } else {
                                                                comments.map {
                                                                    if (it.id == comment.id) {
                                                                        it.copy(body = updatedBody, edited = true)
                                                                    } else it
                                                                }
                                                            }
                                                            editingCommentId = null
                                                            editingText = ""
                                                            feedbackMessage = "Zaktualizowano komentarz"
                                                            feedbackIsError = false
                                                        } finally {
                                                            submittingEdit = false
                                                        }
                                                    }
                                                },
                                                enabled = editingText.isNotBlank() && !submittingEdit,
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                            ) {
                                                Text(
                                                    text = if (submittingEdit) "Zapisywanie…" else "Zapisz",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = comment.body,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = PocketPalette.onBackground,
                                        lineHeight = 20.sp
                                    )
                                }
                            }
                        }
                    }

                    if (hasMore && !offlineMode && lastId != null) {
                        OutlinedButton(
                            onClick = {
                                val before = lastId ?: return@OutlinedButton
                                if (loadingMore) return@OutlinedButton
                                loadingMore = true
                                scope.launch {
                                    try {
                                        val nextPage = ShindenManagementApi.fetchComments(titleId, beforeId = before)
                                        hasMore = nextPage.hasMore
                                        lastId = nextPage.lastId
                                        val combinedRemote = (comments + nextPage.comments).distinctBy { it.id }
                                        comments = if (effectiveUserId > 0) {
                                            OfflineLibraryStore.saveRemoteComments(
                                                context = context,
                                                userId = effectiveUserId,
                                                animeId = titleId,
                                                remote = combinedRemote
                                            )
                                        } else {
                                            combinedRemote
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                    } finally {
                                        loadingMore = false
                                    }
                                }
                            },
                            enabled = !loadingMore,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = if (loadingMore) "Wczytywanie starszych komentarzy…" else "Wczytaj więcej komentarzy",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }
        }
    }
}
