package com.example.shindenpocket.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shindenpocket.data.Anime
import com.example.shindenpocket.data.EpisodeHistoryEvent
import com.example.shindenpocket.data.EpisodeSnapshotStore
import com.example.shindenpocket.ui.theme.PocketPalette
import com.example.shindenpocket.ui.theme.PocketRadius
import java.util.Calendar

/** Stan przeczytania historii premier jest niezależny od stanu obejrzenia odcinków. */
@Composable
internal fun EpisodeHistorySection(
    userId: Int,
    refreshKey: Int,
    animeById: Map<Int, Anime>,
    modifier: Modifier = Modifier,
    onOpenEpisode: (EpisodeHistoryEvent) -> Boolean
) {
    val context = LocalContext.current
    var history by remember(userId) { mutableStateOf<List<EpisodeHistoryEvent>>(emptyList()) }
    var selectedTab by remember(userId) { mutableIntStateOf(0) }
    var timeFilter by remember(userId) { mutableIntStateOf(0) } // 0=Wszystkie, 1=Dzisiaj, 2=Wczoraj, 3=Ten tydzień
    var message by remember(userId) { mutableStateOf<String?>(null) }

    fun reload() {
        try {
            history = EpisodeSnapshotStore.getRecentHistory(context, userId)
            message = null
        } catch (_: Exception) {
            message = "Nie można odczytać historii premier."
        }
    }

    LaunchedEffect(userId, refreshKey, selectedTab) { reload() }

    val actual = history.filterNot { it.isTest }
    val newEpisodes = actual.filterNot { it.isRead }
    val watchedHistory = actual.filter { it.isRead }
    val baseEpisodes = if (selectedTab == 0) newEpisodes else watchedHistory
    val visibleEpisodes = remember(baseEpisodes, timeFilter) {
        if (timeFilter == 0) baseEpisodes else {
            val now = Calendar.getInstance()
            baseEpisodes.filter { event ->
                val target = Calendar.getInstance().apply { timeInMillis = event.detectedAt }
                val sameYear = now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
                val dayDiff = now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR)
                when (timeFilter) {
                    1 -> sameYear && dayDiff == 0
                    2 -> sameYear && dayDiff == 1
                    3 -> sameYear && dayDiff in 0..7
                    else -> true
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(PocketRadius.control.dp))
                .background(PocketPalette.surface)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            EpisodeFilterTab(
                label = "Nowe",
                count = newEpisodes.size,
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                modifier = Modifier.weight(1f)
            )
            EpisodeFilterTab(
                label = "Historia",
                count = watchedHistory.size,
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(
                0 to "Wszystkie",
                1 to "Dzisiaj",
                2 to "Wczoraj",
                3 to "Ten tydzień"
            ).forEach { (idx, label) ->
                val active = timeFilter == idx
                Surface(
                    onClick = { timeFilter = idx },
                    shape = CircleShape,
                    color = if (active) PocketPalette.primary else PocketPalette.surfaceRaised,
                    border = if (active) null else BorderStroke(1.dp, PocketPalette.outlineVariant.copy(alpha = 0.7f))
                ) {
                    Text(
                        text = label,
                        color = if (active) Color.White else PocketPalette.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }

        message?.let { value ->
            PocketMessage(
                message = value,
                type = PocketMessageType.ERROR,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        if (visibleEpisodes.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                PocketEmptyState(
                    title = if (selectedTab == 0) "Jesteś na bieżąco" else "Brak historii premier",
                    message = if (timeFilter != 0 && baseEpisodes.isNotEmpty())
                        "Brak premier w wybranym przedziale czasowym."
                    else if (selectedTab == 0)
                        "Nowe odcinki pojawią się tutaj po wykryciu premiery. Użyj przycisku odświeżania, aby sprawdzić obserwowane serie."
                    else
                        "Premiery oznaczone jako obejrzane zobaczysz w tej historii.",
                    symbol = if (selectedTab == 0) "✓" else "◷",
                    modifier = Modifier.padding(top = 20.dp),
                    actionLabel = when {
                        timeFilter != 0 && baseEpisodes.isNotEmpty() -> "Pokaż wszystkie"
                        selectedTab == 0 && watchedHistory.isNotEmpty() -> "Przejdź do historii"
                        else -> null
                    },
                    onAction = when {
                        timeFilter != 0 && baseEpisodes.isNotEmpty() -> ({ timeFilter = 0 })
                        selectedTab == 0 && watchedHistory.isNotEmpty() -> ({ selectedTab = 1 })
                        else -> null
                    }
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 12.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = visibleEpisodes,
                    key = { event -> "${event.titleId}_${event.episodeId}_${selectedTab}" }
                ) { event ->
                    PremierEpisodeTile(
                        event = event,
                        anime = animeById[event.titleId],
                        userId = userId,
                        onClick = {
                            if (onOpenEpisode(event)) {
                                if (!event.isRead) {
                                    try {
                                        EpisodeSnapshotStore.markHistoryEpisodeRead(
                                            context, userId, event.titleId, event.episodeId
                                        )
                                        reload()
                                    } catch (_: Exception) {
                                        message = "Odcinek otwarto, ale nie udało się zaktualizować historii premier."
                                    }
                                }
                            } else {
                                message = "Nie znaleziono anime w bieżącej bibliotece."
                            }
                        }
                    )
                }
                if (selectedTab == 0 && newEpisodes.size > 1) {
                    item(key = "mark_all_read") {
                        TextButton(
                            onClick = {
                                try {
                                    EpisodeSnapshotStore.markHistoryRead(context, userId)
                                    reload()
                                } catch (_: Exception) {
                                    message = "Nie udało się zmienić statusu premier."
                                }
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Text("Oznacz wszystkie jako obejrzane", color = PocketPalette.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EpisodeFilterTab(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.heightIn(min = 44.dp)
            .clip(RoundedCornerShape(PocketRadius.small.dp))
            .background(if (selected) PocketPalette.primaryContainer.copy(alpha = 0.72f) else Color.Transparent)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = "$label: $count" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (selected) PocketPalette.onBackground else PocketPalette.onSurfaceVariant,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1
        )
        Spacer(Modifier.width(7.dp))
        Box(
            modifier = Modifier.background(
                if (selected) PocketPalette.primary else PocketPalette.surfaceVariant,
                CircleShape
            ).padding(horizontal = 7.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                count.toString(),
                color = if (selected) Color.White else PocketPalette.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun PremierEpisodeTile(
    event: EpisodeHistoryEvent,
    anime: Anime?,
    userId: Int,
    onClick: () -> Unit
) {
    val isNew = !event.isRead
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = "Otwórz odcinek ${event.episodeNumber}: ${event.animeTitle}"
        },
        shape = RoundedCornerShape(PocketRadius.card.dp),
        color = PocketPalette.surfaceRaised,
        border = BorderStroke(1.dp, PocketPalette.outlineVariant.copy(alpha = 0.65f))
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.width(102.dp).height(66.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(PocketPalette.surfaceVariant)
            ) {
                if (anime != null) {
                    AnimeCover(anime, Modifier.fillMaxSize(), userId = userId)
                } else {
                    PocketCoverImage(null, event.animeTitle, Modifier.fillMaxSize())
                }
                if (isNew) {
                    Box(
                        Modifier.align(Alignment.TopStart)
                            .padding(5.dp)
                            .background(PocketPalette.primary, RoundedCornerShape(5.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "NEW",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    event.animeTitle.ifBlank { anime?.title ?: "Anime #${event.titleId}" },
                    color = PocketPalette.onBackground,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.5.sp),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val epLabel = if (event.episodeTitle.isNotBlank()) {
                    "Odc. ${event.episodeNumber} • ${event.episodeTitle}"
                } else {
                    "Odc. ${event.episodeNumber}"
                }
                Text(
                    epLabel,
                    color = if (isNew) PocketPalette.primarySoft else PocketPalette.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatRelativeDetectedDate(event.detectedAt),
                    color = PocketPalette.onSurfaceVariant.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                    maxLines = 1
                )
            }
            Surface(
                onClick = onClick,
                shape = CircleShape,
                color = PocketPalette.primary
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
