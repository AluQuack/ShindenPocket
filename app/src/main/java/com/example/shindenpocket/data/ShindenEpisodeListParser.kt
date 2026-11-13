package com.example.shindenpocket.data

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import java.time.LocalDate

/** Wiersz listy odcinków odczytany z jednego z obsługiwanych układów HTML. */
internal data class ParsedEpisodeRow(
    val episodeId: Int,
    val number: String,
    val title: String,
    val online: Boolean?,
    val airDate: String?,
    val languages: List<String>,
    val detailUrl: String?
)

/**
 * Parser listy odcinków dla `/titles/{id}/episodes`, obsługujący `.ep-row` i układ tabeli.
 * Sam link nie potwierdza premiery: przyszłe odcinki mogą być ukryte i mieć pustą nazwę.
 */
internal object ShindenEpisodeListParser {
    private val paginationArg = Regex("[?&](?:strona|page)=([1-9][0-9]*)")

    fun parse(html: String, titleId: Int): List<ParsedEpisodeRow> {
        val doc = Jsoup.parse(html, ShindenUrls.BASE)
        val rows = doc.select("ol.ep-rows > li.ep-row, .ep-list li.ep-row")
        if (rows.isNotEmpty()) {
            return rows.map { row ->
                parseNewRow(row, titleId)
                    ?: throw IOException("Nie rozpoznano odcinka w nowej liscie HTML Shindena")
            }
        }

        val table = doc.selectFirst("table.data-view-table-episodes") ?: return emptyList()
        return table.select("tbody tr[data-episode-no]").map { parseLegacyRow(it, titleId) }
    }

    fun pageTitle(html: String): String {
        val doc = Jsoup.parse(html, ShindenUrls.BASE)
        return doc.selectFirst("h1.page-title .title")?.text()?.trim().orEmpty()
            .ifBlank { doc.selectFirst(".sh-tp-title")?.text()?.trim().orEmpty() }
            .ifBlank { doc.selectFirst("h1")?.text()?.trim().orEmpty() }
    }

    fun maxPage(html: String): Int {
        val doc = Jsoup.parse(html, ShindenUrls.BASE)
        var max = 1
        for (link in doc.select("a[href]")) {
            paginationArg.findAll(link.attr("href")).forEach { match ->
                val page = match.groupValues[1].toIntOrNull() ?: return@forEach
                if (page > max) max = page
            }
        }
        return max
    }

    fun listUrl(titleId: Int, page: Int): String {
        val base = "${ShindenUrls.episodesUrl(titleId)}?na_stronie=100"
        return if (page <= 1) base else "$base&strona=$page"
    }

    private fun parseNewRow(row: Element, titleId: Int): ParsedEpisodeRow? {
        val anchor = row.selectFirst("a.ep-row__link[href]")
            ?: row.selectFirst("a[href*=\"/view/\"]")
            ?: return null
        val path = ShindenUrls.pathOf(anchor.attr("href")) ?: return null
        val match = ShindenUrls.episodeLink.matchEntire(path) ?: return null
        val pathTitleId = match.groupValues[1].toIntOrNull() ?: return null
        val episodeId = match.groupValues[2].toIntOrNull() ?: return null
        if (pathTitleId != titleId) return null

        val number = row.selectFirst(".ep-row__no")?.text()?.trim().orEmpty()
        if (number.isEmpty()) {
            throw IOException("Brak numeru odcinka w nowej liscie HTML")
        }
        val title = row.selectFirst(".ep-row__title")?.text()?.trim().orEmpty()
        val languages = row.select(".ep-row__langs .lang-flag[title], .ep-row__langs .flag-icon[title]")
            .mapNotNull { it.attr("title").trim().ifBlank { null } }
            .distinct()
        val airDate = readDate(row)
        // Brak kolumny online wymaga rozpoznania przyszłych odcinków po `hidden` lub dacie emisji.
        
        
        
        val online = when {
            row.hasAttr("hidden") || isFutureAirDate(airDate) -> false
            title.isNotEmpty() || languages.isNotEmpty() -> true
            else -> null
        }
        return ParsedEpisodeRow(
            episodeId = episodeId,
            number = number,
            title = title,
            online = online,
            airDate = airDate,
            languages = languages,
            detailUrl = "${ShindenUrls.BASE}$path"
        )
    }

    private fun readDate(row: Element): String? {
        val node = row.selectFirst(".ep-row__date time") ?: row.selectFirst(".ep-row__date")
        val value = node?.attr("datetime")?.trim().orEmpty()
            .ifBlank { node?.text()?.trim().orEmpty() }
        return value.takeIf { it.isNotEmpty() && it != "—" && it != "-" }
    }

    internal fun isFutureAirDate(raw: String?): Boolean {
        if (raw == null) return false
        val value = raw.trim().lowercase()
        if (value == "jutro" || value.startsWith("za ")) return true
        return try {
            LocalDate.parse(raw.take(10)).isAfter(LocalDate.now())
        } catch (_: Exception) {
            false
        }
    }

    private fun parseLegacyRow(row: Element, titleId: Int): ParsedEpisodeRow {
        val cells = row.children().filter { it.tagName() == "td" }
        if (cells.size < 6) throw IOException("Zmiana struktury tabeli HTML")
        val href = row.selectFirst("a.detail[href]")?.attr("href").orEmpty()
        val path = ShindenUrls.pathOf(href) ?: ""
        val match = ShindenUrls.episodeLink.matchEntire(path)
            ?: throw IOException("Nie znaleziono ID odcinka w HTML")
        if (match.groupValues[1].toIntOrNull() != titleId) {
            throw IOException("Nieprawidlowy adres odcinka w HTML")
        }
        val episodeId = match.groupValues[2].toIntOrNull()
            ?: throw IOException("Nieprawidlowe ID odcinka w HTML")
        val check = cells[2].selectFirst("i.fa-check") != null
        val times = cells[2].selectFirst("i.fa-times") != null
        val online = when {
            check && !times -> true
            times && !check -> false
            else -> null
        }
        val languages = cells[3].select(".flag-icon")
            .mapNotNull { it.attr("title").trim().ifBlank { null } }
            .distinct()
        val date = cells[4].text().trim().ifBlank { null }
        return ParsedEpisodeRow(
            episodeId = episodeId,
            number = row.attr("data-episode-no").trim(),
            title = cells[1].text().trim(),
            online = online,
            airDate = date,
            languages = languages,
            detailUrl = "${ShindenUrls.BASE}$path"
        )
    }
}
