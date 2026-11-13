package com.example.shindenpocket.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Odcinki anime spoza biblioteki: wyłącznie potwierdzony HTML strony Shindena. */
data class SeasonSeriesEpisode(
    val id: Int,
    val number: String,
    val title: String,
    val online: Boolean?,
    val airDate: String?,
    val languages: List<String>,
    val detailUrl: String?
)

data class SeriesAltTitle(
    val langCode: String,
    val title: String,
    val langName: String? = null
)

data class SeriesSubRating(
    val label: String,
    val score: String
)

data class SeriesTrailer(
    val youtubeId: String?,
    val videoUrl: String,
    val thumbnailUrl: String?,
    val title: String?,
    val caption: String?,
    val moreCountLabel: String? = null,
    val moreVideosUrl: String? = null
)

data class SeriesTagGroup(
    val category: String,
    val tags: List<String>
)

data class SeriesInfoField(
    val label: String,
    val value: String,
    val primaryChips: List<String> = emptyList(),
    val extraChips: List<String> = emptyList()
)

data class SeriesRatingBucket(
    val rating: Int,
    val heightPercent: Int,
    val votes: Int?,
    val tooltip: String? = null
)

data class SeriesStatusCount(
    val label: String,
    val count: Int,
    val displayCount: String
)

data class SeriesCharacter(
    val id: Int?,
    val name: String,
    val role: String?,
    val imageUrl: String?,
    val voiceActorName: String? = null,
    val voiceActorRole: String? = null,
    val voiceActorImageUrl: String? = null
)

data class SeasonSeriesInfo(
    val description: String?,
    val bannerUrl: String? = null,
    val coverUrl: String? = null,
    val kind: String? = null,
    val metaBadges: List<String> = emptyList(),
    val altTitles: List<SeriesAltTitle> = emptyList(),
    val rating: String? = null,
    val votesCount: String? = null,
    val rankingPosition: String? = null,
    val subRatings: List<SeriesSubRating> = emptyList(),
    val favoritesLabel: String? = null,
    val trailer: SeriesTrailer? = null,
    val tags: List<String> = emptyList(),
    val tagGroups: List<SeriesTagGroup> = emptyList(),
    val infoFields: List<SeriesInfoField> = emptyList(),
    val ratingDistribution: List<SeriesRatingBucket> = emptyList(),
    val listStatuses: List<SeriesStatusCount> = emptyList(),
    val characters: List<SeriesCharacter> = emptyList(),
    val relatedSeries: List<RelatedSeries> = emptyList()
)

object SeasonSeriesApi {
    private fun validTitlePath(path: String, titleId: Int): Boolean {
        val match = ShindenUrls.titleLink.matchEntire(path) ?: return false
        return match.groupValues[1].toIntOrNull() == titleId
    }

    private fun validEpisodesPath(path: String, titleId: Int): Boolean =
        Regex("^/(?:titles|series)/$titleId(?:-[a-z0-9-]+)?/episodes/?$").matches(path)

    private fun validate(url: URL, titleId: Int, episodes: Boolean) {
        if (url.protocol != "https" || url.host != ShindenUrls.HOST || url.port != -1 ||
            url.ref != null ||
            !(if (episodes) validEpisodesPath(url.path, titleId) else validTitlePath(url.path, titleId))
        ) throw IOException("Nieoczekiwany adres strony Shindena")
        if (url.query != null) {
            if (episodes && !isAllowedEpisodesQuery(url.query)) {
                throw IOException("Nieoczekiwane parametry strony odcinków")
            }
            if (!episodes && !isAllowedTitleQuery(url.query)) {
                throw IOException("Nieoczekiwane parametry strony Shindena")
            }
        }
    }

    // Strona tytułu dopuszcza wyłącznie parametr WAF `r307=1`.
    private fun isAllowedTitleQuery(query: String?): Boolean =
        query == null || query.split('&').all { it.substringBefore('=') == "r307" }

