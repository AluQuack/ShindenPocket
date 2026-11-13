package com.example.shindenpocket.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shindenpocket.data.EpisodeSource
import com.example.shindenpocket.ui.theme.PocketPalette

internal val playerBackground = PocketPalette.playerBackground
internal val playerPanel = PocketPalette.playerPanel
internal val playerAccent = PocketPalette.playerAccent
internal val playerMuted = PocketPalette.playerMuted

/**
 * Zwarty pasek sterowania odtwarzaczem (przejście do poprzedniego/następnego odcinka oraz wybór źródła).
 * Przyciski nawigacji pozostają zawsze pod ręką, nawet gdy nazwa źródła jest długa.
 */
@Composable
internal fun PlayerControls(
    source: EpisodeSource,
    sourceIndex: Int,
    sources: List<EpisodeSource>,
    onSourceChange: (Int) -> Unit,
    onPreviousEpisode: (() -> Unit)?,
    onNextEpisode: (() -> Unit)?,
    movingEpisode: Boolean,
    modifier: Modifier = Modifier
) {
    var sourceMenuOpen by remember { mutableStateOf(false) }
    val canSwitchSource = sources.size > 1 && !movingEpisode
    val canGoPrevious = onPreviousEpisode != null && !movingEpisode
    val canGoNext = onNextEpisode != null && !movingEpisode

    LaunchedEffect(movingEpisode, sources.size) {
        if (movingEpisode || sources.size <= 1) {
            sourceMenuOpen = false
        }
    }

    Row(
        modifier = modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        IconButton(
            onClick = { onPreviousEpisode?.invoke() },
            enabled = canGoPrevious,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "Poprzedni odcinek" }
        ) {
            Text(
                text = "‹",
                color = if (canGoPrevious) playerAccent else playerMuted,
                fontSize = 32.sp
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            TextButton(
                onClick = { sourceMenuOpen = true },
                enabled = canSwitchSource,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${source.service}  ▾",
                    color = if (canSwitchSource) playerAccent else playerMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            DropdownMenu(
                expanded = sourceMenuOpen,
                onDismissRequest = { sourceMenuOpen = false }
            ) {
                sources.forEachIndexed { index, option ->
                    val isSelected = index == sourceIndex
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.formatDropdownLabel(isSelected = isSelected),
                                color = if (isSelected) playerAccent else Color.Unspecified,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        onClick = {
                            sourceMenuOpen = false
                            if (index != sourceIndex) {
                                onSourceChange(index)
                            }
                        }
                    )
                }
            }
        }

        IconButton(
            onClick = { onNextEpisode?.invoke() },
            enabled = canGoNext,
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "Następny odcinek" }
        ) {
            Text(
                text = "›",
                color = if (canGoNext) playerAccent else playerMuted,
                fontSize = 32.sp
            )
        }
    }
}

internal fun EpisodeSource.formatHeaderSubtitle(): String = buildString {
    append(service)
    if (!quality.isNullOrBlank()) {
        append(" · ")
        append(quality)
    }
    if (languages.isNotEmpty()) {
        append(" · ")
        append(languages.joinToString("/"))
    }
}

internal fun EpisodeSource.formatDropdownLabel(isSelected: Boolean): String = buildString {
    if (isSelected) {
        append("✓ ")
    }
    append(service)
    if (!quality.isNullOrBlank()) {
        append(" · ")
        append(quality)
    }
    if (languages.isNotEmpty()) {
        append(" (")
        append(languages.joinToString(", "))
        append(")")
    }
}
