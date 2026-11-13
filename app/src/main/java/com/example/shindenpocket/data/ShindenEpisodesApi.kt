package com.example.shindenpocket.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Łączy katalog odcinków z JSON ze statusem wersji online z HTML; obecność w JSON nie potwierdza premiery. */
data class TrackedEpisode(
    val episodeId: Int,
    val number: String,
    val title: String,
    val watched: Boolean,
    val hasOnlineVersion: Boolean?, // `null` oznacza stan nieustalony.
    val airDate: String?,
    val languageLabels: List<String>,
    // Adres szczegółów pochodzi ze zweryfikowanej listy HTML Shinden.
    val detailUrl: String? = null
)

data class TrackedEpisodesResult(
    val items: List<TrackedEpisode>,
    val htmlTitle: String,
    val htmlChecks: Int,
    val htmlTimes: Int,
    val htmlUnknowns: Int
) {
    val withOnlineVersion: Int get() = items.count { it.hasOnlineVersion == true }
    val watched: Int get() = items.count { it.watched }
    val availableUnwatched: Int get() = items.count { it.hasOnlineVersion == true && !it.watched }
}

object ShindenEpisodesApi {

    /**
     * Pobiera katalog przez `lista.shinden.pl` i statusy ze strony `/titles/{id}/episodes`.
     * Przekierowanie do adresu ze slugiem jest dozwolone wyłącznie w domenie Shinden.
     */
    suspend fun fetchTrackedEpisodes(titleId: Int, userId: Int): TrackedEpisodesResult =
        withContext(Dispatchers.IO) {
            require(titleId > 0 && userId > 0) { "Nieprawidlowe ID anime lub uzytkownika" }

            val json = downloadText(
                URL("https://lista.shinden.pl/api/title-episodes/$titleId/$userId"),
                accept = "application/json",
                expectedHost = "lista.shinden.pl"
            )
            val catalog = parseCatalog(json)

            val firstHtml = downloadText(
                URL(ShindenEpisodeListParser.listUrl(titleId, 1)),
                accept = "text/html",
                expectedHost = "shinden.pl",
                episodesTitleId = titleId
            )
            val firstPage = parseOnlineTable(firstHtml, titleId = titleId, userId = userId)
            val statusById = linkedMapOf<Int, HtmlEpisode>().apply {
                putAll(firstPage.episodes)
            }
            // Lista jest stronicowana, dlatego pobieramy wszystkie strony po 100 pozycji.
            
            val maxPage = ShindenEpisodeListParser.maxPage(firstHtml)
            for (page in 2..maxPage) {
                val html = downloadText(
                    URL(ShindenEpisodeListParser.listUrl(titleId, page)),
                    accept = "text/html",
                    expectedHost = "shinden.pl",
                    episodesTitleId = titleId
                )
                val parsed = parseOnlineTable(html, titleId = titleId, userId = userId)
                for ((episodeId, episode) in parsed.episodes) {
                    if (statusById.put(episodeId, episode) != null) {
                        throw IOException("Powtorzone ID odcinka w HTML")
                    }
                }
            }
            if (catalog.isNotEmpty() && statusById.isNotEmpty() &&
                catalog.none { it.episodeId in statusById }
            ) {
                throw IOException("Inne identyfikatory odcinkow w JSON i HTML")
            }

            TrackedEpisodesResult(catalog.map { ep ->
                val htmlEpisode = statusById[ep.episodeId]
                TrackedEpisode(
                    episodeId = ep.episodeId,
                    number = ep.number,
                    title = ep.title,
                    watched = ep.watched,
                    hasOnlineVersion = htmlEpisode?.online,
                    airDate = htmlEpisode?.airDate,
                    languageLabels = htmlEpisode?.languages ?: emptyList(),
                    detailUrl = htmlEpisode?.detailUrl
                )
            }, htmlTitle = firstPage.title,
                htmlChecks = statusById.values.count { it.online == true },
                htmlTimes = statusById.values.count { it.online == false },
                htmlUnknowns = statusById.values.count { it.online == null })
        }

