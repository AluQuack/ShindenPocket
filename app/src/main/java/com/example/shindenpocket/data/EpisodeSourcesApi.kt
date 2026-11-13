package com.example.shindenpocket.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale

/**
 * Odczytuje źródła z rzeczywistej strony odcinka, bez zgadywania adresów odtwarzaczy.
 * Obsługuje JSON z `script#episode-players-data` oraz awaryjnie układ tabeli.
 * Nierozpoznana struktura strony powoduje błąd zamiast zwrócenia niepewnych danych.
 */
data class EpisodeSource(
    val service: String,
    val quality: String?,
    val languages: List<String>,
    val addedAt: String?,
    // Identyfikator playera pochodzi z JSON albo z atrybutu `data-episode` układu tabeli.
    val playerId: String? = null
)

object EpisodeSourcesApi {
    private const val HOST = "shinden.pl"
    private const val MAX_HTML_CHARS = 3_000_000

    suspend fun fetch(detailUrl: String, titleId: Int, episodeId: Int, userId: Int): List<EpisodeSource> =
        withContext(Dispatchers.IO) {
            require(titleId > 0 && episodeId > 0 && userId > 0) { "Nieprawidłowe ID" }
            val expected = URL(detailUrl)
            checkEpisodeUrl(expected, titleId, episodeId)
            val html = downloadEpisodePage(expected, titleId, episodeId)
            parseEpisodeSources(html, titleId, episodeId, userId)
        }

    /** Parsuje zapisany HTML bez połączenia sieciowego. */
    internal fun parseEpisodeSources(
        html: String,
        titleId: Int,
        episodeId: Int,
        userId: Int
    ): List<EpisodeSource> {
        val doc = Jsoup.parse(html)

        // Deklarowane ID konta musi odpowiadać bieżącej sesji.
        val declaredUser = Regex("""_Storage\.userId\s*=\s*(\d+)\s*;""")
            .find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (declaredUser != null && declaredUser != userId) {
            throw IOException("Strona odcinka należy do innej sesji. Zaloguj się ponownie.")
        }

        val canonical = doc.selectFirst("link[rel=canonical]")?.attr("href")?.trim().orEmpty()
        if (canonical.isNotEmpty()) {
            val canonicalUrl = try { URL(URL("https://$HOST/"), canonical) }
            catch (_: Exception) { throw IOException("Nieprawidłowy adres canonical odcinka") }
            checkEpisodeUrl(canonicalUrl, titleId, episodeId)
        }

        // Lista playerów może być osadzona jako JSON w stronie odcinka.
        parseEmbeddedPlayers(doc)?.let { return it }

        // Brak rozpoznanej listy może oznaczać przekierowanie do logowania.
        // Nierozpoznana strona nie jest interpretowana jako potwierdzony brak źródeł.
        val candidates = doc.select("table").mapNotNull { table ->
            val headers = table.select("tr")
                .firstOrNull { row -> row.select("th").isNotEmpty() }
                ?.select("th")?.map { normalize(it.text()) }
                ?: emptyList()
            val service = headers.indexOfFirst { it.contains("serwis") || it.contains("hosting") }
            val quality = headers.indexOfFirst { it.contains("jakosc") || it.contains("quality") }
            val added = headers.indexOfFirst { it.contains("dodano") || it.contains("added") }
            val watch = headers.indexOfFirst { it.contains("ogladaj") || it.contains("watch") }
            if (service < 0 || (quality < 0 && added < 0 && watch < 0)) null
            else SourceTable(table, service, quality, added, watch)
        }

        val sourceTable = candidates.firstOrNull()
            ?: throw IOException(
                "Nie udało się rozpoznać tabeli źródeł. Strona może ładować ją przez JavaScript " +
                    "lub Shinden zmienił HTML. Potrzebny będzie podgląd Network."
            )

        val sources = mutableListOf<EpisodeSource>()
        for (row in sourceTable.element.select("tr")) {
            if (row.closest("table") !== sourceTable.element) continue
            val cells = row.children().filter { it.tagName() == "td" }
            if (cells.isEmpty()) continue
            val serviceCell = cells.getOrNull(sourceTable.serviceColumn) ?: continue
            val service = serviceCell.text().trim().ifBlank {
                serviceCell.selectFirst("[title]")?.attr("title")?.trim().orEmpty()
            }
            if (service.isBlank()) continue

            val quality = cells.getOrNull(sourceTable.qualityColumn)
                ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val addedAt = cells.getOrNull(sourceTable.addedColumn)
                ?.text()?.trim()?.takeIf { it.isNotEmpty() }

            val langs = row.select(".flag-icon, [class*=flag-icon], img[alt][class*=flag], [data-original-title][class*=flag]")
                .mapNotNull { flag ->
                    val title = flag.attr("title").ifBlank { flag.attr("data-original-title") }
                        .ifBlank { flag.attr("alt") }.trim()
                    title.ifBlank { languageFromClass(flag.className()).orEmpty() }
                }.filter { it.isNotBlank() }.distinct()

            val playerId = row.select("[data-episode]")
                .mapNotNull { button ->
                    try {
                        JSONObject(button.attr("data-episode"))
                            .optString("online_id")
                            .takeIf { it.matches(Regex("[0-9]{1,16}")) }
                    } catch (_: Exception) { null }
                }.firstOrNull()

            sources.add(EpisodeSource(service, quality, langs, addedAt, playerId))
        }
        return sources
    }

