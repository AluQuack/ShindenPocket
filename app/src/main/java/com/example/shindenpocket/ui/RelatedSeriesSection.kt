package com.example.shindenpocket.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.shindenpocket.data.RelatedSeries
import com.example.shindenpocket.data.SeasonAnime
import com.example.shindenpocket.data.SeasonSeriesApi
import kotlinx.coroutines.CancellationException

/** Powiązania pochodzą z istniejącej publicznej strony serii, nie z fikcyjnego API. */
@Composable
internal fun RelatedSeriesSection(
    titleId: Int,
    userId: Int?,
    offlineMode: Boolean,
    onRelatedClick: (SeasonAnime) -> Unit,
    prefetched: List<RelatedSeries>? = null
) {
    var fetchedRelations by remember(titleId) { mutableStateOf<List<RelatedSeries>>(emptyList()) }
    var loading by remember(titleId) { mutableStateOf(false) }
    var failed by remember(titleId) { mutableStateOf(false) }
    var retry by remember(titleId) { mutableIntStateOf(0) }
    val relations = if (!prefetched.isNullOrEmpty()) prefetched else fetchedRelations
    LaunchedEffect(titleId, offlineMode, retry, prefetched) {
        if (offlineMode || !prefetched.isNullOrEmpty()) return@LaunchedEffect
        loading = true
        failed = false
        try { fetchedRelations = SeasonSeriesApi.fetchRelatedSeries(titleId) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        finally { loading = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Powiązane serie", style = MaterialTheme.typography.titleMedium)
        when {
            offlineMode -> Text("Powiązania są pobierane online ze strony Shindena.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            loading && relations.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth())
            failed -> {
                Text("Nie udało się pobrać powiązanych serii.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { retry++ }) { Text("Ponów") }
            }
            relations.isEmpty() -> Text("Brak rozpoznanych powiązań w danych tej serii.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(relations, key = { it.series.id }) { related ->
                    PocketPoster(title = related.series.title, coverUrl = related.series.coverUrl,
                        libraryAnime = null, userId = userId,
                        relationLabel = related.relation, style = PocketCardStyle.POSTER,
                        modifier = Modifier.width(105.dp),
                        onClick = { onRelatedClick(related.series) })
                }
            }
        }
    }
}
