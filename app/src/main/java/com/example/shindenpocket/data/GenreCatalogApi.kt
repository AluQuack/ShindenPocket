package com.example.shindenpocket.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale

/**
 * Wyszukuje gatunki w publicznym katalogu Shinden. Taksonomia pochodzi z
 * `tag-description-pl.js`, a parser wyników obsługuje `li.sh-tagi-wynik` i `li.desc-col`.
 */
data class Genre(
    val id: Int,
    val namePl: String,
    val nameEn: String,
    val slug: String
)

data class CatalogTag(
    val id: Int,
    val name: String,
    val type: String
)

data class GenreAnime(
    val id: Int,
    val title: String,
    val detailUrl: String,
    val tags: List<String>,
    val coverId: Int? = null
)

data class GenrePage(
    val anime: List<GenreAnime>,
    val maxPage: Int,
    val cached: Boolean
)

object GenreCatalogApi {

    private const val BASE = "https://shinden.pl"
    private const val TAG_JS_URL = "https://shinden.pl/f/js/tag-description-pl.js"
    private const val TAG_JS_PATH = "/f/js/tag-description-pl.js"
    private const val CACHE = "shinden_genre_catalog_v1"
    private const val MAX_TAG_CHIPS = 8

    private val coverImagePath = Regex(
        "^/res/images/(?:225x350|280x400|genuine|100x100)/([1-9][0-9]*)\\.(?:jpg|jpeg|webp)$"
    )
    private val pageArg = Regex("page=([1-9][0-9]*)")

    private fun genreUrl(genre: Genre, page: Int): String =
        "$BASE/genre/${genre.id}-${genre.slug}" + if (page > 1) "?page=$page" else ""