    private fun downloadText(
        startUrl: URL,
        accept: String,
        expectedHost: String,
        episodesTitleId: Int? = null
    ): String {
        var url = startUrl
        repeat(5) {
            // Zalogowana sesja nie może zostać przekierowana poza domenę Shinden.
            if (url.protocol != "https" || url.host != expectedHost || url.port != -1) {
                throw IOException("Niebezpieczny adres przekierowania")
            }
            if (episodesTitleId != null) {
                if (!ShindenUrls.episodesPath(episodesTitleId).matches(url.path) || url.ref != null) {
                    throw IOException("Nieoczekiwany adres strony odcinkow")
                }
                if (!isAllowedEpisodesQuery(url.query)) {
                    throw IOException("Nieoczekiwane parametry strony odcinkow")
                }
            }

            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("Accept", accept)
                connection.setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7")
                // Nagłówek User-Agent wymusza wariant HTML przeznaczony dla przeglądarki.
                // Żądanie nadal używa cookies sesji aplikacji.
                if (episodesTitleId != null) {
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                    connection.setRequestProperty("Referer", ShindenUrls.titleUrl(episodesTitleId))
                }
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("Pragma", "no-cache")

                when (val status = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        return connection.inputStream.bufferedReader(Charsets.UTF_8)
                            .use { reader -> reader.readText() }
                    }
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Przekierowanie bez adresu Location")
                        url = URL(url, location)
                    }
                    else -> throw IOException("${url.host}: HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Zbyt wiele przekierowan podczas pobierania odcinkow")
    }

    private fun isAllowedEpisodesQuery(query: String?): Boolean {
        if (query == null) return true
        return query.split('&').all { part ->
            val key = part.substringBefore('=')
            key == "strona" || key == "na_stronie" || key == "r307"
        }
    }

    private data class CatalogEpisode(
        val episodeId: Int,
        val number: String,
        val title: String,
        val watched: Boolean
    )

    private fun parseCatalog(jsonText: String): List<CatalogEpisode> {
        val json = JSONObject(jsonText)
        if (!json.optBoolean("success")) throw IOException("API odcinkow zwrocilo success=false")
        val items = json.optJSONObject("result")?.optJSONArray("items")
            ?: throw IOException("Brak result.items w API")
        val episodes = mutableListOf<CatalogEpisode>()
        for (i in 0 until items.length()) {
            val ep = items.getJSONObject(i)
            val episodeId = ep.optInt("episodeId", -1)
            val episodeNo = ep.opt("episodeNo")?.toString()?.trim().orEmpty()
            if (episodeId <= 0 || episodeNo.isEmpty() || episodeNo == "null") {
                throw IOException("Brak ID lub numeru odcinka w JSON")
            }
            fun getTitle(name: String) = ep.optJSONObject(name)
                ?.optString("title", "")?.trim().orEmpty()
            val title = sequenceOf("titlePL", "titleEN", "titleOfficial")
                .map(::getTitle).firstOrNull { it.isNotBlank() }.orEmpty()
            episodes.add(
                CatalogEpisode(
                    episodeId = episodeId,
                    number = episodeNo,
                    title = title,
                    watched = ep.optJSONObject("watched") != null
                )
            )
        }
        if (episodes.map { it.episodeId }.toSet().size != episodes.size) {
            throw IOException("Powtarzajace sie ID w katalogu odcinkow")
        }
        return episodes
    }

    private data class HtmlEpisode(
        val online: Boolean?,
        val airDate: String?,
        val languages: List<String>,
        val detailUrl: String?
    )

    private data class HtmlPage(
        val title: String,
        val episodes: Map<Int, HtmlEpisode>
    )

    private fun parseOnlineTable(html: String, titleId: Int, userId: Int): HtmlPage {
        val doc = Jsoup.parse(html)
        // Deklarowane konto musi odpowiadać bieżącej sesji.
        val htmlUserId = Regex("""_Storage\.userId\s*=\s*(\d+)\s*;""")
            .find(html)?.groupValues?.get(1)?.toIntOrNull()
        if (htmlUserId != null && htmlUserId != userId) {
            throw IOException("HTML nie potwierdza sesji uzytkownika (zaloguj ponownie)")
        }
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href").orEmpty()
        if (canonical.isNotBlank()) {
            val canonicalPath = try { URL(canonical).path } catch (e: Exception) {
                throw IOException("Nieprawidlowy adres canonical strony odcinkow")
            }
            if (!ShindenUrls.episodesPath(titleId).matches(canonicalPath)) {
                throw IOException("HTML dotyczy innego anime niz oczekiwane ID $titleId")
            }
        }
        val title = ShindenEpisodeListParser.pageTitle(html)
        val rows = ShindenEpisodeListParser.parse(html, titleId)
        val result = linkedMapOf<Int, HtmlEpisode>()
        for (row in rows) {
            if (row.episodeId <= 0 || row.number.isBlank()) {
                throw IOException("Nieprawidlowy odcinek w HTML")
            }
            if (result.put(
                    row.episodeId,
                    HtmlEpisode(row.online, row.airDate, row.languages, row.detailUrl)
                ) != null
            ) {
                throw IOException("Powtorzone ID odcinka w HTML")
            }
        }
        return HtmlPage(title, result)
    }
}
