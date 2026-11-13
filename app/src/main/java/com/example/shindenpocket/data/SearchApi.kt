package com.example.shindenpocket.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/** Wyszukuje publiczny katalog Shinden z obsługą paginacji, sortowania i filtrów strony `/series`. */
enum class SearchSort(val label: String, val value: String) {
    POPULARITY("Popularność", ""),
    RATING("Ocena", "ocena"),
    RANKING("Ranking", "ranking"),
    FAVORITES("Ulubione", "ulubione"),
    NEWEST("Najnowsze", "nowe"),
    OLDEST("Najstarsze", "stare"),
    NAME("Tytuł A–Z", "az"),
    EPISODES("Odcinki", "odcinki"),
    RANDOM("Losowo", "losowo")
}

enum class SearchType(val label: String, val value: String) {
    TV("TV", "TV"),
    ONA("ONA", "ONA"),
    OVA("OVA", "OVA"),
    MOVIE("Film", "Movie"),
    SPECIAL("Specjalny", "Special"),
    MUSIC("Muzyka", "Music")
}

enum class SearchStatus(val label: String, val value: String) {
    AIRING("Emitowane", "Currently Airing"),
    FINISHED("Zakończone", "Finished Airing"),
    UPCOMING("Zapowiedziane", "Not yet aired"),
    PROPOSAL("Deklaracja", "Proposal")
}

enum class SearchSeason(val label: String, val value: String) {
    WINTER("Zima", "winter"),
    SPRING("Wiosna", "spring"),
    SUMMER("Lato", "summer"),
    AUTUMN("Jesień", "autumn")
}

enum class SearchTagMode(val prefix: String) {
    INCLUDE("i"),
    ONLY("o"),
    EXCLUDE("e")
}

data class SearchTagFilter(
    val id: Int,
    val name: String,
    val mode: SearchTagMode = SearchTagMode.INCLUDE
)

data class SubtitleGroup(val id: Int, val name: String)