    private fun isAllowedEpisodesQuery(query: String?): Boolean {
        if (query == null) return true
        return query.split('&').all { part ->
            val key = part.substringBefore('=')
            key == "strona" || key == "na_stronie" || key == "r307"
        }
    }

    private fun readPage(titleId: Int, episodes: Boolean, page: Int = 1): String {
        require(titleId > 0)
        var url = URL(
            if (episodes) ShindenEpisodeListParser.listUrl(titleId, page)
            else ShindenUrls.titleUrl(titleId)
        )
        repeat(5) {
            validate(url, titleId, episodes)
            val conn = url.openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = false
                conn.useCaches = false
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                conn.setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
                if (episodes) conn.setRequestProperty("Referer", ShindenUrls.titleUrl(titleId))
                when (val code = conn.responseCode) {
                    200 -> {
                        val content = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        if (content.length > 4_000_000) throw IOException("Zbyt duża odpowiedź HTML")
                        return content
                    }
                    301, 302, 303, 307, 308 -> {
                        url = URL(url, conn.getHeaderField("Location")
                            ?: throw IOException("Przekierowanie bez adresu"))
                    }
                    else -> throw IOException("Nie można pobrać strony anime (HTTP $code)")
                }
            } finally { conn.disconnect() }
        }
        throw IOException("Zbyt wiele przekierowań")
    }

    private fun resolveShindenImage(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val resolved = try { URL(URL("${ShindenUrls.BASE}/"), raw.trim()) } catch (_: Exception) { return null }
        // Okładki i banery mogą zawierać parametr wersji `v`; walidujemy ścieżkę obrazu.
        return resolved.toString().takeIf {
            resolved.protocol == "https" && resolved.host == ShindenUrls.HOST &&
                resolved.port == -1 && resolved.ref == null &&
                resolved.path.startsWith("/res/images/")
        }
    }

    /** Opis i metadane karty serii: wyłącznie potwierdzony HTML strony Shindena. */
    suspend fun fetchInfo(titleId: Int): SeasonSeriesInfo = withContext(Dispatchers.IO) {
        parseInfo(readPage(titleId, episodes = false), titleId)
    }

    internal fun parseInfo(html: String, titleId: Int): SeasonSeriesInfo {
        val doc = Jsoup.parse(html, ShindenUrls.titleUrl(titleId))
        checkCanonical(doc.selectFirst("link[rel=canonical]")?.attr("href"), titleId, false)
        val description = listOf(
            doc.selectFirst("[itemprop=description]")?.text(),
            doc.selectFirst(".series-description")?.text(),
            doc.selectFirst("#description")?.text(),
            doc.selectFirst("meta[property=og:description]")?.attr("content")
        ).firstOrNull { !it.isNullOrBlank() && it.trim().length > 30 }
            ?.trim()?.take(4000)

        val bannerImg = doc.selectFirst(".sh-tp-banner img")
        val bannerUrl = sequenceOf(bannerImg?.attr("data-src"), bannerImg?.attr("src"))
            .mapNotNull(::resolveShindenImage)
            .firstOrNull()

        val coverImg = doc.selectFirst(".sh-tp-cover img")
        val coverUrl = sequenceOf(
            coverImg?.attr("data-src"),
            coverImg?.attr("src"),
            doc.selectFirst(".sh-tp-listbtn")?.attr("data-cover")
        ).mapNotNull(::resolveShindenImage).firstOrNull()

        val kind = doc.selectFirst(".sh-tp-head .sh-tp-kind")?.text()?.trim()?.takeIf(String::isNotEmpty)
        val metaBadges = doc.select(".sh-tp-head .sh-tp-meta span")
            .mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }

        val altTitles = doc.select(".sh-tp-alttitles .alt-titles li").mapNotNull { li ->
            val abbr = li.selectFirst("abbr")
            val code = abbr?.text()?.trim()?.takeIf(String::isNotEmpty) ?: "ALT"
            val langName = abbr?.attr("title")?.trim()?.takeIf(String::isNotEmpty)
                ?: li.selectFirst(".flag-icon")?.attr("title")?.trim()?.takeIf(String::isNotEmpty)
            val title = (li.selectFirst("span[lang]")?.text() ?: li.ownText()).trim()
                .takeIf(String::isNotEmpty) ?: return@mapNotNull null
            SeriesAltTitle(langCode = code, title = title, langName = langName)
        }

        val wynik = doc.selectFirst(".sh-tp-wynik")
        val rating = wynik?.selectFirst(".sh-tp-score b")?.text()?.trim()?.takeIf(String::isNotEmpty)
        val scoreSpans = wynik?.select("p span")
            ?.mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .orEmpty()
        val votesCount = scoreSpans.firstOrNull { it.contains("głos", ignoreCase = true) }
        val rankingPosition = scoreSpans.firstOrNull {
            it.contains("#") || it.contains("ranking", ignoreCase = true)
        }
        val subRatings = wynik?.select(".sh-tp-parts > span")?.mapNotNull { span ->
            val score = span.selectFirst("b")?.text()?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val label = span.ownText().trim().takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            SeriesSubRating(label = label, score = score)
        }.orEmpty()

        val favoritesLabel = doc.selectFirst(".sh-fav-cnt")?.text()?.trim()?.takeIf(String::isNotEmpty)

        val videoBox = doc.selectFirst(".sh-tp-video")
        val videoLink = videoBox?.selectFirst("a.sh-tp-video__link")
        val ytId = videoLink?.attr("data-yt")?.trim()
            ?.takeIf { Regex("^[A-Za-z0-9_-]{6,20}$").matches(it) }
        val rawVideoHref = videoLink?.attr("href")?.trim().orEmpty()
        val videoUrl = when {
            ytId != null -> "https://www.youtube.com/watch?v=$ytId"
            rawVideoHref.startsWith("https://www.youtube.com/") ||
                rawVideoHref.startsWith("https://youtu.be/") -> rawVideoHref
            else -> null
        }
        val trailer = videoUrl?.let { url ->
            val aria = videoLink?.attr("aria-label")?.trim()
                ?.removePrefix("Odtwórz:")?.trim()?.takeIf(String::isNotEmpty)
            val title = videoBox?.selectFirst(".sh-tp-video__title")?.text()?.trim()?.takeIf(String::isNotEmpty)
                ?: aria
            val caption = videoBox?.selectFirst(".sh-tp-video__caption")?.text()?.trim()?.takeIf(String::isNotEmpty)
            val moreEl = videoBox?.selectFirst("a.sh-tp-video__more")
            val moreLabel = moreEl?.text()?.trim()?.takeIf(String::isNotEmpty)
            val moreUrl = moreEl?.attr("href")?.let { href ->
                try {
                    val u = URL(URL("${ShindenUrls.BASE}/"), href)
                    if (u.protocol == "https" && u.host == ShindenUrls.HOST && u.port == -1) u.toString() else null
                } catch (_: Exception) { null }
            }
            SeriesTrailer(
                youtubeId = ytId,
                videoUrl = url,
                thumbnailUrl = ytId?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" },
                title = title,
                caption = caption,
                moreCountLabel = moreLabel,
                moreVideosUrl = moreUrl
            )
        }

        val tags = doc.select(".sh-tp-tags a.sh-tp-tag")
            .mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .distinct()
        val tagGroups = doc.select(".sh-tp-tags-all .sh-tp-taggroup").mapNotNull { group ->
            val category = group.selectFirst("h3, h4, strong")?.text()?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val groupTags = group.select("a.sh-tp-tag")
                .mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
                .distinct()
            if (groupTags.isEmpty()) null else SeriesTagGroup(category = category, tags = groupTags)
        }

        val infoFields = mutableListOf<SeriesInfoField>()
        val dl = doc.selectFirst("dl.sh-tp-info")
        if (dl != null) {
            var pendingDt: String? = null
            for (child in dl.children()) {
                when (child.tagName().lowercase()) {
                    "dt" -> pendingDt = child.text().trim().takeIf(String::isNotEmpty)
                    "dd" -> {
                        val label = pendingDt ?: continue
                        pendingDt = null
                        val extraChips = child.select("details a.sh-tp-tag")
                            .mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
                            .distinct()
                        val allChips = child.select("a.sh-tp-tag")
                            .mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
                            .distinct()
                        if (allChips.isNotEmpty()) {
                            val extraSet = extraChips.toSet()
                            val primaryChips = allChips.filterNot { it in extraSet }.ifEmpty { allChips }
                            infoFields += SeriesInfoField(
                                label = label,
                                value = primaryChips.joinToString(", "),
                                primaryChips = primaryChips,
                                extraChips = extraChips
                            )
                        } else {
                            val value = child.text().trim().takeIf(String::isNotEmpty) ?: continue
                            infoFields += SeriesInfoField(label = label, value = value)
                        }
                    }
                }
            }
        }

        val ratingDistribution = doc.select("ul.sh-tp-dist > li").mapIndexed { index, li ->
            val bar = li.selectFirst(".sh-tp-dist__bar")
            val heightPct = bar?.classNames()?.firstNotNullOfOrNull { cls ->
                Regex("^sh-tp-h-(\\d+)$").find(cls)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }?.coerceIn(0, 100) ?: 0
            val tip = li.selectFirst(".sh-tp-dist__tip")?.text()?.trim()?.takeIf(String::isNotEmpty)
            val ratingNum = tip?.let {
                Regex("Ocena\\s+(\\d+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
            } ?: (index + 1)
            val votes = tip?.let {
                Regex(":\\s*(\\d+)").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
            SeriesRatingBucket(
                rating = ratingNum,
                heightPercent = heightPct,
                votes = votes,
                tooltip = tip
            )
        }

        val listStatuses = doc.select("ul.sh-tp-statuslist > li").mapNotNull { li ->
            val rawCount = li.selectFirst("b")?.text()?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            val count = rawCount.replace(" ", "").replace("\u00A0", "").toIntOrNull() ?: 0
            val label = li.ownText().trim().takeIf(String::isNotEmpty)
                ?: li.text().removeSuffix(rawCount).trim().takeIf(String::isNotEmpty)
                ?: return@mapNotNull null
            SeriesStatusCount(label = label, count = count, displayCount = rawCount)
        }

        val characters = doc.select("ul.sh-tp-chars > li.sh-tp-char").mapNotNull { li ->
            val charWho = li.selectFirst(".sh-tp-char__who:not(.sh-tp-char__who--va)")
                ?: li.selectFirst(".sh-tp-char__who")
            val nameEl = charWho?.selectFirst(".sh-tp-char__name") ?: li.selectFirst(".sh-tp-char__name")
            val name = nameEl?.text()?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            val charHref = nameEl.attr("href")
            val charId = Regex("/character/(\\d+)").find(charHref)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val role = charWho?.selectFirst(".sh-tp-char__role")?.text()?.trim()?.takeIf(String::isNotEmpty)
            val charImgEl = li.children().firstOrNull { it.tagName() == "img" } ?: li.selectFirst("img")
            val charImg = sequenceOf(charImgEl?.attr("data-src"), charImgEl?.attr("src"))
                .mapNotNull(::resolveShindenImage)
                .firstOrNull()
            val vaEl = li.selectFirst(".sh-tp-char__va")
            val vaName = vaEl?.selectFirst(".sh-tp-char__name")?.text()?.trim()?.takeIf(String::isNotEmpty)
            val vaRole = vaEl?.selectFirst(".sh-tp-char__role")?.text()?.trim()?.takeIf(String::isNotEmpty)
            val vaImgEl = vaEl?.selectFirst("img")
            val vaImg = sequenceOf(vaImgEl?.attr("data-src"), vaImgEl?.attr("src"))
                .mapNotNull(::resolveShindenImage)
                .firstOrNull()
            SeriesCharacter(
                id = charId,
                name = name,
                role = role,
                imageUrl = charImg,
                voiceActorName = vaName,
                voiceActorRole = vaRole,
                voiceActorImageUrl = vaImg
            )
        }

        val relatedSeries = parseRelatedSeriesFromDoc(doc, titleId)

        return SeasonSeriesInfo(
            description = description,
            bannerUrl = bannerUrl,
            coverUrl = coverUrl,
            kind = kind,
            metaBadges = metaBadges,
            altTitles = altTitles,
            rating = rating,
            votesCount = votesCount,
            rankingPosition = rankingPosition,
            subRatings = subRatings,
            favoritesLabel = favoritesLabel,
            trailer = trailer,
            tags = tags,
            tagGroups = tagGroups,
            infoFields = infoFields,
            ratingDistribution = ratingDistribution,
            listStatuses = listStatuses,
            characters = characters,
            relatedSeries = relatedSeries
        )
    }

    suspend fun fetchRelatedSeries(titleId: Int): List<RelatedSeries> = withContext(Dispatchers.IO) {
        parseRelatedSeries(readPage(titleId, episodes = false), titleId)
    }

    /**
     * Odczytuje wyłącznie zakotwiczoną sekcję `section.sh-tp-section` z kartami `li.sh-tp-tile`.
     * Nierozpoznany układ zwraca pustą listę zamiast zgadywać relacje.
     */
    internal fun parseRelatedSeries(html: String, titleId: Int): List<RelatedSeries> {
        val doc = Jsoup.parse(html, ShindenUrls.titleUrl(titleId))
        checkCanonical(doc.selectFirst("link[rel=canonical]")?.attr("href"), titleId, false)
        return parseRelatedSeriesFromDoc(doc, titleId)
    }

    private fun parseRelatedSeriesFromDoc(doc: org.jsoup.nodes.Document, titleId: Int): List<RelatedSeries> {
        val heading = doc.select("h1,h2,h3,h4,h5,h6,div,span,strong")
            .firstOrNull { it.ownText().trim().lowercase().contains("powiązane serie") }
            ?: return emptyList()
        val root = heading.closest("section.sh-tp-section")
            ?: heading.parents().firstOrNull { parent ->
                val titleLinks = parent.select("a[href]").count { link ->
                    val path = validTitlePath(link.attr("href")) ?: return@count false
                    ShindenUrls.titleLink.matches(path)
                }
                titleLinks in 1..32 && parent.text().length < 10000
            } ?: return emptyList()
        val result = linkedMapOf<Int, RelatedSeries>()
        for (link in root.select("a[href]")) {
            val url = try { URL(URL("${ShindenUrls.BASE}/"), link.attr("href")) }
                catch (_: Exception) { continue }
            if (url.protocol != "https" || url.host != ShindenUrls.HOST || url.port != -1 ||
                url.query != null || url.ref != null) continue
            val match = ShindenUrls.titleLink.matchEntire(url.path) ?: continue
            val id = match.groupValues[1].toIntOrNull() ?: continue
            if (id == titleId || id in result) continue
            // Karta to `li.sh-tp-tile`; nie schodzimy wyżej, bo wtedy selektor
            // relacji trafiłby w pierwszą kartę całej listy.
            val container = link.closest("li.sh-tp-tile") ?: link.parent() ?: continue
            // Karta ma jawny element relacji; tekst kontenera zawiera dodatkowo
            // nazwę i typ, więc czytamy najpierw dedykowany selektor.
            val local = (container.selectFirst(".sh-tp-tile__rel")?.text() ?: container.text())
                .lowercase()
            val relation = when {
                Regex("\\bprequel\\b|\\bprequel[eu]\\b", RegexOption.IGNORE_CASE).containsMatchIn(local) -> "Prequel"
                Regex("\\bsequel\\b|\\bsequel[eu]\\b", RegexOption.IGNORE_CASE).containsMatchIn(local) -> "Sequel"
                local.contains("pierwowzór") || local.contains("pierwowzor") -> "Pierwowzór"
                local.contains("adaptacj") -> "Adaptacja"
                local.contains("historia poboczna") -> "Spin-off"
                local.contains("spin-off") -> "Spin-off"
                local.trim() == "inne" -> "Inne"
                else -> "Powiązane"
            }
            val title = sequenceOf(
                container.selectFirst(".sh-tp-tile__name")?.text(),
                link.selectFirst(".sh-tp-tile__name")?.text(),
                link.attr("title"),
                link.selectFirst("img")?.attr("alt"),
                link.text()
            ).mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.firstOrNull() ?: continue
            val image = link.selectFirst("img") ?: container.selectFirst("img")
            val cover = sequenceOf(image?.attr("data-src"), image?.attr("src"))
                .mapNotNull(::resolveShindenImage).firstOrNull()
            result[id] = RelatedSeries(SeasonAnime(id, title, url.toString(), cover), relation)
        }
        return result.values.toList()
    }

    suspend fun fetchEpisodes(titleId: Int, userId: Int?): List<SeasonSeriesEpisode> =
        withContext(Dispatchers.IO) {
            val firstHtml = readPage(titleId, episodes = true)
            val byId = linkedMapOf<Int, SeasonSeriesEpisode>()
            for (episode in parseEpisodes(firstHtml, titleId, userId)) {
                byId[episode.id] = episode
            }
            // Lista odcinków jest stronicowana, dlatego pobieramy wszystkie strony.
            val maxPage = ShindenEpisodeListParser.maxPage(firstHtml)
            for (page in 2..maxPage) {
                for (episode in parseEpisodes(readPage(titleId, episodes = true, page), titleId, userId)) {
                    if (byId.put(episode.id, episode) != null) {
                        throw IOException("Powtórzone ID odcinka w HTML")
                    }
                }
            }
            byId.values.toList()
        }

    /** Waliduje ścieżkę tytułu, odrzucając linki edycji i inne działy. */
    private fun validTitlePath(raw: String): String? {
        val url = try { URL(URL("${ShindenUrls.BASE}/"), raw.trim()) } catch (_: Exception) { return null }
        if (url.protocol != "https" || url.host != ShindenUrls.HOST || url.port != -1 ||
            url.query != null || url.ref != null
        ) return null
        return url.path
    }

    private fun checkCanonical(raw: String?, titleId: Int, episodes: Boolean) {
        if (raw.isNullOrBlank()) return
        val url = try { URL(URL("https://shinden.pl/"), raw) }
        catch (_: Exception) { throw IOException("Nieprawidłowy adres canonical") }
        validate(url, titleId, episodes)
    }

    /** Nie interpretuj braku wierszy jako braku dostępnych odcinków. */
    internal fun parseEpisodes(html: String, titleId: Int, userId: Int?): List<SeasonSeriesEpisode> {
        val doc = Jsoup.parse(html, ShindenUrls.episodesUrl(titleId))
        checkCanonical(doc.selectFirst("link[rel=canonical]")?.attr("href"), titleId, true)
        // Jeżeli strona deklaruje konto, dane muszą pochodzić z tej samej sesji.
        val declared = Regex("""_Storage\.userId\s*=\s*(\d+)\s*;""")
            .find(html)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (userId != null && declared != null && declared != userId) {
            throw IOException("Strona odcinków nie potwierdza sesji. Zaloguj się ponownie.")
        }
        return ShindenEpisodeListParser.parse(html, titleId).map { row ->
            SeasonSeriesEpisode(
                id = row.episodeId,
                number = row.number,
                title = row.title,
                online = row.online,
                airDate = row.airDate,
                languages = row.languages,
                detailUrl = row.detailUrl
            )
        }
    }
}

/** Publiczne powiązania ze strony szczegółów serii; bez nowych endpointów Shindena. */
data class RelatedSeries(
    val series: SeasonAnime,
    val relation: String
)
