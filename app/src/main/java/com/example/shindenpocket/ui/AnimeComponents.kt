package com.example.shindenpocket.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shindenpocket.data.Anime
import com.example.shindenpocket.data.OfflineLibraryStore
import com.example.shindenpocket.data.RecentPlayback
import com.example.shindenpocket.ui.theme.PocketPalette
import com.example.shindenpocket.ui.theme.PocketRadius

// Adres okładki musi odpowiadać formatowi używanemu przez `AnimeCover`.
fun getCoverUrl(coverId: Int): String? {
    if (coverId <= 0) return null
    return "https://shinden.pl/res/images/225x350/$coverId.jpg"
}

@Composable
fun AnimeCover(anime: Anime, modifier: Modifier = Modifier, userId: Int? = null) {
    val context = LocalContext.current
    val model = remember(context, userId, anime.coverId) {
        OfflineLibraryStore.coverFile(context, userId, anime.coverId) ?: getCoverUrl(anime.coverId)
    }
    PocketCoverImage(
        model = model,
        title = anime.title,
        modifier = modifier.clip(RoundedCornerShape(PocketRadius.control.dp))
    )
}

/** Pozioma karta biblioteki z postępem oglądania i akcjami użytkownika. */
@Composable
fun AnimeCard(
    anime: Anime,
    onClick: () -> Unit,
    onContinueClick: (() -> Unit)? = null,
    userId: Int? = null
) {
    val scheme = MaterialTheme.colorScheme
    val safeWatched = anime.watched.coerceAtLeast(0)
    val totalKnown = anime.total > 0
    val progress = if (totalKnown) {
        (safeWatched.toFloat() / anime.total).coerceIn(0f, 1f)
    } else if (safeWatched > 0) 0.5f else 0f
    val percent = if (totalKnown) ((progress * 100).toInt()) else null

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PocketRadius.card.dp),
        colors = CardDefaults.cardColors(containerColor = PocketPalette.surfaceRaised),
        border = BorderStroke(1.dp, PocketPalette.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(96.dp).height(64.dp)) {
                AnimeCover(anime = anime, modifier = Modifier.fillMaxSize(), userId = userId)
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = anime.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = PocketPalette.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (anime.favorite) "♥" else "♡",
                        color = if (anime.favorite) PocketPalette.primary else PocketPalette.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                    IconButton(
                        onClick = onContinueClick ?: onClick,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Text(
                            text = "⋮",
                            color = PocketPalette.onSurfaceVariant,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                val statusLabel = libraryStatusLabel(anime) ?: "Bez statusu"
                val watchedLabel = if (totalKnown) "Obejrzano: $safeWatched / ${anime.total}"
                else "Obejrzano: $safeWatched odc."
                val userRatingLabel = anime.rateTotal?.let { "★ $it/10" }
                Text(
                    text = listOfNotNull(statusLabel, watchedLabel, userRatingLabel).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.weight(1f).height(5.dp).clip(CircleShape),
                        color = PocketPalette.primary,
                        trackColor = PocketPalette.surfaceVariant
                    )
                    Text(
                        text = if (percent != null) "$percent%" else "${safeWatched} odc.",
                        style = MaterialTheme.typography.labelSmall,
                        color = PocketPalette.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/** Karta uruchomionego odcinka; nie oznacza zapisu pozycji odtwarzania. */
@Composable
fun RecentPlaybackCard(
    anime: Anime,
    recent: RecentPlayback,
    onContinue: () -> Unit,
    onDetails: () -> Unit,
    userId: Int? = null,
    offlineMode: Boolean = false
) {
    val scheme = MaterialTheme.colorScheme
    PocketCard(modifier = Modifier.width(260.dp), emphasized = true) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(Modifier.width(60.dp).height(90.dp)) {
                    AnimeCover(anime, Modifier.fillMaxSize(), userId = userId)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        anime.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        minLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        recent.episodeNumber?.let { "Ostatnio: odc. $it" } ?: "Ostatnio odtwarzane",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    recent.service?.let { service ->
                        Text(
                            service,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PocketPrimaryButton(
                    label = if (offlineMode) "Szczegóły" else "▶ Wznów",
                    onClick = onContinue,
                    modifier = Modifier.weight(1f)
                )
                if (!offlineMode) {
                    TextButton(onClick = onDetails, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Szczegóły", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