data class SearchFilters(
    val sort: SearchSort = SearchSort.POPULARITY,
    val tags: List<SearchTagFilter> = emptyList(),
    val types: Set<SearchType> = emptySet(),
    val statuses: Set<SearchStatus> = emptySet(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val exactYear: Int? = null,
    val season: SearchSeason? = null,
    val subtitleGroupId: Int? = null,
    val subtitleGroupName: String? = null,
    val onlineOnly: Boolean = false,
    val ratingFrom: Int? = null,
    val ratingTo: Int? = null,
    val episodesFrom: Int? = null,
    val episodesTo: Int? = null,
    val lengthFrom: Int? = null,
    val lengthTo: Int? = null
) {
    /** Liczba aktywnych grup filtrów, bez sortowania. */
    val activeCount: Int
        get() = listOf(
            tags.isNotEmpty(),
            types.isNotEmpty(),
            statuses.isNotEmpty(),
            yearFrom != null || yearTo != null,
            exactYear != null || season != null,
            subtitleGroupId != null,
            onlineOnly,
            ratingFrom != null || ratingTo != null,
            episodesFrom != null || episodesTo != null,
            lengthFrom != null || lengthTo != null
        ).count { it }

    /** Stabilny klucz cache niezależny od kolejności elementów w zbiorach. */
    val signature: String
        get() = buildString {
            append(sort.name).append('|')
            append(tags.sortedBy { it.id }.joinToString(",") { "${it.mode.prefix}${it.id}" }).append('|')
            append(types.map { it.value }.sorted().joinToString(",")).append('|')
            append(statuses.map { it.value }.sorted().joinToString(",")).append('|')
            append(yearFrom).append('-').append(yearTo).append('|')
            append(exactYear).append('-').append(season?.value).append('|')
            append(subtitleGroupId).append('|').append(onlineOnly).append('|')
            append(ratingFrom).append('-').append(ratingTo).append('|')
            append(episodesFrom).append('-').append(episodesTo).append('|')
            append(lengthFrom).append('-').append(lengthTo)
        }
}

data class SearchAnime(
    val id: Int,
    val title: String,
    val detailUrl: String,
    val coverId: Int?,
    val tags: List<String>,
    val kind: String?,
    val episodes: Int?,
    val rating: Float?,
    val status: String?
)

data class SearchResult(
    val anime: List<SearchAnime>,
    val page: Int,
    val maxPage: Int,
    val cached: Boolean
)

object SearchApi {
    private const val BASE = "https://shinden.pl"
    private const val HOST = "shinden.pl"
    private const val PATH = "/series"
    private const val CACHE = "shinden_search_pages_v1"
    private const val SETTINGS = "shinden_search_settings_v1"
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    private val coverPath = Regex(
        "^/res/images/(?:225x350|280x400|genuine|100x100)/([1-9][0-9]*)\\.(?:jpg|jpeg|webp)$"
    )
    private val pagePattern = Regex("[?&]page=([1-9][0-9]*)")
    private val numberPattern = Regex("([0-9]+[.,][0-9]+)")
    private val episodesPattern = Regex("^\\s*([0-9]+)")

    fun buildUrl(query: String, filters: SearchFilters, page: Int): String {
        require(page >= 1) { "Page must be >= 1" }
        val params = mutableListOf<String>()
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) {
            params += "search=" + enc(trimmed)
        }
        if (page > 1) params += "page=$page"
        if (filters.sort.value.isNotEmpty()) params += "sort=" + filters.sort.value
        filters.tags.filter { it.id > 0 }.distinctBy { it.id }.take(40).takeIf { it.isNotEmpty() }
            ?.let { tags -> params += "genres=" + enc(tags.joinToString(";") { "${it.mode.prefix}${it.id}" }) }
        filters.types.sortedBy { it.ordinal }.forEach { params += "series_type%5B%5D=" + enc(it.value) }
        filters.statuses.sortedBy { it.ordinal }.forEach { params += "series_status%5B%5D=" + enc(it.value) }
        filters.yearFrom?.takeIf { it in 1917..2100 }?.let { params += "year_from=$it" }
        filters.yearTo?.takeIf { it in 1917..2100 }?.let { params += "year_to=$it" }
        filters.exactYear?.takeIf { it in 1917..2100 }?.let { params += "year=$it" }
        filters.season?.let { params += "season=${it.value}" }
        filters.subtitleGroupId?.takeIf { it > 0 }?.let { params += "grupa=$it" }
        if (filters.onlineOnly) params += "one_online=true"
        filters.ratingFrom?.takeIf { it in 1..10 }?.let { params += "rating_from=$it" }
        filters.ratingTo?.takeIf { it in 1..10 }?.let { params += "rating_to=$it" }
        filters.episodesFrom?.takeIf { it >= 0 }?.let { params += "series_number_from=$it" }
        filters.episodesTo?.takeIf { it >= 0 }?.let { params += "series_number_to=$it" }
        filters.lengthFrom?.takeIf { it in 1..300 }?.let { params += "length_from=$it" }
        filters.lengthTo?.takeIf { it in 1..300 }?.let { params += "length_to=$it" }
        return "$BASE$PATH" + if (params.isEmpty()) "" else "?" + params.joinToString("&")
    }

    internal fun validate(filters: SearchFilters): String? = when {
        listOfNotNull(filters.yearFrom, filters.yearTo, filters.exactYear).any { it !in 1917..2100 } ->
            "Rok musi mieścić się w zakresie 1917–2100."
        filters.yearFrom != null && filters.yearTo != null && filters.yearFrom > filters.yearTo ->
            "Rok początkowy nie może być późniejszy niż końcowy."
        filters.season != null && filters.exactYear == null ->
            "Wybierz rok dla wybranego sezonu."
        listOfNotNull(filters.ratingFrom, filters.ratingTo).any { it !in 1..10 } ->
            "Ocena musi mieścić się w zakresie 1–10."
        filters.ratingFrom != null && filters.ratingTo != null && filters.ratingFrom > filters.ratingTo ->
            "Minimalna ocena nie może być wyższa niż maksymalna."
        listOfNotNull(filters.episodesFrom, filters.episodesTo).any { it < 0 } ->
            "Liczba odcinków nie może być ujemna."
        filters.episodesFrom != null && filters.episodesTo != null &&
            filters.episodesFrom > filters.episodesTo ->
            "Minimalna liczba odcinków nie może być wyższa niż maksymalna."
        listOfNotNull(filters.lengthFrom, filters.lengthTo).any { it !in 1..300 } ->
            "Długość odcinka musi mieścić się w zakresie 1–300 minut."
        filters.lengthFrom != null && filters.lengthTo != null && filters.lengthFrom > filters.lengthTo ->
            "Minimalna długość nie może być wyższa niż maksymalna."
        else -> null
    }

    /** Offline zwraca cache; online preferuje sieć i używa cache po błędzie. */
    suspend fun search(
        context: Context,
        query: String,
        filters: SearchFilters,
        page: Int,
        offlineMode: Boolean
    ): SearchResult {
        val key = cacheKey(query, filters, page)
        if (offlineMode) {
            return loadCached(context, key, page)
                ?: throw IOException("Brak zapisanych wyników w trybie offline.")
        }
        return try {
            val result = fetch(query, filters, page)
            withContext(Dispatchers.IO) { savePage(context, key, result) }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadCached(context, key, page)?.copy(cached = true) ?: throw e
        }
    }

    private suspend fun fetch(query: String, filters: SearchFilters, page: Int): SearchResult =
        withContext(Dispatchers.IO) {
            val html = downloadText(buildUrl(query, filters, page))
            val (anime, maxPage) = parseSearchPage(html, BASE)
            SearchResult(anime, page, maxPage, cached = false)
        }

    suspend fun fetchSubtitleGroups(): List<SubtitleGroup> = withContext(Dispatchers.IO) {
        parseSubtitleGroups(downloadText("$BASE$PATH"))
    }

    internal fun parseSubtitleGroups(html: String): List<SubtitleGroup> =
        Jsoup.parse(html, BASE).select("select[name=grupa] option").mapNotNull { option ->
            val id = option.attr("value").trim().toIntOrNull() ?: return@mapNotNull null
            val name = option.text().trim()
            if (id > 0 && name.isNotEmpty()) SubtitleGroup(id, name) else null
        }.distinctBy { it.id }

    internal fun parseSearchPage(html: String, base: String): Pair<List<SearchAnime>, Int> {
        val doc = Jsoup.parse(html, base)
        val byId = linkedMapOf<Int, SearchAnime>()

        // Siatka wyników używa adresów `/titles/{id}-slug` lub `/series/{id}-slug`.
        for (card in doc.select("li.sh-tagi-wynik")) {
            val titleLink = card.select("a[href]").firstOrNull { link ->
                val path = normalizePath(base, link.attr("href")) ?: return@firstOrNull false
                val match = ShindenUrls.titleLink.matchEntire(path) ?: return@firstOrNull false
                match.groupValues[1].toIntOrNull() != null &&
                    (card.selectFirst(".sh-tagi-nazwa") != null || link.text().trim().isNotEmpty())
            } ?: continue
            val path = normalizePath(base, titleLink.attr("href")) ?: continue
            val id = ShindenUrls.titleLink.matchEntire(path)
                ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            val title = (card.selectFirst(".sh-tagi-nazwa")?.text() ?: titleLink.text())
                .trim().replace(Regex("\\s+"), " ")
            if (title.isEmpty() || byId.containsKey(id)) continue

            val coverId = coverId(base, card.selectFirst(".sh-okl img"))
            val kind = card.selectFirst(".sh-tagi-typ")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val episodes = card.selectFirst("[data-episodes]")?.attr("data-episodes")
                ?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            val rating = card.selectFirst(".sh-tagi-meta b")?.text()?.let(::parseRating)

            byId[id] = SearchAnime(
                id = id,
                title = title,
                detailUrl = "$BASE$path",
                coverId = coverId,
                tags = emptyList(),
                kind = kind,
                episodes = episodes,
                rating = rating,
                status = null
            )
        }

        // Parser obsługuje także tabelaryczny układ wyników.
        for (card in doc.select("li.desc-col")) {
            // Pomijamy nagłówek tabeli, karty `/manga` i linki inne niż serie.
            val titleLink = card.select("h3 > a[href]").firstOrNull { link ->
                val path = normalizePath(base, link.attr("href")) ?: return@firstOrNull false
                ShindenUrls.titleLink.matches(path) && link.text().trim().isNotEmpty()
            } ?: continue
            val path = normalizePath(base, titleLink.attr("href")) ?: continue
            val id = ShindenUrls.titleLink.matchEntire(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue
            val title = titleLink.text().trim().replace(Regex("\\s+"), " ")
            if (title.isEmpty() || byId.containsKey(id)) continue

            val row = card.parent()
            val tags = card.select("ul.tags a[href^='/genre/']")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(8)
            val coverId = row?.selectFirst("li.cover-col a[href]")
                ?.attr("href")
                ?.let { normalizePath(base, it) }
                ?.let { coverPath.matchEntire(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            val kind = row?.selectFirst("li.title-kind-col")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val episodes = row?.selectFirst("li.episodes-col")?.text()
                ?.let { episodesPattern.find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            val rating = row?.selectFirst("li.ratings-col .rating-total")?.text()?.let(::parseRating)
            val status = row?.selectFirst("li.title-status-col")?.text()?.trim()?.takeIf { it.isNotEmpty() }

            byId[id] = SearchAnime(
                id = id,
                title = title,
                detailUrl = "$BASE$path",
                coverId = coverId,
                tags = tags,
                kind = kind,
                episodes = episodes,
                rating = rating,
                status = status
            )
        }

        var maxPage = 1
        for (link in doc.select("a[href]")) {
            val href = link.attr("href")
            if (!href.contains("page=")) continue
            val value = pagePattern.find(href)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            if (value > maxPage) maxPage = value
        }
        return byId.values.toList() to maxPage.coerceIn(1, 500)
    }

    fun loadSubtitleGroups(context: Context): List<SubtitleGroup> = try {
        val raw = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            .getString("subtitle_groups", null) ?: return emptyList()
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val id = row.optInt("id")
                val name = row.optString("name").trim()
                if (id > 0 && name.isNotEmpty()) add(SubtitleGroup(id, name))
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun saveSubtitleGroups(context: Context, groups: List<SubtitleGroup>) {
        if (groups.isEmpty()) return
        val array = JSONArray()
        groups.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name)) }
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit()
            .putString("subtitle_groups", array.toString()).apply()
    }

    fun loadSavedFilters(context: Context): SearchFilters? = try {
        val raw = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
            .getString("filters", null) ?: return null
        filtersFromJson(JSONObject(raw))
    } catch (_: Exception) {
        null
    }

    fun saveFilters(context: Context, filters: SearchFilters) {
        context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit()
            .putString("filters", filtersToJson(filters).toString()).apply()
    }

    private fun filtersToJson(filters: SearchFilters) = JSONObject().apply {
        put("sort", filters.sort.name)
        put("tags", JSONArray().apply {
            filters.tags.forEach {
                put(JSONObject().put("id", it.id).put("name", it.name).put("mode", it.mode.name))
            }
        })
        put("types", JSONArray(filters.types.map { it.name }))
        put("statuses", JSONArray(filters.statuses.map { it.name }))
        put("yearFrom", filters.yearFrom ?: JSONObject.NULL)
        put("yearTo", filters.yearTo ?: JSONObject.NULL)
        put("exactYear", filters.exactYear ?: JSONObject.NULL)
        put("season", filters.season?.name ?: "")
        put("subtitleGroupId", filters.subtitleGroupId ?: JSONObject.NULL)
        put("subtitleGroupName", filters.subtitleGroupName ?: "")
        put("onlineOnly", filters.onlineOnly)
        put("ratingFrom", filters.ratingFrom ?: JSONObject.NULL)
        put("ratingTo", filters.ratingTo ?: JSONObject.NULL)
        put("episodesFrom", filters.episodesFrom ?: JSONObject.NULL)
        put("episodesTo", filters.episodesTo ?: JSONObject.NULL)
        put("lengthFrom", filters.lengthFrom ?: JSONObject.NULL)
        put("lengthTo", filters.lengthTo ?: JSONObject.NULL)
    }

    private fun filtersFromJson(json: JSONObject): SearchFilters {
        fun enumSet(arrayName: String, values: Array<out Enum<*>>): Set<String> {
            val array = json.optJSONArray(arrayName) ?: return emptySet()
            return buildSet {
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf { name -> values.any { it.name == name } }?.let(::add)
                }
            }
        }
        val tagArray = json.optJSONArray("tags") ?: JSONArray()
        val tags = buildList {
            for (i in 0 until tagArray.length()) {
                val row = tagArray.optJSONObject(i) ?: continue
                val id = row.optInt("id")
                val name = row.optString("name").trim()
                val mode = runCatching { SearchTagMode.valueOf(row.optString("mode")) }
                    .getOrDefault(SearchTagMode.INCLUDE)
                if (id > 0 && name.isNotEmpty()) add(SearchTagFilter(id, name, mode))
            }
        }
        return SearchFilters(
            sort = runCatching { SearchSort.valueOf(json.optString("sort")) }
                .getOrDefault(SearchSort.POPULARITY),
            tags = tags,
            types = enumSet("types", SearchType.entries.toTypedArray()).map(SearchType::valueOf).toSet(),
            statuses = enumSet("statuses", SearchStatus.entries.toTypedArray())
                .map(SearchStatus::valueOf).toSet(),
            yearFrom = json.optIntOrNull("yearFrom"),
            yearTo = json.optIntOrNull("yearTo"),
            exactYear = json.optIntOrNull("exactYear"),
            season = json.optString("season").takeIf(String::isNotEmpty)
                ?.let { runCatching { SearchSeason.valueOf(it) }.getOrNull() },
            subtitleGroupId = json.optIntOrNull("subtitleGroupId"),
            subtitleGroupName = json.optString("subtitleGroupName").takeIf(String::isNotEmpty),
            onlineOnly = json.optBoolean("onlineOnly"),
            ratingFrom = json.optIntOrNull("ratingFrom"),
            ratingTo = json.optIntOrNull("ratingTo"),
            episodesFrom = json.optIntOrNull("episodesFrom"),
            episodesTo = json.optIntOrNull("episodesTo"),
            lengthFrom = json.optIntOrNull("lengthFrom"),
            lengthTo = json.optIntOrNull("lengthTo")
        )
    }

    

    private fun cacheKey(query: String, filters: SearchFilters, page: Int): String =
        query.trim().lowercase().replace(Regex("\\s+"), " ") + "|" + filters.signature + "|" + page

    private fun savePage(context: Context, key: String, result: SearchResult) {
        try {
            val prefs = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            if (prefs.all.size > 80) editor.clear()
            val items = JSONArray()
            for (anime in result.anime) {
                items.put(JSONObject().apply {
                    put("id", anime.id)
                    put("title", anime.title)
                    put("url", anime.detailUrl)
                    put("cover", anime.coverId ?: 0)
                    put("tags", JSONArray(anime.tags))
                    put("kind", anime.kind ?: "")
                    put("episodes", anime.episodes ?: 0)
                    put("rating", anime.rating?.toDouble() ?: 0.0)
                    put("status", anime.status ?: "")
                })
            }
            val record = JSONObject().apply {
                put("key", key)
                put("maxPage", result.maxPage)
                put("items", items)
            }
            editor.putString(Integer.toHexString(key.hashCode()), record.toString()).apply()
        } catch (_: Exception) {
            // Cache jest opcjonalny.
        }
    }

    private fun loadCached(context: Context, key: String, page: Int): SearchResult? {
        return try {
            val stored = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
                .getString(Integer.toHexString(key.hashCode()), null) ?: return null
            val record = JSONObject(stored)
            if (record.optString("key") != key) return null
            val maxPage = record.optInt("maxPage", 1).coerceIn(1, 500)
            val array = record.optJSONArray("items") ?: return null
        val anime = buildList {
            for (i in 0 until array.length()) {
                val row = array.getJSONObject(i)
                val id = row.optInt("id", 0)
                val title = row.optString("title").trim()
                val url = row.optString("url")
                if (id <= 0 || title.isEmpty() || !ShindenUrls.isTitleDetailUrl(url, id)) continue
                val tags = buildList {
                    val source = row.optJSONArray("tags") ?: JSONArray()
                    for (j in 0 until source.length()) {
                        val value = source.optString(j).trim()
                        if (value.isNotEmpty()) add(value)
                    }
                }
                add(
                    SearchAnime(
                        id = id,
                        title = title,
                        detailUrl = url,
                        coverId = row.optInt("cover", 0).takeIf { it > 0 },
                        tags = tags,
                        kind = row.optString("kind").trim().takeIf { it.isNotEmpty() },
                        episodes = row.optInt("episodes", 0).takeIf { it > 0 },
                        rating = row.optDouble("rating", 0.0).takeIf { it > 0.0 }?.toFloat(),
                        status = row.optString("status").trim().takeIf { it.isNotEmpty() }
                    )
                )
            }
        }
        if (anime.isEmpty()) null else SearchResult(anime, page, maxPage, cached = true)
        } catch (_: Exception) {
            null
        }
    }

    

    private fun downloadText(url: String): String {
        var current = URL(url)
        repeat(5) {
            validate(current)
            val connection = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.8")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Przekierowanie bez adresu (HTTP $code).")
                    current = URL(current, location)
                    return@repeat
                }
                if (code !in 200..299) {
                    throw IOException("Shinden odrzucił żądanie (HTTP $code).")
                }
                return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Zbyt wiele przekierowań.")
    }

    private fun validate(url: URL) {
        if (url.protocol != "https" ||
            !url.host.equals(HOST, ignoreCase = true) ||
            url.port != -1
        ) throw IOException("Nieobsługiwany adres Shindena.")
        if (url.path.trimEnd('/') != PATH) {
            throw IOException("Shinden przekierował na stronę tytułu zamiast listy wyników.")
        }
    }

    private fun normalizePath(base: String, raw: String): String? = try {
        val uri = URI(base).resolve(raw)
        if (uri.scheme == "https" && uri.host == HOST && uri.port == -1 &&
            uri.query == null && uri.fragment == null
        ) uri.path else null
    } catch (_: Exception) {
        null
    }

    private fun coverId(base: String, image: org.jsoup.nodes.Element?): Int? {
        val raw = sequenceOf(image?.attr("data-src"), image?.attr("src"))
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull() ?: return null
        val path = try { URI(base).resolve(raw).path } catch (_: Exception) { return null }
        return coverPath.matchEntire(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseRating(raw: String): Float? {
        val match = numberPattern.find(raw) ?: return null
        return match.groupValues[1].replace(',', '.').toFloatOrNull()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (isNull(name) || !has(name)) null else optInt(name).takeIf { it != 0 }
}
