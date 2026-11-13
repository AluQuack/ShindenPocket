package com.example.shindenpocket.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Aktualizuje pełny zestaw obejrzanych odcinków przez zweryfikowany endpoint.
 * Autoryzacja używa cookies i opcjonalnego `_Storage.basic`; zapis wymaga potwierdzonego stanu strony.
 */
object ShindenWatchedApi {
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    data class WriteResult(val changed: Boolean)

    suspend fun setWatched(
        titleId: Int,
        userId: Int,
        episodeId: Int,
        wasWatched: Boolean,
        wantWatched: Boolean
    ): WriteResult = withContext(Dispatchers.IO) {
        require(titleId > 0 && userId > 0 && episodeId > 0) { "Nieprawidlowe ID" }

        val page = readEpisodesPage(titleId)
        val html = page.html
        val doc = Jsoup.parse(html)
        // Pełny zestaw obejrzanych odcinków znajduje się w `#ep-watched-set`.
        val field = doc.getElementById("ep-watched-set")
            ?: throw IOException("Brak pola #ep-watched-set. Zapis anulowany.")
        val initialValue = if (field.tagName() == "textarea") field.text() else field.attr("value")
        if (!initialValue.matches(Regex("[0-9;]*"))) {
            throw IOException("Nieznany format pola #ep-watched-set. Zapis anulowany.")
        }
        val existingIds = initialValue.split(';')
            .filter { it.isNotBlank() }
            .map { it.toIntOrNull() ?: throw IOException("Bledne ID w #ep-watched-set") }
        if (existingIds.any { it <= 0 } || existingIds.distinct().size != existingIds.size) {
            throw IOException("Niespojny stan pola #ep-watched-set. Zapis anulowany.")
        }
        val currentlyWatched = episodeId in existingIds
        if (currentlyWatched != wasWatched) {
            throw IOException("Status odcinka zmienil sie na Shinden. Odswiez odcinki i sprobuj ponownie.")
        }
        if (currentlyWatched == wantWatched) return@withContext WriteResult(changed = false)

        // Identyfikator anime chroni przed użyciem danych po przekierowaniu na inną stronę.
        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href").orEmpty()
        if (canonical.isNotBlank()) {
            val canonicalUrl = URL(page.url, canonical)
            if (!isEpisodePage(canonicalUrl, titleId)) {
                throw IOException("Nieprawidlowa strona anime. Zapis anulowany.")
            }
        }

        // `_Storage.userId` wiąże stronę odcinków z bieżącym kontem.
        val pageUserId = Regex("""_Storage\.userId\s*=\s*(\d+)\s*;""")
            .find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (pageUserId != userId) {
            throw IOException("Strona nie potwierdza zalogowanego konta. Zapis anulowany.")
        }
        // W trybie ciasteczkowym `_Storage.basic` może być pusty, a autoryzację zapewnia sesja.
        
        
        val auth = findBasic(html).orEmpty()

        // API oczekuje pełnego zestawu w formacie `id;id;` albo pustego ciągu.
        val updatedIds = if (wantWatched) {
            existingIds + episodeId
        } else {
            existingIds.filterNot { it == episodeId }
        }
        val newValue = if (updatedIds.isEmpty()) "" else
            updatedIds.joinToString(separator = ";", postfix = ";")
        if (newValue == initialValue) return@withContext WriteResult(changed = false)

        postWatched(
            URL("https://shinden.pl/api/userlist/$userId/episodes/$titleId"),
            page.url,
            newValue,
            auth,
            userId,
            titleId
        )
        WriteResult(changed = true)
    }

    private data class Page(val html: String, val url: URL)

    private fun isEpisodePage(url: URL, titleId: Int): Boolean =
        url.protocol == "https" && url.host == ShindenUrls.HOST && url.port == -1 &&
            isAllowedQuery(url.query) && url.ref == null &&
            Regex("^/(?:titles|series)/$titleId(?:-[a-z0-9-]+)?/episodes/?$").matches(url.path)

    // WAF może dodać `r307=1`; inne parametry są niedozwolone.
    private fun isAllowedQuery(query: String?): Boolean =
        query == null || query.split('&').all { it.substringBefore('=') == "r307" }

    private fun readEpisodesPage(titleId: Int): Page {
        var url = URL(ShindenUrls.episodesUrl(titleId))
        repeat(5) {
            if (!isEpisodePage(url, titleId)) {
                throw IOException("Nieoczekiwane przekierowanie strony odcinkow")
            }
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "GET"
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "text/html")
                connection.setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9")
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("User-Agent", BROWSER_UA)
                connection.setRequestProperty("Referer", ShindenUrls.titleUrl(titleId))
                when (val status = connection.responseCode) {
                    200 -> return Page(
                        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() },
                        url
                    )
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Przekierowanie bez Location")
                        url = URL(url, location)
                    }
                    else -> throw IOException("Pobranie stanu odcinkow: HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Zbyt wiele przekierowan odcinkow")
    }

    private fun findBasic(html: String): String? {
        // Obsługujemy zapis `_Storage.basic` przez notację kropkową i indeksową.
        val pattern = Regex(
            """(?:window\.)?_Storage\s*(?:\.\s*basic|\[\s*['\"]basic['\"]\s*])\s*=\s*(['\"])([A-Za-z0-9+/_=-]{8,})\1"""
        )
        return pattern.find(html)?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
    }

    private fun postWatched(
        startUrl: URL,
        referrer: URL,
        episodes: String,
        auth: String,
        userId: Int,
        titleId: Int
    ) {
        // Kolejność pól musi odpowiadać skryptowi `lista-obejrzane.js`.
        val form = "auth=" + URLEncoder.encode(auth, "UTF-8") +
            "&episodes=" + URLEncoder.encode(episodes, "UTF-8")
        var url = startUrl
        // WAF może odpowiedzieć kodem 307 z `r307=1` i cookie `_rnd`; wtedy powtarzamy POST.
        
        
        repeat(5) {
            if (!isWatchedEndpoint(url, userId, titleId)) {
                throw IOException("Nieprawidlowy adres zapisu obejrzenia. Zapis anulowany.")
            }
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01")
                connection.setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9")
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
                connection.setRequestProperty("Referer", referrer.toString())
                connection.setRequestProperty("User-Agent", BROWSER_UA)
                connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
                when (val status = connection.responseCode) {
                    in 200..299 -> {
                        // Odpowiedź HTTP 2xx potwierdza zapis.
                        connection.inputStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        return
                    }
                    307, 308 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Przekierowanie zapisu bez Location")
                        url = URL(url, location)
                    }
                    else -> throw IOException("Zapis obejrzenia: HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Zbyt wiele przekierowan zapisu obejrzenia")
    }

    private fun isWatchedEndpoint(url: URL, userId: Int, titleId: Int): Boolean =
        url.protocol == "https" && url.host == ShindenUrls.HOST && url.port == -1 &&
            url.ref == null && url.path == "/api/userlist/$userId/episodes/$titleId" &&
            isAllowedQuery(url.query)
}
