package com.example.shindenpocket.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/** Dane sezonu są odczytywane z publicznej strony Shinden, bez niepotwierdzonych endpointów API. */
data class SeasonAnime(
    val id: Int,
    val title: String,
    val detailUrl: String,
    val coverUrl: String?,
    /** Liczba odcinków z karty sezonu; `null`, gdy Shinden jej nie podaje. */
    val episodes: Int? = null
)

data class SavedSeason(val anime: List<SeasonAnime>, val savedAt: Long)

data class SeasonPeriod(val year: Int, val slug: String)

data class SeasonPage(val anime: List<SeasonAnime>, val hasNextPage: Boolean)

object CurrentSeasonApi {
    private const val URL_CURRENT = "https://shinden.pl/series/season/current"
    private const val CACHE = "shinden_season_catalog_v1"
    private val SEASONS = listOf("winter", "spring", "summer", "autumn")
    // Endpoint `current` przekierowuje na adres zawierający rok i porę sezonu.
    private val seasonPath = Regex("^/series/season(?:/[a-z0-9-]+)+/?$")
    private val episodesPattern = Regex("([0-9]+)\\s*odc")

    suspend fun fetch(): List<SeasonAnime> = withContext(Dispatchers.IO) {
        fetchCurrentPage().anime
    }

    suspend fun fetchCurrentPage(): SeasonPage = withContext(Dispatchers.IO) {
        parsePage(downloadHtml(), 1).also {
            if (it.anime.isEmpty()) {
                throw IOException("Nie rozpoznano anime na stronie aktualnego sezonu.")
            }
        }
    }

    suspend fun fetchPage(period: SeasonPeriod, page: Int): SeasonPage = withContext(Dispatchers.IO) {
        require(period.year in 1990..2100 && period.slug in SEASONS && page > 0)
        val suffix = if (page == 1) "" else "?page=$page"
        val html = downloadHtml("https://shinden.pl/series/season/${period.year}/${period.slug}$suffix")
        parsePage(html, page).also {
            // Pusta lista może oznaczać stronę błędu lub nierozpoznany HTML, więc nie trafia do cache.
            if (it.anime.isEmpty()) throw IOException("Nie rozpoznano anime na stronie sezonu.")
        }
    }

    /** Sezon archiwalny pobierany na żądanie; bez cache. */
    suspend fun fetch(period: SeasonPeriod): List<SeasonAnime> = withContext(Dispatchers.IO) {
        fetchPage(period, 1).anime
    }

    /** Ostatnie sezony łącznie z aktualnym, wyliczone z kalendarza urządzenia. */
    fun recentPeriods(count: Int = 8): List<SeasonPeriod> {
        val calendar = Calendar.getInstance()
        var year = calendar.get(Calendar.YEAR)
        var index = calendar.get(Calendar.MONTH) / 3
        return buildList {
            repeat(count) {
                add(SeasonPeriod(year, SEASONS[index]))
                if (index == 0) {
                    index = SEASONS.lastIndex
                    year--
                } else {
                    index--
                }
            }
        }
    }

    /** Parser obsługuje karty `li.sh-tagi-wynik` oraz awaryjnie układ `.title.anime`. */
    internal fun parseHtml(html: String): List<SeasonAnime> {
        return parsePage(html, 1).anime
    }

    internal fun parsePage(html: String, page: Int): SeasonPage {
        val doc = Jsoup.parse(html, URL_CURRENT)
        val byId = linkedMapOf<Int, SeasonAnime>()
        for (card in doc.select("li.sh-tagi-wynik")) addCard(card, byId)
        if (byId.isEmpty()) {
            for (card in doc.select(".title.anime")) addCard(card, byId)
        }
        val nextPage = page + 1
        val hasNextPage = doc.select("a[href]").any {
            Regex("(?:[?&])page=([0-9]+)").find(it.attr("href"))
                ?.groupValues?.getOrNull(1)?.toIntOrNull() == nextPage
        }
        return SeasonPage(byId.values.toList(), hasNextPage)
    }

