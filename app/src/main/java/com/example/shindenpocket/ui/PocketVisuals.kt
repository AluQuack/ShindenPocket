package com.example.shindenpocket.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.shindenpocket.data.Anime
import com.example.shindenpocket.data.OfflineLibraryStore
import com.example.shindenpocket.data.SeasonSeriesInfo
import com.example.shindenpocket.data.WatchStatusOption
import com.example.shindenpocket.ui.theme.PocketPalette
import com.example.shindenpocket.ui.theme.PocketRadius
import com.example.shindenpocket.ui.theme.PocketSpacing

internal val PocketViolet = PocketPalette.primary
internal val PocketBackground = PocketPalette.background
internal val PocketCard = PocketPalette.surfaceRaised

private val HeroScrim = Brush.horizontalGradient(
    listOf(
        PocketPalette.background,
        PocketPalette.background.copy(alpha = 0.96f),
        PocketPalette.background.copy(alpha = 0.38f),
        PocketPalette.background.copy(alpha = 0.10f)
    )
)

internal enum class PocketCardStyle { WIDE, COMPACT, POSTER }

internal enum class PocketMessageType { INFO, SUCCESS, ERROR }

internal fun libraryStatusLabel(anime: Anime?): String? =
    WatchStatusOption.fromCode(anime?.watchStatus).takeIf { it != WatchStatusOption.NONE }?.label

@Composable
internal fun StatusPill(anime: Anime?, compact: Boolean = false) {
    val statusOpt = WatchStatusOption.fromCode(anime?.watchStatus)
        .takeIf { it != WatchStatusOption.NONE } ?: return
    val text = statusOpt.label
    val completed = statusOpt == WatchStatusOption.COMPLETED
    Surface(
        color = if (completed) PocketPalette.success.copy(alpha = 0.16f)
        else PocketPalette.primaryContainer.copy(alpha = 0.96f),
        contentColor = if (completed) PocketPalette.success else PocketPalette.onPrimaryContainer,
        shape = CircleShape
    ) {
        Text(
            text = text,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            fontSize = if (compact) 9.sp else 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(
                horizontal = if (compact) 7.dp else 10.dp,
                vertical = if (compact) 4.dp else 5.dp
            )
        )
    }
}

@Composable
internal fun PocketCard(
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(PocketRadius.card.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (emphasized) scheme.primaryContainer.copy(alpha = 0.42f)
            else scheme.surfaceContainer
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (emphasized) scheme.primary.copy(alpha = 0.20f) else scheme.outlineVariant
        ),
        content = content
    )
}

@Composable
internal fun PocketScreenHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PocketSpacing.compact.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(PocketSpacing.tiny.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PocketSpacing.tiny.dp),
            content = actions
        )
    }
}

@Composable
internal fun PocketBackButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = "‹  $label",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

@Composable
internal fun PocketSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = {
            Text(placeholder, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingIcon = {
            Text("⌕", color = scheme.secondary, fontSize = 21.sp)
        },
        trailingIcon = {
            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(48.dp).semantics { contentDescription = "Wyczyść wyszukiwanie" }
                ) {
                    Text("✕", color = scheme.onSurfaceVariant, fontSize = 14.sp)
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(PocketRadius.control.dp),
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = scheme.surface,
            unfocusedContainerColor = scheme.surface,
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = scheme.outlineVariant,
            focusedTextColor = scheme.onSurface,
            unfocusedTextColor = scheme.onSurface,
            cursorColor = scheme.primary
        )
    )
}