    /** Odczytuje JSON z `script#episode-players-data`; zwraca `null`, gdy skryptu nie ma. */
    private fun parseEmbeddedPlayers(doc: org.jsoup.nodes.Document): List<EpisodeSource>? {
        val script = doc.selectFirst("script#episode-players-data") ?: return null
        val json = try { JSONObject(script.data()) } catch (_: Exception) { return null }
        val players = json.optJSONArray("players") ?: return emptyList()
        val sources = mutableListOf<EpisodeSource>()
        for (i in 0 until players.length()) {
            val player = players.optJSONObject(i) ?: continue
            val service = player.optString("player", "").trim()
            if (service.isEmpty()) continue
            val quality = player.optInt("res", 0).takeIf { it > 0 }?.toString()
            val playerId = player.opt("online_id")
                ?.toString()
                ?.takeIf { it.matches(Regex("[0-9]{1,16}")) }
            val languages = sequenceOf("audio", "sub")
                .mapNotNull { key -> player.optString(key, "").trim().ifEmpty { null } }
                .flatMap { it.split(',').asSequence() }
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull(::languageFromCode)
                .distinct()
                .toList()
            sources.add(EpisodeSource(service, quality, languages, null, playerId))
        }
        return sources
    }

    internal fun languageFromCode(code: String): String? = when (code.lowercase(Locale.ROOT)) {
        "pl" -> "Polski"
        "ipl" -> "Polski AI"
        "jp", "ja" -> "Japoński"
        "en", "gb", "us" -> "Angielski"
        "de" -> "Niemiecki"
        "fr" -> "Francuski"
        "es" -> "Hiszpański"
        "it" -> "Włoski"
        "ru" -> "Rosyjski"
        else -> code.uppercase(Locale.ROOT).takeIf { it.isNotBlank() }
    }

    private data class SourceTable(
        val element: Element,
        val serviceColumn: Int,
        val qualityColumn: Int,
        val addedColumn: Int,
        val watchColumn: Int
    )

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "")

    private fun languageFromClass(classes: String): String? {
        val code = Regex("(?:flag-icon-|flag-)([a-z]{2})(?:\\b|$)")
            .find(classes)?.groupValues?.getOrNull(1) ?: return null
        return when (code) {
            "pl" -> "Polski"
            "jp" -> "Japoński"
            "en", "gb", "us" -> "Angielski"
            else -> code.uppercase(Locale.ROOT)
        }
    }

    private fun checkEpisodeUrl(url: URL, titleId: Int, episodeId: Int) {
        val match = if (url.protocol == "https" && url.host == HOST && url.port == -1 &&
            url.query == null && url.ref == null
        ) ShindenUrls.episodeLink.matchEntire(url.path) else null
        if (match == null ||
            match.groupValues[1].toIntOrNull() != titleId ||
            match.groupValues[2].toIntOrNull() != episodeId
        ) {
            throw IOException("Adres nie jest stroną wskazanego odcinka na Shinden")
        }
    }

    private fun downloadEpisodePage(startUrl: URL, titleId: Int, episodeId: Int): String {
        var url = startUrl
        repeat(5) {
            checkEpisodeUrl(url, titleId, episodeId)
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "text/html")
                connection.setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9,en-US;q=0.8")
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                connection.setRequestProperty("Referer", ShindenUrls.episodesUrl(titleId))
                connection.setRequestProperty("Cache-Control", "no-cache")
                when (val status = connection.responseCode) {
                    200 -> {
                        val content = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                            val out = StringBuilder()
                            val buffer = CharArray(8192)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                out.append(buffer, 0, count)
                                if (out.length > MAX_HTML_CHARS) {
                                    throw IOException("Strona odcinka jest zbyt duża")
                                }
                            }
                            out.toString()
                        }
                        return content
                    }
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Przekierowanie bez Location")
                        url = URL(url, location)
                    }
                    else -> throw IOException("Pobieranie strony odcinka: HTTP $status")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Za dużo przekierowań strony odcinka")
    }
}