    private fun addCard(card: Element, byId: MutableMap<Int, SeasonAnime>) {
        // Akceptujemy wyłącznie link do serii, z pominięciem obrazów i adresów `/episodes`.
        val titleLink = card.select("a[href]").firstOrNull { link ->
            val path = normalizePath(link.attr("href")) ?: return@firstOrNull false
            ShindenUrls.titleLink.matches(path)
        } ?: return
        val path = normalizePath(titleLink.attr("href")) ?: return
        val id = ShindenUrls.titleLink.matchEntire(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return
        if (byId.containsKey(id)) return
        // Gdy cała karta jest linkiem, tytuł pobieramy z `.sh-tagi-nazwa`.
        val title = card.selectFirst(".sh-tagi-nazwa")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: titleLink.text().trim().takeIf { it.isNotEmpty() }
            ?: return
        val image = card.selectFirst(".sh-okl img") ?: card.selectFirst("img")
        val rawCover = image?.attr("src")?.takeIf { it.isNotBlank() }
            ?: image?.attr("data-src")
        val cover = rawCover?.let(::normalizeCoverUrl)
        val episodes = card.selectFirst("[data-episodes]")?.attr("data-episodes")
            ?.toIntOrNull()?.takeIf { it > 0 }
            ?: episodesPattern.find(card.text())?.groupValues?.getOrNull(1)
                ?.toIntOrNull()?.takeIf { it > 0 }
        byId[id] = SeasonAnime(
            id = id,
            title = title,
            detailUrl = "https://shinden.pl$path",
            coverUrl = cover,
            episodes = episodes
        )
    }

    private fun normalizePath(raw: String): String? = try {
        val url = URL(URL(URL_CURRENT), raw.trim())
        if (url.protocol == "https" && url.host.equals("shinden.pl", true) &&
            url.port == -1 && url.query == null && url.ref == null
        ) url.path else null
    } catch (_: Exception) { null }

    private fun normalizeCoverUrl(raw: String): String? = try {
        val url = URL(URL(URL_CURRENT), raw.trim())
        // Parametr wersji `?v=...` jest dozwolony w adresach okładek.
        if (url.protocol == "https" && url.host.equals("shinden.pl", true) &&
            url.port == -1 && url.ref == null && url.path.startsWith("/res/images/")
        ) url.toString() else null
    } catch (_: Exception) { null }

    /** Sprawdza, czy adres prowadzi do dozwolonej strony sezonu. */
    internal fun isSeasonPath(path: String): Boolean = seasonPath.matches(path)

    private fun downloadHtml(startUrl: String = URL_CURRENT): String {
        var current = URL(startUrl)
        repeat(5) {
            // WAF może dodać `?r307=1`; pozostałe parametry są odrzucane.
            
            if (current.protocol != "https" || current.host != "shinden.pl" ||
                current.port != -1 || current.ref != null ||
                !isSeasonPath(current.path)
            ) throw IOException("Nieoczekiwany adres sezonu Shinden")

            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                connection.setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9")
                connection.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                )
                when (val code = connection.responseCode) {
                    200 -> return connection.inputStream.bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Przekierowanie bez adresu")
                        current = URL(current, location)
                    }
                    else -> throw IOException("Nie można pobrać sezonu (HTTP $code)")
                }
            } finally { connection.disconnect() }
        }
        throw IOException("Zbyt wiele przekierowań strony sezonu")
    }

    fun loadCached(context: Context): SavedSeason? {
        return try {
            val prefs = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
            val stored = prefs.getString("items", null) ?: return null
            val array = JSONArray(stored)
            val result = buildList {
                for (i in 0 until array.length()) {
                    val row = array.getJSONObject(i)
                    val id = row.optInt("id", 0)
                    val title = row.optString("title").trim()
                    val url = row.optString("url")
                    if (id > 0 && title.isNotEmpty() &&
                        ShindenUrls.isTitleDetailUrl(url, id)
                    ) add(SeasonAnime(
                        id, title, url,
                        row.optString("cover").ifBlank { null },
                        row.optInt("episodes", 0).takeIf { it > 0 }
                    ))
                }
            }
            if (result.isEmpty()) null else SavedSeason(result, prefs.getLong("saved", 0L))
        } catch (_: Exception) { null }
    }

    fun saveCached(context: Context, anime: List<SeasonAnime>) {
        if (anime.isEmpty()) return
        val json = JSONArray()
        anime.forEach {
            json.put(JSONObject().put("id", it.id).put("title", it.title)
                .put("url", it.detailUrl).put("cover", it.coverUrl ?: "")
                .put("episodes", it.episodes ?: 0))
        }
        context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit()
            .putString("items", json.toString())
            .putLong("saved", System.currentTimeMillis()).apply()
    }
}