@Composable
internal fun PocketPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(PocketRadius.control.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun PocketSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        shape = RoundedCornerShape(PocketRadius.control.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun PocketSectionHeader(
    title: String,
    action: String? = null,
    onAction: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PocketSpacing.compact.dp)
    ) {
        Box(
            Modifier.width(4.dp).height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (action != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(
                    "$action  →",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun PocketMessage(
    message: String,
    modifier: Modifier = Modifier,
    type: PocketMessageType = PocketMessageType.INFO,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val scheme = MaterialTheme.colorScheme
    val container = when (type) {
        PocketMessageType.INFO -> scheme.primaryContainer.copy(alpha = 0.36f)
        PocketMessageType.SUCCESS -> scheme.tertiaryContainer
        PocketMessageType.ERROR -> scheme.errorContainer.copy(alpha = 0.72f)
    }
    val content = when (type) {
        PocketMessageType.INFO -> scheme.onPrimaryContainer
        PocketMessageType.SUCCESS -> scheme.onTertiaryContainer
        PocketMessageType.ERROR -> scheme.onErrorContainer
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PocketRadius.control.dp),
        color = container,
        border = BorderStroke(1.dp, content.copy(alpha = 0.18f))
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = if (actionLabel == null) 14.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PocketSpacing.compact.dp)
        ) {
            Text(
                text = when (type) {
                    PocketMessageType.INFO -> "●"
                    PocketMessageType.SUCCESS -> "✓"
                    PocketMessageType.ERROR -> "!"
                },
                color = content,
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                text = message,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = content
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(actionLabel, color = content, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun PocketLoadingState(
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().heightIn(min = 160.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun PocketEmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    symbol: String = "◇",
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PocketRadius.card.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(PocketRadius.card.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.64f)),
                contentAlignment = Alignment.Center
            ) {
                Text(symbol, color = MaterialTheme.colorScheme.secondary, fontSize = 25.sp)
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(actionLabel, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@Composable
internal fun PocketErrorState(
    title: String,
    message: String,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    PocketEmptyState(
        title = title,
        message = message,
        modifier = modifier,
        symbol = "!",
        actionLabel = if (onRetry != null) "Spróbuj ponownie" else null,
        onAction = onRetry
    )
}

@Composable
internal fun PocketTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeIndex = selectedIndex.coerceIn(0, labels.lastIndex)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = PocketPalette.surface,
        shape = RoundedCornerShape(PocketRadius.control.dp),
        border = BorderStroke(1.dp, PocketPalette.outlineVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            labels.forEachIndexed { index, label ->
                val selected = safeIndex == index
                Surface(
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp),
                    color = if (selected) PocketPalette.primaryContainer else Color.Transparent,
                    contentColor = if (selected) Color.White else PocketPalette.onSurfaceVariant,
                    shape = RoundedCornerShape((PocketRadius.control - 3).dp),
                    border = if (selected) {
                        BorderStroke(1.dp, PocketPalette.primary.copy(alpha = 0.55f))
                    } else null
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) Color.White else PocketPalette.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

internal fun formatEpisodeAirDate(raw: String?): String? {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    val datePart = trimmed.substringBefore('T').substringBefore(' ')
    val pieces = datePart.split('-')
    if (pieces.size == 3) {
        val year = pieces[0]
        val month = pieces[1].toIntOrNull()
        val day = pieces[2].toIntOrNull()
        if (year.length == 4 && month != null && day != null) {
            val months = arrayOf(
                "sty", "lut", "mar", "kwi", "maj", "cze",
                "lip", "sie", "wrz", "paź", "lis", "gru"
            )
            val mLabel = months.getOrNull(month - 1) ?: pieces[1]
            return "$day $mLabel $year"
        }
    }
    return trimmed
}

internal fun languageShortCode(lang: String): String? {
    val lower = lang.trim().lowercase()
    if (lower == "ipl") return "Polski AI"
    val code = when {
        lower.startsWith("pol") || lower == "pl" -> "PL"
        lower.startsWith("ang") || lower.startsWith("eng") || lower == "en" -> "EN"
        lower.startsWith("jap") || lower == "jp" || lower == "ja" -> "JP"
        lower.startsWith("niem") || lower == "de" -> "DE"
        lower.startsWith("hisz") || lower == "es" -> "ES"
        lower.startsWith("fran") || lower == "fr" -> "FR"
        lower.length in 2..3 -> lower.uppercase()
        else -> null
    }
    val machineTranslated = lower.contains("maszyn") ||
        Regex("""(?:^|\W)ai(?:\W|$)""").containsMatchIn(lower)
    return code?.let { if (machineTranslated && it == "PL") "Polski AI" else it }
}

@Composable
internal fun PocketEpisodeCard(
    number: String,
    title: String,
    online: Boolean?,
    airDate: String?,
    languages: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    watched: Boolean? = null
) {
    val scheme = MaterialTheme.colorScheme
    val formattedDate = remember(airDate) { formatEpisodeAirDate(airDate) }
    val isWatched = watched == true
    val isOnline = online == true
    val isUpcoming = online == false && title.isBlank() && !formattedDate.isNullOrBlank()

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PocketRadius.card.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isWatched -> PocketPalette.surface
                isOnline -> PocketPalette.surfaceRaised
                else -> PocketPalette.surface.copy(alpha = 0.85f)
            }
        ),
        border = BorderStroke(
            1.dp,
            when {
                isWatched -> PocketPalette.primary.copy(alpha = 0.48f)
                isOnline -> PocketPalette.outlineVariant
                else -> PocketPalette.outlineVariant.copy(alpha = 0.55f)
            }
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            
            Surface(
                modifier = Modifier.size(46.dp),
                shape = RoundedCornerShape(10.dp),
                color = when {
                    isWatched -> PocketPalette.primaryContainer
                    isOnline -> PocketPalette.surfaceVariant
                    else -> PocketPalette.surfaceRaised
                },
                border = BorderStroke(
                    1.dp,
                    when {
                        isWatched -> PocketPalette.primary.copy(alpha = 0.60f)
                        isOnline -> PocketPalette.outlineVariant
                        else -> PocketPalette.outlineVariant.copy(alpha = 0.45f)
                    }
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = when {
                            isWatched -> "✓ ODC"
                            else -> "ODC"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 8.sp,
                        lineHeight = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isWatched) PocketPalette.primarySoft else PocketPalette.onSurfaceVariant
                    )
                    Text(
                        text = number,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isWatched || isOnline) Color.White else scheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
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
                        text = if (title.isNotBlank()) title else "Odcinek $number",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (online == false && !isWatched) Color(0xFFD5CFE2) else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (watched != null) {
                        Surface(
                            color = if (isWatched) PocketPalette.primary.copy(alpha = 0.20f)
                            else PocketPalette.surfaceVariant.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(5.dp),
                            border = if (isWatched) {
                                BorderStroke(1.dp, PocketPalette.primary.copy(alpha = 0.40f))
                            } else null
                        ) {
                            Text(
                                text = if (isWatched) "✓ Obejrzany" else "Do obejrzenia",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isWatched) PocketPalette.primarySoft else scheme.onSurfaceVariant
                            )
                        }
                    }
                }

                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (title.isNotBlank()) {
                        Text(
                            text = "Odcinek $number",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = PocketPalette.primarySoft,
                            maxLines = 1
                        )
                    }
                    if (!formattedDate.isNullOrBlank()) {
                        if (title.isNotBlank()) {
                            Text(
                                text = "·",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = formattedDate,
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    languages.take(2).forEach { lang ->
                        val code = languageShortCode(lang)
                        Surface(
                            color = PocketPalette.surfaceHighest,
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, PocketPalette.outlineVariant)
                        ) {
                            Text(
                                text = code ?: lang,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE4E0EC),
                                maxLines = 1
                            )
                        }
                    }
                    if (languages.size > 2) {
                        Text(
                            text = "+${languages.size - 2}",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }

            
            Surface(
                color = when (online) {
                    true -> PocketPalette.primaryContainer
                    false -> PocketPalette.surfaceVariant.copy(alpha = 0.72f)
                    null -> PocketPalette.surfaceVariant
                },
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(
                    1.dp,
                    when (online) {
                        true -> PocketPalette.primary.copy(alpha = 0.55f)
                        false -> PocketPalette.outlineVariant.copy(alpha = 0.65f)
                        null -> PocketPalette.outlineVariant
                    }
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = when {
                            isOnline -> "▶"
                            isUpcoming -> "⏳"
                            online == false -> "○"
                            else -> "·"
                        },
                        fontSize = 9.sp,
                        color = if (isOnline) PocketPalette.primarySoft else scheme.onSurfaceVariant
                    )
                    Text(
                        text = when {
                            isOnline -> "Online"
                            isUpcoming -> "Wkrótce"
                            online == false -> "Brak"
                            else -> "Sprawdź"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isOnline) Color.White else scheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
internal fun PocketCoverImage(
    model: Any?,
    title: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = "Okładka $title",
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    val request = remember(context, model) {
        ImageRequest.Builder(context).data(model).crossfade(true).build()
    }
    SubcomposeAsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        loading = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        },
        error = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "SP",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
                )
            }
        }
    )
}

/** Awatar Shindena z awaryjną literą, gdy adres jest pusty lub obraz się nie wczyta. */
@Composable
internal fun PocketAvatar(
    url: String?,
    name: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color = PocketPalette.surfaceVariant,
    letterColor: Color = Color.White,
    letterStyle: TextStyle = MaterialTheme.typography.labelMedium
) {
    val context = LocalContext.current
    val initial: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = name.trim().take(1).uppercase().ifBlank { "?" },
                style = letterStyle,
                fontWeight = FontWeight.Bold,
                color = letterColor
            )
        }
    }
    Surface(shape = CircleShape, color = backgroundColor, modifier = modifier) {
        if (url.isNullOrBlank()) {
            initial()
        } else {
            SubcomposeAsyncImage(
                model = remember(context, url) {
                    ImageRequest.Builder(context).data(url).crossfade(true).build()
                },
                contentDescription = "Awatar $name",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = { initial() },
                error = { initial() }
            )
        }
    }
}

@Composable
internal fun PocketTopBar(onSearch: () -> Unit, onAccount: () -> Unit) {
    val iconColor = MaterialTheme.colorScheme.onBackground
    Row(
        Modifier.fillMaxWidth().height(60.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.linearGradient(
                        listOf(PocketPalette.primary, PocketPalette.primaryContainer)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "SP",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            "Shinden",
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Pocket",
            fontSize = 22.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            color = PocketViolet,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onSearch,
            modifier = Modifier.size(48.dp).semantics { contentDescription = "Szukaj w katalogu anime" }
        ) {
            Canvas(Modifier.size(21.dp)) {
                drawCircle(
                    iconColor,
                    radius = size.minDimension * 0.32f,
                    center = Offset(size.width * 0.43f, size.height * 0.42f),
                    style = Stroke(width = 1.9.dp.toPx())
                )
                drawLine(
                    iconColor,
                    start = Offset(size.width * 0.66f, size.height * 0.67f),
                    end = Offset(size.width * 0.94f, size.height * 0.95f),
                    strokeWidth = 1.9.dp.toPx()
                )
            }
        }
        IconButton(
            onClick = onAccount,
            modifier = Modifier.size(48.dp).semantics { contentDescription = "Konto i ustawienia" }
        ) {
            Canvas(Modifier.size(22.dp)) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = size.minDimension * 0.28f
                drawCircle(
                    PocketViolet,
                    radius = r,
                    center = c,
                    style = Stroke(width = 2.dp.toPx())
                )
                drawCircle(
                    PocketViolet,
                    radius = size.minDimension * 0.10f,
                    center = c
                )
                for (i in 0 until 8) {
                    val angle = Math.toRadians((i * 45).toDouble())
                    val cos = kotlin.math.cos(angle).toFloat()
                    val sin = kotlin.math.sin(angle).toFloat()
                    drawLine(
                        color = PocketViolet,
                        start = Offset(c.x + cos * (r * 1.05f), c.y + sin * (r * 1.05f)),
                        end = Offset(c.x + cos * (r * 1.48f), c.y + sin * (r * 1.48f)),
                        strokeWidth = 2.2.dp.toPx()
                    )
                }
            }
        }
    }
}

@Composable
internal fun PocketPoster(
    title: String,
    coverUrl: String?,
    libraryAnime: Anime?,
    userId: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.width(114.dp),
    subtitle: String? = null,
    relationLabel: String? = null,
    style: PocketCardStyle = PocketCardStyle.WIDE,
    newEpisodeNumber: String? = null
) {
    val context = LocalContext.current
    val posterHeight = when (style) {
        PocketCardStyle.WIDE -> 164.dp
        PocketCardStyle.COMPACT -> 154.dp
        PocketCardStyle.POSTER -> 164.dp
    }
    val image = remember(libraryAnime?.coverId, userId, coverUrl) {
        libraryAnime?.let {
            OfflineLibraryStore.coverFile(context, userId, it.coverId) ?: getCoverUrl(it.coverId)
        } ?: coverUrl
    }
    val subText = subtitle ?: when {
        newEpisodeNumber != null -> "Odc. $newEpisodeNumber"
        libraryAnime != null && libraryAnime.total > 0 ->
            if (libraryAnime.watched > 0) "${libraryAnime.watched} / ${libraryAnime.total} odc."
            else "${libraryAnime.total} odc."
        libraryAnime != null && libraryAnime.watched > 0 -> "Obejrzano ${libraryAnime.watched} odc."
        else -> null
    }

    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(PocketRadius.poster.dp),
        colors = CardDefaults.cardColors(containerColor = PocketPalette.surfaceRaised),
        border = BorderStroke(1.dp, PocketPalette.outlineVariant)
    ) {
        Box(
            Modifier.fillMaxWidth()
                .then(
                    if (style == PocketCardStyle.POSTER) Modifier.aspectRatio(0.68f)
                    else Modifier.height(posterHeight)
                )
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            PocketCoverImage(
                model = image,
                title = title,
                modifier = Modifier.fillMaxSize()
            )
            
            Box(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.62f)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color(0xCC09090D),
                                Color(0xF509090D)
                            )
                        )
                    )
            )
            when {
                newEpisodeNumber != null -> Surface(
                    Modifier.align(Alignment.TopEnd).padding(6.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = PocketPalette.primary
                ) {
                    Text(
                        "NEW",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        maxLines = 1
                    )
                }
                relationLabel != null -> Surface(
                    Modifier.align(Alignment.TopStart).padding(6.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = PocketPalette.primary
                ) {
                    Text(
                        relationLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        maxLines = 1
                    )
                }
                libraryAnime != null -> Box(Modifier.align(Alignment.TopStart).padding(6.dp)) {
                    StatusPill(libraryAnime, compact = true)
                }
            }
            if (libraryAnime?.favorite == true && newEpisodeNumber == null) {
                Surface(
                    Modifier.align(Alignment.TopEnd).padding(6.dp),
                    color = Color.Black.copy(alpha = 0.68f),
                    shape = CircleShape
                ) {
                    Text(
                        "♥",
                        color = PocketViolet,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subText.isNullOrBlank()) {
                    Text(
                        text = subText,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = PocketPalette.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (libraryAnime != null && libraryAnime.total > 0 && libraryAnime.watched > 0) {
                    Spacer(Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { (libraryAnime.watched.coerceAtLeast(0).toFloat() / libraryAnime.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape),
                        color = PocketViolet,
                        trackColor = Color.White.copy(alpha = 0.24f)
                    )
                }
            }
        }
    }
}

@Composable
internal fun PocketHero(
    title: String,
    coverUrl: String?,
    description: String,
    actionText: String,
    onPrimary: () -> Unit,
    onDetails: (() -> Unit)? = null,
    status: Anime? = null,
    userId: Int? = null,
    episodeLabel: String? = null
) {
    val context = LocalContext.current
    val image = remember(status?.coverId, userId, coverUrl) {
        status?.let { OfflineLibraryStore.coverFile(context, userId, it.coverId) ?: getCoverUrl(it.coverId) }
            ?: coverUrl
    }
    val watched = status?.watched?.coerceAtLeast(0) ?: 0
    val total = status?.total ?: 0
    val progress = if (total > 0) (watched.toFloat() / total).coerceIn(0f, 1f) else null
    val percent = progress?.let { (it * 100).toInt() }
    val subtitle = episodeLabel ?: when {
        status != null && total > 0 -> "Odcinek ${watched.coerceAtLeast(1)} z $total"
        status != null && watched > 0 -> "Obejrzano $watched odc."
        description.isNotBlank() -> description
        else -> "Polecane anime"
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            onClick = onDetails ?: onPrimary,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(PocketRadius.large.dp),
            colors = CardDefaults.cardColors(containerColor = PocketCard),
            border = BorderStroke(1.dp, PocketPalette.primary.copy(alpha = 0.28f))
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 216.dp)) {
                PocketCoverImage(
                    model = image,
                    title = title,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize()
                )
                Box(Modifier.matchParentSize().background(HeroScrim))
                Column(
                    Modifier.fillMaxWidth(0.88f).heightIn(min = 216.dp).padding(18.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.Start
                ) {
                    
                    Surface(
                        color = PocketPalette.primaryContainer.copy(alpha = 0.85f),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, PocketPalette.primary.copy(alpha = 0.45f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier.size(18.dp).clip(CircleShape).background(PocketPalette.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("▶", color = Color.White, fontSize = 8.sp)
                            }
                            Text(
                                text = if (status != null) "Kontynuuj oglądanie  ›" else "Polecane w sezonie  ›",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            subtitle,
                            color = Color(0xFFD8D2E4),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (progress != null && percent != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(0.78f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.weight(1f).height(6.dp).clip(CircleShape),
                                    color = PocketViolet,
                                    trackColor = Color.White.copy(alpha = 0.20f)
                                )
                                Text(
                                    "$percent%",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = onPrimary,
                                modifier = Modifier.heightIn(min = 44.dp),
                                shape = CircleShape,
                                contentPadding = PaddingValues(horizontal = 22.dp, vertical = 10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = PocketViolet,
                                    contentColor = Color.White
                                )
                            ) {
                                Text(
                                    text = if (status != null && !actionText.contains("Szczegóły")) "▶   Wznów" else actionText,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1
                                )
                            }
                            if (onDetails != null) {
                                OutlinedButton(
                                    onClick = onDetails,
                                    modifier = Modifier.heightIn(min = 44.dp),
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f))
                                ) {
                                    Text(
                                        "Szczegóły",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White,
                                        maxLines = 1
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
internal fun PocketDetailsHeader(
    title: String,
    coverUrl: String?,
    userId: Int?,
    libraryAnime: Anime?,
    subtitle: String,
    watched: Int? = null,
    total: Int? = null,
    seriesInfo: SeasonSeriesInfo? = null,
    expanded: Boolean = true
) {
    val context = LocalContext.current
    val image = remember(libraryAnime?.coverId, userId, coverUrl, seriesInfo?.coverUrl) {
        libraryAnime?.let {
            OfflineLibraryStore.coverFile(context, userId, it.coverId) ?: getCoverUrl(it.coverId)
        } ?: coverUrl ?: seriesInfo?.coverUrl
    }
    val backdropModel = seriesInfo?.bannerUrl ?: image
    val progress = if (watched != null && total != null && total > 0) {
        (watched.coerceAtLeast(0).toFloat() / total).coerceIn(0f, 1f)
    } else null
    val percent = progress?.let { (it * 100).toInt() }
    val rating = seriesInfo?.rating?.takeUnless { it.isBlank() }
        ?: libraryAnime?.rating?.takeUnless { it.isBlank() || it == "null" }
    val kindLabel = seriesInfo?.kind?.takeIf { it.isNotBlank() }
    val metaLine = remember(seriesInfo?.metaBadges, subtitle) {
        if (!seriesInfo?.metaBadges.isNullOrEmpty()) {
            seriesInfo!!.metaBadges.joinToString("  ·  ")
        } else {
            subtitle
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PocketRadius.card.dp),
        colors = CardDefaults.cardColors(containerColor = PocketCard),
        border = BorderStroke(1.dp, PocketPalette.primary.copy(alpha = 0.28f))
    ) {
        Box(Modifier.fillMaxWidth()) {
            PocketCoverImage(
                model = backdropModel,
                title = title,
                contentDescription = null,
                modifier = Modifier.matchParentSize()
            )
            
            Box(
                Modifier.matchParentSize().background(
                    Brush.horizontalGradient(
                        listOf(
                            PocketPalette.background.copy(alpha = 0.94f),
                            PocketPalette.background.copy(alpha = 0.86f),
                            PocketPalette.background.copy(alpha = 0.68f)
                        )
                    )
                )
            )
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(
                        listOf(
                            PocketPalette.background.copy(alpha = 0.35f),
                            PocketPalette.background.copy(alpha = 0.88f),
                            PocketPalette.background.copy(alpha = 0.98f)
                        )
                    )
                )
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(if (expanded) 14.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (expanded) 12.dp else 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (expanded) 14.dp else 12.dp),
                    verticalAlignment = if (expanded) Alignment.Top else Alignment.CenterVertically
                ) {
                    
                    Card(
                        modifier = Modifier
                            .width(if (expanded) 102.dp else 64.dp)
                            .height(if (expanded) 148.dp else 92.dp),
                        shape = RoundedCornerShape(PocketRadius.control.dp),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
                        colors = CardDefaults.cardColors(containerColor = PocketPalette.surfaceVariant)
                    ) {
                        PocketCoverImage(
                            model = image,
                            title = title,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (kindLabel != null) {
                                Surface(
                                    color = PocketPalette.surfaceVariant.copy(alpha = 0.85f),
                                    shape = CircleShape,
                                    border = BorderStroke(1.dp, PocketPalette.outlineVariant)
                                ) {
                                    Text(
                                        text = kindLabel,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = PocketPalette.primarySoft
                                    )
                                }
                            }
                            if (libraryAnime != null) {
                                StatusPill(libraryAnime, compact = true)
                            }
                            if (!expanded && !rating.isNullOrBlank()) {
                                Surface(
                                    color = Color(0xFFF59E0B).copy(alpha = 0.16f),
                                    shape = CircleShape,
                                    border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.38f))
                                ) {
                                    Text(
                                        text = "★ $rating",
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFBBF24)
                                    )
                                }
                            }
                        }

                        Text(
                            text = title,
                            style = if (expanded) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = if (expanded) 3 else 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (metaLine.isNotBlank()) {
                            Text(
                                text = metaLine,
                                color = PocketPalette.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        
                        val altTitles = if (expanded) seriesInfo?.altTitles.orEmpty().take(3) else emptyList()
                        if (altTitles.isNotEmpty()) {
                            Column(
                                modifier = Modifier.padding(top = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                altTitles.forEach { alt ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            color = PocketPalette.surfaceHighest,
                                            shape = RoundedCornerShape(4.dp),
                                            border = BorderStroke(1.dp, PocketPalette.outlineVariant)
                                        ) {
                                            Text(
                                                text = alt.langCode.uppercase(),
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = PocketPalette.onSurfaceVariant
                                            )
                                        }
                                        Text(
                                            text = alt.title,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFFD5CFE2),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        val favLabel = if (expanded) seriesInfo?.favoritesLabel else null
                        if (!favLabel.isNullOrBlank()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Text(
                                    text = "♥",
                                    color = Color(0xFFF43F5E),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = favLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = PocketPalette.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                val subRatings = seriesInfo?.subRatings.orEmpty()
                if (expanded && (!rating.isNullOrBlank() || subRatings.isNotEmpty())) {
                    HorizontalDivider(color = PocketPalette.outlineVariant.copy(alpha = 0.65f))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!rating.isNullOrBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.Bottom,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = rating,
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "/ 10",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = PocketPalette.onSurfaceVariant,
                                        modifier = Modifier.padding(bottom = 3.dp)
                                    )
                                }
                                val votesAndRank = listOfNotNull(
                                    seriesInfo?.votesCount,
                                    seriesInfo?.rankingPosition
                                ).joinToString("   ")
                                if (votesAndRank.isNotBlank()) {
                                    Text(
                                        text = votesAndRank,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = PocketPalette.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (subRatings.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                subRatings.take(4).forEach { sub ->
                                    Surface(
                                        modifier = Modifier.weight(1f),
                                        color = PocketPalette.surface.copy(alpha = 0.82f),
                                        shape = RoundedCornerShape(10.dp),
                                        border = BorderStroke(1.dp, PocketPalette.outlineVariant)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = sub.score,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = Color.White,
                                                maxLines = 1
                                            )
                                            Text(
                                                text = sub.label,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 10.sp,
                                                color = PocketPalette.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (watched != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Obejrzano: $watched / ${total ?: "?"} odc.",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (percent != null) {
                                Text(
                                    text = "$percent%",
                                    color = PocketPalette.primarySoft,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                                color = PocketViolet,
                                trackColor = Color.White.copy(alpha = 0.22f)
                            )
                        }
                    }
                }
            }
        }
    }
}
