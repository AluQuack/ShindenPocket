package com.example.shindenpocket.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.shindenpocket.data.*
import com.example.shindenpocket.ui.theme.PocketPalette
import com.example.shindenpocket.ui.theme.PocketRadius

/** Zakładka informacji o anime: opis, multimedia, relacje, oceny, statystyki i obsada. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SeriesOverviewContent(
    titleId: Int,
    seriesTitle: String,
    userId: Int?,
    username: String? = null,
    offlineMode: Boolean,
    libraryAnime: Anime?,
    seriesInfo: SeasonSeriesInfo?,
    infoLoading: Boolean = false,
    fallbackDescription: String? = null,
    fallbackCoverUrl: String? = null,
    fallbackTotalEpisodes: Int = 0,
    onAnimeUpdated: ((Anime) -> Unit)? = null,
    onOpenEpisodes: () -> Unit,
    onRelatedClick: (SeasonAnime) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val description = seriesInfo?.description?.takeIf { it.isNotBlank() }
        ?: fallbackDescription?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PocketPrimaryButton(
            label = "Przeglądaj odcinki  →",
            onClick = onOpenEpisodes,
            modifier = Modifier.fillMaxWidth()
        )

        if (infoLoading && seriesInfo == null) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                color = PocketPalette.primary,
                trackColor = PocketPalette.surfaceVariant
            )
        }

        
        if (userId != null && userId > 0) {
            AnimeManagementSection(
                titleId = titleId,
                seriesTitle = seriesTitle,
                userId = userId,
                offlineMode = offlineMode,
                libraryAnime = libraryAnime,
                seriesInfo = seriesInfo,
                fallbackCoverUrl = fallbackCoverUrl,
                fallbackTotalEpisodes = fallbackTotalEpisodes,
                fallbackDescription = description,
                onAnimeUpdated = onAnimeUpdated
            )
        }

        
        SeriesDescriptionCard(
            description = description,
            tags = seriesInfo?.tags.orEmpty(),
            tagGroups = seriesInfo?.tagGroups.orEmpty()
        )

        
        seriesInfo?.trailer?.let { trailer ->
            SeriesTrailerCard(
                trailer = trailer,
                seriesTitle = seriesTitle
            )
        }

        
        PocketCard(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                RelatedSeriesSection(
                    titleId = titleId,
                    userId = userId,
                    offlineMode = offlineMode,
                    onRelatedClick = onRelatedClick,
                    prefetched = seriesInfo?.relatedSeries
                )
            }
        }

        
        SeriesInfoTableCard(
            libraryAnime = libraryAnime,
            infoFields = seriesInfo?.infoFields.orEmpty()
        )

        
        val hasDistribution = !seriesInfo?.ratingDistribution.isNullOrEmpty()
        val hasStatusList = !seriesInfo?.listStatuses.isNullOrEmpty()
        if (hasDistribution || hasStatusList) {
            SeriesStatsAndRatingsCard(
                distribution = seriesInfo?.ratingDistribution.orEmpty(),
                listStatuses = seriesInfo?.listStatuses.orEmpty()
            )
        }

        
        val characters = seriesInfo?.characters.orEmpty()
        if (characters.isNotEmpty()) {
            SeriesCharactersCard(characters = characters)
        }

        
        AnimeCommentsSection(
            titleId = titleId,
            seriesTitle = seriesTitle,
            userId = userId,
            username = username,
            offlineMode = offlineMode
        )

        Spacer(Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesDescriptionCard(
    description: String?,
    tags: List<String>,
    tagGroups: List<SeriesTagGroup>
) {
    var allTagsExpanded by remember { mutableStateOf(false) }
    val totalCategorizedTags = remember(tagGroups, tags) {
        val groupedCount = tagGroups.sumOf { it.tags.size }
        if (groupedCount > 0) groupedCount else tags.size
    }

    PocketCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Opis",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                text = description ?: "Opis nie jest dostępny w pobranych danych Shindena.",
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 21.sp,
                color = if (description != null) Color(0xFFE4E0EC) else MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tags.forEach { tag ->
                        SeriesTagChip(label = tag)
                    }
                }
            }

            if (tagGroups.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { allTagsExpanded = !allTagsExpanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (allTagsExpanded) {
                            "Ukryj kategorie tagów ($totalCategorizedTags)  ▴"
                        } else {
                            "Wszystkie tagi ($totalCategorizedTags)  ▾"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = PocketPalette.primarySoft
                    )
                }

                if (allTagsExpanded) {
                    Surface(
                        color = PocketPalette.surface,
                        shape = RoundedCornerShape(PocketRadius.control.dp),
                        border = BorderStroke(1.dp, PocketPalette.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            tagGroups.forEach { group ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = group.category,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = PocketPalette.onSurfaceVariant
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        group.tags.forEach { tag ->
                                            SeriesTagChip(label = tag, compact = true)
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

@Composable
private fun SeriesTagChip(
    label: String,
    compact: Boolean = false
) {
    Surface(
        color = PocketPalette.surface,
        shape = CircleShape,
        border = BorderStroke(1.dp, PocketPalette.outlineVariant)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(
                horizontal = if (compact) 10.dp else 13.dp,
                vertical = if (compact) 4.dp else 6.dp
            ),
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            color = Color(0xFFEAE6F2),
            maxLines = 1
        )
    }
}

@Composable
private fun SeriesTrailerCard(
    trailer: SeriesTrailer,
    seriesTitle: String
) {
    val context = LocalContext.current

    fun openExternalUrl(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(context, "Nie udało się otworzyć linku wideo", Toast.LENGTH_SHORT).show()
        }
    }

    PocketCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Card(
                onClick = { openExternalUrl(trailer.videoUrl) },
                modifier = Modifier.fillMaxWidth().height(176.dp),
                shape = RoundedCornerShape(PocketRadius.control.dp),
                border = BorderStroke(1.dp, PocketPalette.outlineVariant),
                colors = CardDefaults.cardColors(containerColor = PocketPalette.surface)
            ) {
                Box(Modifier.fillMaxSize()) {
                    if (trailer.thumbnailUrl != null) {
                        PocketCoverImage(
                            model = trailer.thumbnailUrl,
                            title = trailer.title ?: seriesTitle,
                            contentDescription = trailer.title ?: "Zwiastun $seriesTitle",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.18f),
                                    Color.Black.copy(alpha = 0.45f),
                                    Color.Black.copy(alpha = 0.86f)
                                )
                            )
                        )
                    )
                    
                    Surface(
                        modifier = Modifier.size(50.dp).align(Alignment.Center),
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.72f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "▶",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    val displayTitle = trailer.title ?: trailer.caption ?: "Zwiastun"
                    Text(
                        text = displayTitle,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = trailer.caption ?: "Zwiastun YouTube",
                    style = MaterialTheme.typography.labelMedium,
                    color = PocketPalette.onSurfaceVariant
                )
                val moreLabel = trailer.moreCountLabel
                if (!moreLabel.isNullOrBlank()) {
                    Text(
                        text = moreLabel,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { openExternalUrl(trailer.moreVideosUrl ?: trailer.videoUrl) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = PocketPalette.primarySoft
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesInfoTableCard(
    libraryAnime: Anime?,
    infoFields: List<SeriesInfoField>
) {
    val hasLibraryInfo = libraryAnime != null
    if (!hasLibraryInfo && infoFields.isEmpty()) return

    PocketCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Informacje",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            if (libraryAnime != null) {
                SeriesKeyValueRow(
                    label = "Na Twojej liście",
                    value = buildList {
                        add(libraryStatusLabel(libraryAnime) ?: "Bez statusu")
                        if (libraryAnime.favorite) add("♥ Ulubione")
                        libraryAnime.rateTotal?.let { add("★ $it/10") }
                    }.joinToString(" • "),
                    highlightValue = true
                )
                if (infoFields.isNotEmpty()) {
                    HorizontalDivider(color = PocketPalette.outlineVariant.copy(alpha = 0.6f))
                }
            }

            infoFields.forEachIndexed { index, field ->
                if (index > 0) {
                    HorizontalDivider(color = PocketPalette.outlineVariant.copy(alpha = 0.55f))
                }
                if (field.primaryChips.isNotEmpty()) {
                    var showExtra by remember(field.label) { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = field.label,
                            modifier = Modifier.width(116.dp).padding(top = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = PocketPalette.onSurfaceVariant
                        )
                        FlowRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            field.primaryChips.forEach { chip ->
                                SeriesTagChip(label = chip, compact = true)
                            }
                            if (showExtra) {
                                field.extraChips.forEach { chip ->
                                    SeriesTagChip(label = chip, compact = true)
                                }
                            }
                            if (field.extraChips.isNotEmpty()) {
                                Surface(
                                    onClick = { showExtra = !showExtra },
                                    color = PocketPalette.primaryContainer.copy(alpha = 0.55f),
                                    shape = CircleShape,
                                    border = BorderStroke(1.dp, PocketPalette.primary.copy(alpha = 0.45f))
                                ) {
                                    Text(
                                        text = if (showExtra) "Zwiń" else "+${field.extraChips.size} więcej",
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = PocketPalette.primarySoft
                                    )
                                }
                            }
                        }
                    }
                } else {
                    val isNextEp = field.label.contains("Następny", ignoreCase = true)
                    SeriesKeyValueRow(
                        label = field.label,
                        value = field.value,
                        highlightValue = isNextEp
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesKeyValueRow(
    label: String,
    value: String,
    highlightValue: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.width(116.dp),
            style = MaterialTheme.typography.bodySmall,
            color = PocketPalette.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlightValue) FontWeight.SemiBold else FontWeight.Medium,
            color = if (highlightValue) PocketPalette.primarySoft else Color.White
        )
    }
}

@Composable
private fun SeriesStatsAndRatingsCard(
    distribution: List<SeriesRatingBucket>,
    listStatuses: List<SeriesStatusCount>
) {
    var selectedBucket by remember(distribution) {
        mutableStateOf(distribution.maxByOrNull { it.votes ?: it.heightPercent })
    }

    PocketCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Oceny i statystyki",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            
            if (distribution.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Rozkład ocen",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = PocketPalette.onSurfaceVariant
                        )
                        selectedBucket?.tooltip?.let { tip ->
                            Text(
                                text = tip,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = PocketPalette.primarySoft
                            )
                        }
                    }

                    Surface(
                        color = PocketPalette.surface,
                        shape = RoundedCornerShape(PocketRadius.control.dp),
                        border = BorderStroke(1.dp, PocketPalette.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().height(72.dp),
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                verticalAlignment = Alignment.Bottom
                            ) {
                                distribution.forEach { bucket ->
                                    val isSelected = selectedBucket?.rating == bucket.rating
                                    val fraction = (bucket.heightPercent.coerceIn(0, 100) / 100f)
                                        .let { if (bucket.heightPercent > 0) it.coerceAtLeast(0.06f) else 0.02f }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                            .clickable { selectedBucket = bucket },
                                        contentAlignment = Alignment.BottomCenter
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .fillMaxHeight(fraction)
                                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                                .background(
                                                    if (isSelected) PocketPalette.primarySoft
                                                    else PocketPalette.primary.copy(alpha = 0.85f)
                                                )
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = PocketPalette.outlineVariant)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                distribution.forEach { bucket ->
                                    val isSelected = selectedBucket?.rating == bucket.rating
                                    Text(
                                        text = bucket.rating.toString(),
                                        modifier = Modifier.weight(1f),
                                        textAlign = TextAlign.Center,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else PocketPalette.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            
            if (listStatuses.isNotEmpty()) {
                val mainStatuses = listStatuses.filterNot {
                    it.label.contains("Ulubione", ignoreCase = true)
                }
                val totalOnLists = mainStatuses.sumOf { it.count }.coerceAtLeast(1)

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Na listach",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = PocketPalette.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(PocketPalette.surfaceVariant)
                    ) {
                        mainStatuses.forEach { status ->
                            if (status.count > 0) {
                                val weight = (status.count.toFloat() / totalOnLists).coerceAtLeast(0.015f)
                                Box(
                                    modifier = Modifier
                                        .weight(weight)
                                        .fillMaxHeight()
                                        .background(statusDotColor(status.label))
                                )
                            }
                        }
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        listStatuses.forEach { status ->
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
                                        modifier = Modifier
                                            .size(9.dp)
                                            .clip(CircleShape)
                                            .background(statusDotColor(status.label))
                                    )
                                    Text(
                                        text = status.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PocketPalette.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = status.displayCount,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun statusDotColor(label: String): Color {
    val lower = label.lowercase()
    return when {
        lower.contains("ogląda") -> Color(0xFF0EA5E9)     
        lower.contains("obejrza") -> Color(0xFF10B981)    
        lower.contains("planuje") -> Color(0xFF6366F1)    
        lower.contains("wstrzyma") -> Color(0xFFF59E0B)   
        lower.contains("porzuci") -> Color(0xFFEF4444)    
        lower.contains("pomin") -> Color(0xFF64748B)      
        lower.contains("ulubion") -> Color(0xFFE11D48)    
        else -> PocketPalette.primary
    }
}

@Composable
private fun SeriesCharactersCard(
    characters: List<SeriesCharacter>
) {
    var showAll by remember { mutableStateOf(false) }
    val visibleCharacters = if (showAll) characters else characters.take(4)

    PocketCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Postacie",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "${characters.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = PocketPalette.onSurfaceVariant
                )
            }

            visibleCharacters.forEach { character ->
                Surface(
                    color = PocketPalette.surface,
                    shape = RoundedCornerShape(PocketRadius.control.dp),
                    border = BorderStroke(1.dp, PocketPalette.outlineVariant)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Card(
                                modifier = Modifier.width(44.dp).height(60.dp),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, PocketPalette.outlineVariant)
                            ) {
                                PocketCoverImage(
                                    model = character.imageUrl,
                                    title = character.name,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = character.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (!character.role.isNullOrBlank()) {
                                    Text(
                                        text = character.role,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (character.role.contains("Główna", ignoreCase = true)) {
                                            PocketPalette.primarySoft
                                        } else {
                                            PocketPalette.onSurfaceVariant
                                        },
                                        maxLines = 1
                                    )
                                }
                            }
                        }

                        
                        if (!character.voiceActorName.isNullOrBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = character.voiceActorName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFFE4E0EC),
                                        textAlign = TextAlign.End,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = character.voiceActorRole ?: "Seiyuu",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PocketPalette.onSurfaceVariant,
                                        textAlign = TextAlign.End,
                                        maxLines = 1
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Card(
                                    modifier = Modifier.width(44.dp).height(60.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, PocketPalette.outlineVariant)
                                ) {
                                    PocketCoverImage(
                                        model = character.voiceActorImageUrl,
                                        title = character.voiceActorName,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (characters.size > 4) {
                TextButton(
                    onClick = { showAll = !showAll },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (showAll) "Pokaż mniej postaci  ▴" else "Pokaż wszystkie postacie (${characters.size})  ▾",
                        color = PocketPalette.primarySoft
                    )
                }
            }
        }
    }
}

@Composable
internal fun SeriesEpisodesDashboardCard(
    seriesInfo: SeasonSeriesInfo?,
    filters: List<Pair<String, Int>>,
    selectedFilter: Int,
    onSelectFilter: (Int) -> Unit,
    sortDescending: Boolean,
    onToggleSort: () -> Unit,
    onRefresh: (() -> Unit)? = null,
    refreshEnabled: Boolean = true,
    resumeEpisodeNumber: String? = null,
    resumeEpisodeTitle: String? = null,
    onResumeClick: (() -> Unit)? = null
) {
    val episodeSpec = remember(seriesInfo?.infoFields) {
        seriesInfo?.infoFields
            ?.firstOrNull { it.label.equals("Odcinki", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
    }
    val nextEpisodeValue = remember(seriesInfo?.infoFields) {
        seriesInfo?.infoFields
            ?.firstOrNull { it.label.contains("Następny", ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PocketPalette.surface,
        shape = RoundedCornerShape(PocketRadius.card.dp),
        border = BorderStroke(1.dp, PocketPalette.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "LISTA ODCINKÓW",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = PocketPalette.primarySoft
                    )
                    if (episodeSpec != null) {
                        Text(
                            text = "Format: $episodeSpec",
                            style = MaterialTheme.typography.labelSmall,
                            color = PocketPalette.onSurfaceVariant
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onToggleSort,
                        color = PocketPalette.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, PocketPalette.outlineVariant)
                    ) {
                        Text(
                            text = if (sortDescending) "Najnowsze ↓" else "Od 1 ↑",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                    if (onRefresh != null) {
                        Surface(
                            onClick = { if (refreshEnabled) onRefresh() },
                            color = PocketPalette.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, PocketPalette.outlineVariant)
                        ) {
                            Text(
                                text = "↻",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (refreshEnabled) PocketPalette.primarySoft else PocketPalette.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Termin następnego odcinka pochodzi z `.sh-tp-info`.
            if (nextEpisodeValue != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = PocketPalette.primaryContainer.copy(alpha = 0.34f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, PocketPalette.primary.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⏱  Następny odcinek",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFE4E0EC)
                        )
                        Text(
                            text = "Odc. $nextEpisodeValue",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = PocketPalette.primarySoft
                        )
                    }
                }
            }

            
            if (resumeEpisodeNumber != null && onResumeClick != null) {
                Surface(
                    onClick = onResumeClick,
                    modifier = Modifier.fillMaxWidth(),
                    color = PocketPalette.surfaceRaised,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, PocketPalette.primary.copy(alpha = 0.42f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "KOLEJNY DO OBEJRZENIA",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = PocketPalette.primarySoft
                            )
                            Text(
                                text = buildString {
                                    append("Odcinek $resumeEpisodeNumber")
                                    if (!resumeEpisodeTitle.isNullOrBlank()) {
                                        append(" · ")
                                        append(resumeEpisodeTitle)
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = PocketPalette.primaryContainer,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, PocketPalette.primary.copy(alpha = 0.55f))
                        ) {
                            Text(
                                text = "▶ Wybierz",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            
            // Filtry `.sh-tp` pozostają w jednym rzędzie.
            if (filters.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    filters.forEachIndexed { index, (label, count) ->
                        val selected = selectedFilter == index
                        Surface(
                            onClick = { onSelectFilter(index) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) PocketPalette.primaryContainer else PocketPalette.surfaceRaised,
                            border = BorderStroke(
                                1.dp,
                                if (selected) PocketPalette.primary else PocketPalette.outlineVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selected) Color.White else PocketPalette.onSurfaceVariant,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Surface(
                                    color = if (selected) PocketPalette.primary else PocketPalette.surfaceVariant,
                                    shape = RoundedCornerShape(5.dp)
                                ) {
                                    Text(
                                        text = count.toString(),
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selected) Color.White else PocketPalette.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false
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

@Composable
internal fun SeriesEpisodeSheetHeader(
    episodeNumber: String,
    episodeTitle: String,
    seriesTitle: String,
    online: Boolean?,
    airDate: String?,
    languages: List<String>,
    watched: Boolean? = null
) {
    val formattedDate = remember(airDate) { formatEpisodeAirDate(airDate) ?: airDate }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = PocketPalette.primaryContainer,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(1.dp, PocketPalette.primary.copy(alpha = 0.55f))
            ) {
                Text(
                    text = "ODCINEK $episodeNumber",
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
            }
            Surface(
                color = when (online) {
                    true -> PocketPalette.primary.copy(alpha = 0.18f)
                    false -> PocketPalette.surfaceVariant
                    null -> PocketPalette.surfaceVariant
                },
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(
                    1.dp,
                    if (online == true) PocketPalette.primary.copy(alpha = 0.45f) else PocketPalette.outlineVariant
                )
            ) {
                Text(
                    text = when (online) {
                        true -> "▶ Wersja online"
                        false -> "○ Brak online"
                        null -> "· Status nieustalony"
                    },
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (online == true) PocketPalette.primarySoft else PocketPalette.onSurfaceVariant
                )
            }
            if (watched != null) {
                Surface(
                    color = if (watched) PocketPalette.primary.copy(alpha = 0.22f) else PocketPalette.surfaceVariant,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(
                        1.dp,
                        if (watched) PocketPalette.primary.copy(alpha = 0.50f) else PocketPalette.outlineVariant
                    )
                ) {
                    Text(
                        text = if (watched) "✓ Obejrzany" else "Nieobejrzany",
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (watched) PocketPalette.primarySoft else PocketPalette.onSurfaceVariant
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = episodeTitle.ifBlank { "Odcinek $episodeNumber" },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
            Text(
                text = seriesTitle,
                style = MaterialTheme.typography.bodySmall,
                color = PocketPalette.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                color = PocketPalette.surface,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, PocketPalette.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Data emisji",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = PocketPalette.onSurfaceVariant
                    )
                    Text(
                        text = formattedDate ?: "Brak daty",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Surface(
                modifier = Modifier.weight(1f),
                color = PocketPalette.surface,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, PocketPalette.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Języki napisów/audio",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = PocketPalette.onSurfaceVariant
                    )
                    Text(
                        text = if (languages.isNotEmpty()) languages.joinToString(" · ") else "Brak danych",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