    /** Shinden buduje slug z polskiej nazwy; normalizacja NFD wymaga osobnej zamiany `ł`. */
    private fun slugFor(name: String): String =
        Normalizer.normalize(name.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('ł', 'l')
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    
    
    

    suspend fun fetchGenres(): List<Genre> = withContext(Dispatchers.IO) {
        val source = downloadText(
            URL(TAG_JS_URL), TAG_JS_PATH, null,
            "Nie można pobrać listy gatunków"
        )
        val taxonomy = JSONObject(extractUsedTagsJson(source))
        val result = taxonomy.keys().asSequence()
            .mapNotNull { key ->
                if (!key.matches(Regex("a[1-9][0-9]*"))) return@mapNotNull null
                val row = taxonomy.optJSONObject(key) ?: return@mapNotNull null
                if (row.optString("tag_type") != "genre") return@mapNotNull null
                val namePl = row.optString("name").trim()
                val nameEn = row.optString("tag_name").trim()
                val slug = when {
                    namePl.isNotEmpty() -> slugFor(namePl)
                    nameEn.isNotEmpty() -> slugFor(nameEn)
                    else -> return@mapNotNull null
                }
                if (namePl.isEmpty() || slug.isEmpty()) return@mapNotNull null
                Genre(key.drop(1).toInt(), namePl, nameEn, slug)
            }
            .sortedBy { it.id }
            .toList()
        if (result.isEmpty()) {
            throw IOException("Nie rozpoznano ani jednego gatunku w taksonomii Shindena.")
        }
        result
    }

    suspend fun fetchTags(): List<CatalogTag> = withContext(Dispatchers.IO) {
        val source = downloadText(
            URL(TAG_JS_URL), TAG_JS_PATH, null,
            "Nie można pobrać listy tagów"
        )
        parseTags(source).also {
            if (it.isEmpty()) throw IOException("Nie rozpoznano tagów w taksonomii Shindena.")
        }
    }

    internal fun parseTags(source: String): List<CatalogTag> {
        val taxonomy = JSONObject(extractUsedTagsJson(source))
        return taxonomy.keys().asSequence().mapNotNull { key ->
            if (!key.matches(Regex("a[1-9][0-9]*"))) return@mapNotNull null
            val row = taxonomy.optJSONObject(key) ?: return@mapNotNull null
            val name = row.optString("name").trim()
            val type = row.optString("tag_type").trim()
            if (name.isEmpty() || type.isEmpty()) null
            else CatalogTag(key.drop(1).toInt(), name, type)
        }.sortedWith(compareBy<CatalogTag> { it.type }.thenBy { it.name }).toList()
    }

    /**
     * Pliki Shindena to `var _UsedTags = {...};` — JSON moze zawierac średniki
     * w opisach, wiec przecinamy po zbalansowanych klamrach z uwzglednieniem
     * ciagow i znakow uciazkowych.
     */
    private fun extractUsedTagsJson(source: String): String {
        val markerIndex = source.indexOf("var _UsedTags")
        if (markerIndex < 0) throw IOException("Brak struktury _UsedTags w pliku gatunków")
        val open = source.indexOf('{', markerIndex + "var _UsedTags".length)
        if (open < 0 || open - (markerIndex + "var _UsedTags".length) > 256) {
            throw IOException("Brak JSON-u przy _UsedTags")
        }
        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until source.length) {
            // Znak po `\` należy do sekwencji ucieczki i nie może zamknąć literału.

            if (escaped) { escaped = false; continue }
            when (val c = source[i]) {
                '"' -> inString = !inString
                '\\' -> { if (inString) escaped = true }
                '{' -> if (!inString) depth++
                '}' -> if (!inString) {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        throw IOException("Niezamknięty JSON _UsedTags")
    }

    
    
    

    suspend fun fetchGenrePage(genre: Genre, page: Int): GenrePage = withContext(Dispatchers.IO) {
        require(page >= 1) { "Page must be >= 1" }
        val expectedPath = "/genre/${genre.id}-${genre.slug}"
        val expectedQuery = if (page > 1) "page=$page" else null
        val base = genreUrl(genre, page)
        val html = downloadText(URL(base), expectedPath, expectedQuery,
            "Nie można pobrać strony gatunku")
        val (anime, maxPage) = parseGenrePage(html, base)
        if (anime.isEmpty()) {
            throw IOException("Nie rozpoznano anime na stronie gatunku.")
        }
        GenrePage(anime, maxPage, false)
    }

    /** Parsuje karty `li.sh-tagi-wynik` oraz zgodnościowo `li.desc-col`. */
    internal fun parseGenrePage(html: String, base: String): Pair<List<GenreAnime>, Int> {
        val doc = Jsoup.parse(html, base)
        val byId = linkedMapOf<Int, GenreAnime>()

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
            val title = (card.selectFirst(".sh-tagi-nazwa")?.text() ?: titleLink.text()).trim()
            if (title.isBlank() || byId.containsKey(id)) continue
            val coverId = coverId(base, card.selectFirst(".sh-okl img"))
            byId[id] = GenreAnime(id, title, "$BASE$path", emptyList(), coverId)
        }

        for (card in doc.select("li.desc-col")) {
            // Pomijamy karty `/manga`, puste pozycje i linki inne niż serie.
            val titleLink = card.select("h3 > a[href]").firstOrNull { link ->
                val path = normalizePath(base, link.attr("href")) ?: return@firstOrNull false
                ShindenUrls.titleLink.matches(path) && link.text().trim().isNotEmpty()
            } ?: continue
            val path = normalizePath(base, titleLink.attr("href")) ?: continue
            val id = ShindenUrls.titleLink.matchEntire(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue
            val title = titleLink.text().trim()
            if (title.isBlank() || byId.containsKey(id)) continue
            val tags = card.select("ul.tags a[href^='/genre/']")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(MAX_TAG_CHIPS)
            // W układzie `div-row` okładka znajduje się w sąsiednim `li.cover-col`.
            val coverId = card.parent()
                ?.selectFirst("li.cover-col a[href]")
                ?.let { anchor ->
                    val coverPath = normalizePath(base, anchor.attr("href")) ?: return@let null
                    coverImagePath.matchEntire(coverPath)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }
            byId[id] = GenreAnime(id, title, "$BASE$path", tags, coverId)
        }
        // Maksymalny numer strony odczytujemy z linków paginacji `?page=N`.
        var maxPage = 1
        for (link in doc.select("a[href*='page=']")) {
            pageArg.findAll(link.attr("href")).forEach { m ->
                val n = m.groupValues[1].toIntOrNull() ?: return@forEach
                if (n > maxPage) maxPage = n
            }
        }
        return byId.values.toList() to maxPage.coerceAtMost(1000)
    }

    private fun normalizePath(base: String, raw: String): String? = try {
        val url = URL(URL(base), raw.trim())
        if (url.protocol == "https" && url.host.equals("shinden.pl", true) &&
            url.port == -1 && url.query == null && url.ref == null
        ) url.path else null
    } catch (_: Exception) { null }

    private fun coverId(base: String, image: org.jsoup.nodes.Element?): Int? {
        val raw = sequenceOf(image?.attr("data-src"), image?.attr("src"))
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull() ?: return null
        val path = try { URL(URL(base), raw).path } catch (_: Exception) { return null }
        return coverImagePath.matchEntire(path)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    
    
    

    private fun downloadText(
        url: URL,
        expectedPath: String,
        expectedQuery: String?,
        errorMessage: String
    ): String {
        var current = url
        repeat(5) {
            // WAF może dodać `r307=1`; pozostałe parametry muszą odpowiadać żądaniu.
            val normalizedQuery = current.query?.split('&')
                ?.filterNot { it.substringBefore('=') == "r307" }
                ?.joinToString("&")
                ?.takeIf { it.isNotEmpty() }
            if (current.protocol != "https" || !current.host.equals("shinden.pl", true) ||
                current.port != -1 || current.ref != null ||
                current.path != expectedPath ||
                normalizedQuery != expectedQuery
            ) throw IOException("Nieoczekiwany adres Shinden")

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
                    else -> throw IOException("$errorMessage (HTTP $code)")
                }
            } finally { connection.disconnect() }
        }
        throw IOException("Zbyt wiele przekierowań strony Shinden")
    }

    
    
    

    fun loadCachedGenres(context: Context): List<Genre>? {
        return try {
            val stored = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
                .getString("genres", null) ?: return null
            val array = JSONArray(stored)
            val result = buildList {
                for (i in 0 until array.length()) {
                    val row = array.getJSONObject(i)
                    val id = row.optInt("id", 0)
                    val pl = row.optString("pl").trim()
                    // Nieprawidłowy slug w cache jest wyliczany ponownie z polskiej nazwy.
                    val slug = slugFor(pl).ifBlank { row.optString("slug").trim() }
                    if (id > 0 && pl.isNotEmpty() && slug.isNotEmpty()) {
                        add(Genre(id, pl, row.optString("en").trim(), slug))
                    }
                }
            }
            if (result.isEmpty()) null else result
        } catch (_: Exception) { null }
    }

    fun saveGenres(context: Context, genres: List<Genre>) {
        if (genres.isEmpty()) return
        val array = JSONArray()
        genres.forEach {
            array.put(JSONObject().put("id", it.id)
                .put("pl", it.namePl).put("en", it.nameEn).put("slug", it.slug))
        }
        try {
            context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit()
                .putString("genres", array.toString()).apply()
        } catch (_: Exception) { /* Błąd cache nie blokuje danych sieciowych. */ }
    }

    fun loadCachedTags(context: Context): List<CatalogTag> = try {
        val raw = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
            .getString("tags", null) ?: return emptyList()
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val id = row.optInt("id")
                val name = row.optString("name").trim()
                val type = row.optString("type").trim()
                if (id > 0 && name.isNotEmpty() && type.isNotEmpty()) {
                    add(CatalogTag(id, name, type))
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun saveTags(context: Context, tags: List<CatalogTag>) {
        if (tags.isEmpty()) return
        val array = JSONArray()
        tags.forEach {
            array.put(JSONObject().put("id", it.id).put("name", it.name).put("type", it.type))
        }
        context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit()
            .putString("tags", array.toString()).apply()
    }

    fun loadCachedPage(context: Context, genre: Genre, page: Int): GenrePage? {
        return try {
            val stored = context.getSharedPreferences(CACHE, Context.MODE_PRIVATE)
                .getString("g${genre.id}_p$page", null) ?: return null
            val record = JSONObject(stored)
            val maxPage = record.optInt("maxPage", 1).coerceIn(1, 1000)
            val array = record.optJSONArray("items") ?: return null
            val anime = buildList {
                for (i in 0 until array.length()) {
                    val row = array.getJSONObject(i)
                    val id = row.optInt("id", 0)
                    val title = row.optString("title").trim()
                    val url = row.optString("url")
                    if (id > 0 && title.isNotEmpty() &&
                        ShindenUrls.isTitleDetailUrl(url, id)
                    ) {
                        val tags = buildList {
                            row.optJSONArray("tags")?.let { tagsArray ->
                                for (j in 0 until tagsArray.length()) {
                                    val tag = tagsArray.optString(j).trim()
                                    if (tag.isNotEmpty()) add(tag)
                                }
                            }
                        }.take(MAX_TAG_CHIPS)
                        val coverId = row.optInt("cover", 0).takeIf { it > 0 }
                        add(GenreAnime(id, title, url, tags, coverId))
                    }
                }
            }
            if (anime.isEmpty()) null
            else GenrePage(anime, maxPage, true)
        } catch (_: Exception) { null }
    }

    fun savePage(context: Context, genre: Genre, page: Int, result: GenrePage) {
        if (result.anime.isEmpty()) return
        val items = JSONArray()
        result.anime.forEach {
            items.put(JSONObject().put("id", it.id).put("title", it.title)
                .put("url", it.detailUrl)
                .put("tags", JSONArray(it.tags))
                .put("cover", it.coverId ?: 0))
        }
        try {
            context.getSharedPreferences(CACHE, Context.MODE_PRIVATE).edit()
                .putString("g${genre.id}_p$page",
                    JSONObject().put("maxPage", result.maxPage).put("items", items).toString())
                .apply()
        } catch (_: Exception) { /* Błąd cache nie blokuje danych sieciowych. */ }
    }
}
