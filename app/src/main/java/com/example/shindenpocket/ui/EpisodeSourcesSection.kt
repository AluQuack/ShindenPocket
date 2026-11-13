package com.example.shindenpocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.shindenpocket.data.EpisodeSource
import com.example.shindenpocket.data.PlaybackPreferences
import com.example.shindenpocket.data.EpisodeSourcesApi
import com.example.shindenpocket.data.TrackedEpisode
import com.example.shindenpocket.ui.theme.PocketPalette
import com.example.shindenpocket.ui.theme.PocketRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException

/** Lista źródeł; cyklem życia odtwarzacza zarządza ekran szczegółów. */
@Composable
internal fun EpisodeSourcesSection(
    episode: TrackedEpisode,
    titleId: Int,
    userId: Int,
    onPlay: (List<EpisodeSource>, Int) -> Unit
) {
    val context = LocalContext.current
    var reload by remember(episode.episodeId, userId) { mutableIntStateOf(0) }
    var loading by remember(episode.episodeId, userId) { mutableStateOf(false) }
    var sources by remember(episode.episodeId, userId) { mutableStateOf<List<EpisodeSource>?>(null) }
    var error by remember(episode.episodeId, userId) { mutableStateOf<String?>(null) }

    LaunchedEffect(episode.episodeId, titleId, userId, episode.detailUrl, reload) {
        val url = episode.detailUrl ?: return@LaunchedEffect
        if (episode.hasOnlineVersion != true) return@LaunchedEffect
        loading = true
        error = null
        try {
            sources = EpisodeSourcesApi.fetch(url, titleId, episode.episodeId, userId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Nie udało się pobrać źródeł"
        } finally {
            loading = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PocketPalette.surface,
        shape = RoundedCornerShape(PocketRadius.card.dp),
        border = BorderStroke(1.dp, PocketPalette.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "ŹRÓDŁA WIDEO",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = PocketPalette.primarySoft
                )
                val count = sources?.size
                if (count != null) {
                    Surface(
                        color = PocketPalette.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, PocketPalette.outlineVariant)
                    ) {
                        Text(
                            text = "Dostępne: $count",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            when {
                episode.hasOnlineVersion == false -> {
                    Text(
                        "Shinden nie pokazuje jeszcze wersji online tego odcinka.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketPalette.onSurfaceVariant
                    )
                }
                episode.hasOnlineVersion == null -> {
                    Text(
                        "Dostępność online nie została potwierdzona. Odśwież listę odcinków.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketPalette.onSurfaceVariant
                    )
                }
                episode.detailUrl == null -> {
                    Text(
                        "Brak potwierdzonego adresu strony tego odcinka.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PocketPalette.onSurfaceVariant
                    )
                }
                else -> {
                    if (loading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(vertical = 6.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = PocketPalette.primary
                            )
                            Text(
                                "Pobieram dostępne źródła…",
                                style = MaterialTheme.typography.bodySmall,
                                color = PocketPalette.onSurfaceVariant
                            )
                        }
                    }
                    if (error != null) {
                        PocketMessage(
                            message = "Nie udało się pobrać źródeł odcinka. Sprawdź połączenie i spróbuj ponownie.",
                            type = PocketMessageType.ERROR,
                            actionLabel = if (loading) null else "Ponów",
                            onAction = if (loading) null else ({ reload++ })
                        )
                    }
                    if (sources != null) {
                        val entries = sources.orEmpty()
                        if (entries.isEmpty()) {
                            Text(
                                "W tabeli Shindena nie ma aktualnie żadnych źródeł.",
                                style = MaterialTheme.typography.bodySmall,
                                color = PocketPalette.onSurfaceVariant
                            )
                        } else {
                            val preferred = PlaybackPreferences.preferredService(context, userId, titleId)
                            entries.forEachIndexed { index, source ->
                                EpisodeSourceCard(
                                    source = source,
                                    preferred = preferred != null && source.service.equals(preferred, true),
                                    onClick = { onPlay(entries, index) }
                                )
                            }
                        }
                    }
                    Text(
                        "Dotknij źródła, aby otworzyć odtwarzacz w aplikacji. Dostępność zależy od serwisu wideo.",
                        style = MaterialTheme.typography.labelSmall,
                        color = PocketPalette.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeSourceCard(source: EpisodeSource, preferred: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(PocketRadius.control.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (preferred) PocketPalette.primaryContainer.copy(alpha = 0.45f)
            else PocketPalette.surfaceRaised
        ),
        border = BorderStroke(
            1.dp,
            if (preferred) PocketPalette.primary.copy(alpha = 0.65f) else PocketPalette.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = if (preferred) PocketPalette.primary else PocketPalette.primaryContainer,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, PocketPalette.primary.copy(alpha = 0.5f))
            ) {
                Text(
                    "▶",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        source.service,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                    source.quality?.takeIf { it.isNotBlank() }?.let { quality ->
                        Surface(
                            color = PocketPalette.surfaceHighest,
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, PocketPalette.outlineVariant)
                        ) {
                            Text(
                                text = quality,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = PocketPalette.primarySoft
                            )
                        }
                    }
                }
                if (source.languages.isNotEmpty()) {
                    Text(
                        source.languages.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFD5CFE2),
                        maxLines = 1
                    )
                }
                source.addedAt?.let {
                    Text(
                        "Dodano: $it",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = PocketPalette.onSurfaceVariant
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (preferred) {
                    Surface(
                        color = PocketPalette.primary.copy(alpha = 0.22f),
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, PocketPalette.primary.copy(alpha = 0.45f))
                    ) {
                        Text(
                            "PREFEROWANE",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = PocketPalette.primarySoft
                        )
                    }
                }
                Text(
                    "Odtwórz ›",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = PocketPalette.primarySoft
                )
            }
        }
    }
}
