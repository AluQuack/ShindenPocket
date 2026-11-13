package com.example.shindenpocket.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import kotlin.math.roundToInt

enum class WatchStatusOption(
    val code: String,
    val apiValue: String?,
    val label: String
) {
    NONE("", null, "Bez statusu"),
    IN_PROGRESS("in progress", "in progress", "Oglądam"),
    COMPLETED("completed", "completed", "Obejrzane"),
    PLANNED("plan", "plan", "Planuję"),
    ON_HOLD("hold", "hold", "Wstrzymane"),
    DROPPED("dropped", "dropped", "Porzucone"),
    SKIP("skip", "skip", "Pomijam");

    companion object {
        fun fromCode(raw: String?): WatchStatusOption = when (raw?.trim()?.lowercase()) {
            "in progress" -> IN_PROGRESS
            "completed" -> COMPLETED
            "plan", "planned" -> PLANNED
            "hold", "on hold", "on-hold" -> ON_HOLD
            "dropped" -> DROPPED
            "skip", "skipped" -> SKIP
            else -> NONE
        }

        fun normalizeCode(raw: String?): String = fromCode(raw).code
    }
}

object ShindenRatingScale {
    fun labelFor(score: Int?): String? = when (score) {
        1 -> "Beznadziejne"
        2 -> "Słabe"
        3 -> "Mierne"
        4 -> "Ujdzie"
        5 -> "Średnie"
        6 -> "Niezłe"
        7 -> "Dobre"
        8 -> "Bardzo dobre"
        9 -> "Niezwykłe"
        10 -> "Arcydzieło"
        else -> null
    }

    fun descriptionFor(score: Int?): String? = when (score) {
        1 -> "Totalna strata czasu, nie ma tutaj niczego zasługującego na uwagę."
        2 -> "Pojedyncze przebłyski jakości giną w morzu wad."
        3 -> "Więcej wad niż zalet, trudno dotrwać do końca."
        4 -> "Da się skończyć, ale bez większej przyjemności."
        5 -> "Przeciętne, da się obejrzeć, ale nie zapada w pamięć."
        6 -> "Przyjemnie się ogląda, mimo zauważalnych wad."
        7 -> "Dobra seria warta polecenia, drobne wady nie przeszkadzają."
        8 -> "Naprawdę wciąga i wyróżnia się bardzo dobrym wykonaniem."
        9 -> "Perełka! Wyróżnia się na tle gatunku i zostaje w głowie na lata."
        10 -> "Po prostu arcydzieło pod każdym względem."
        else -> null
    }

    /** Formuła `rateLevels`: fabuła ma wagę 2, a pozostałe kryteria wagę 1. */
    fun suggestedTotal(
        story: Int?,
        graphics: Int?,
        music: Int?,
        characters: Int?
    ): Int? {
        var weightedSum = 0
        var totalWeight = 0
        if (story in 1..10) {
            weightedSum += (story ?: 0) * 2
            totalWeight += 2
        }
        if (graphics in 1..10) {
            weightedSum += graphics ?: 0
            totalWeight += 1
        }
        if (music in 1..10) {
            weightedSum += music ?: 0
            totalWeight += 1
        }
        if (characters in 1..10) {
            weightedSum += characters ?: 0
            totalWeight += 1
        }
        if (totalWeight == 0) return null
        return (weightedSum.toDouble() / totalWeight.toDouble()).roundToInt().coerceIn(1, 10)
    }
}

data class RemoteListEntry(
    val titleId: Int,
    val status: String,
    val progress: Int,
    val favourite: Boolean,
    val rateTotal: Int?,
    val rateStory: Int?,
    val rateGraphic: Int?,
    val rateMusic: Int?,
    val rateCharacters: Int?
)

data class AnimeComment(
    val id: Long,
    val animeId: Int,
    val authorId: Int,
    val authorName: String,
    val avatarUrl: String?,
    val rank: String?,
    val body: String,
    val createdAt: String,
    val edited: Boolean = false,
    val likes: Int = 0,
    val parentId: Long? = null,
    val isLocalOnly: Boolean = false
) {
    fun isOwnedBy(userId: Int?, username: String?): Boolean {
        if (isLocalOnly) return true
        if (userId != null && userId > 0 && authorId == userId) return true
        if (!username.isNullOrBlank() && authorName.equals(username.trim(), ignoreCase = true)) return true
        return false
    }
}

data class CommentsPage(
    val comments: List<AnimeComment>,
    val hasMore: Boolean = false,
    val lastId: Long? = null
)

object ShindenManagementApi {
    private const val V1_BASE = "${ShindenUrls.BASE}/v1"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    suspend fun fetchListEntry(titleId: Int): RemoteListEntry? = withContext(Dispatchers.IO) {
        val (code, text) = executeV1Request(
            method = "GET",
            path = "/me/list/anime/$titleId",
            refererPath = "/titles/$titleId"
        )
        if (code == 404 || code == 401) return@withContext null
        if (code !in 200..299 || text.isBlank()) {
            throw IOException(extractErrorMessage(code, text))
        }
        parseListEntryJson(JSONObject(text), titleId)
    }

    suspend fun updateListEntry(
        titleId: Int,
        statusOption: WatchStatusOption? = null,
        favourite: Boolean? = null,
        progress: Int? = null,
        includeRatings: Boolean = false,
        rateTotal: Int? = null,
        rateStory: Int? = null,
        rateGraphic: Int? = null,
        rateMusic: Int? = null,
        rateCharacters: Int? = null
    ): RemoteListEntry = withContext(Dispatchers.IO) {
        val payload = buildUpdatePayload(
            statusOption = statusOption,
            favourite = favourite,
            progress = progress,
            includeRatings = includeRatings,
            rateTotal = rateTotal,
            rateStory = rateStory,
            rateGraphic = rateGraphic,
            rateMusic = rateMusic,
            rateCharacters = rateCharacters
        )

        val (code, text) = executeV1Request(
            method = "PUT",
            path = "/me/list/anime/$titleId",
            body = payload.toString(),
            refererPath = "/titles/$titleId",
            extraCookie = "sh_lv=${System.currentTimeMillis()}"
        )
        if (code !in 200..299) {
            throw IOException(extractErrorMessage(code, text))
        }
        if (text.isBlank()) {
            RemoteListEntry(
                titleId = titleId,
                status = statusOption?.code.orEmpty(),
                progress = progress ?: 0,
                favourite = favourite ?: false,
                rateTotal = rateTotal,
                rateStory = rateStory,
                rateGraphic = rateGraphic,
                rateMusic = rateMusic,
                rateCharacters = rateCharacters
            )
        } else {
            parseListEntryJson(JSONObject(text), titleId)
        }
    }

    suspend fun deleteListEntry(titleId: Int): Boolean = withContext(Dispatchers.IO) {
        val (code, _) = executeV1Request(
            method = "DELETE",
            path = "/me/list/series/$titleId",
            refererPath = "/titles/$titleId",
            extraCookie = "sh_lv=${System.currentTimeMillis()}"
        )
        code in 200..299 || code == 404
    }

    suspend fun fetchComments(titleId: Int, beforeId: Long? = null): CommentsPage =
        withContext(Dispatchers.IO) {
            val query = if (beforeId != null && beforeId > 0L) "?przed=$beforeId" else ""
            val url = "${ShindenUrls.BASE}/komentarze/title/$titleId$query"
            val html = fetchCommentsFragmentHtml(url, "${ShindenUrls.BASE}/titles/$titleId")
            parseCommentsHtml(html, titleId)
        }

    suspend fun postComment(
        titleId: Int,
        body: String,
        parentId: Long? = null
    ): Long? = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("type", "title")
            .put("id", titleId)
            .put("body", body.trim())
        if (parentId != null && parentId > 0L) {
            payload.put("parent_id", parentId)
        }
        val (code, text) = executeV1Request(
            method = "POST",
            path = "/comments",
            body = payload.toString(),
            refererPath = "/titles/$titleId"
        )
        if (code !in 200..299) {
            throw IOException(extractErrorMessage(code, text))
        }
        runCatching {
            JSONObject(text).optLong("comment_id", 0L).takeIf { it > 0L }
        }.getOrNull()
    }

    suspend fun fetchCommentRawBody(commentId: Long): String? = withContext(Dispatchers.IO) {
        val (code, text) = executeV1Request(
            method = "GET",
            path = "/comments/$commentId"
        )
        if (code !in 200..299 || text.isBlank()) return@withContext null
        runCatching {
            JSONObject(text).optString("body").takeIf { it.isNotBlank() && it != "null" }
        }.getOrNull()
    }

    suspend fun editComment(commentId: Long, body: String, titleId: Int): Boolean =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("body", body.trim()).toString()
            val (code, text) = executeV1Request(
                method = "PATCH",
                path = "/comments/$commentId",
                body = payload,
                refererPath = "/titles/$titleId"
            )
            if (code !in 200..299) {
                throw IOException(extractErrorMessage(code, text))
            }
            true
        }

    suspend fun deleteComment(commentId: Long, titleId: Int): Boolean =
        withContext(Dispatchers.IO) {
            val (code, text) = executeV1Request(
                method = "DELETE",
                path = "/comments/$commentId",
                refererPath = "/titles/$titleId"
            )
            if (code !in 200..299 && code != 404) {
                throw IOException(extractErrorMessage(code, text))
            }
            true
        }

    internal fun buildUpdatePayload(
        statusOption: WatchStatusOption? = null,
        favourite: Boolean? = null,
        progress: Int? = null,
        includeRatings: Boolean = false,
        rateTotal: Int? = null,
        rateStory: Int? = null,
        rateGraphic: Int? = null,
        rateMusic: Int? = null,
        rateCharacters: Int? = null
    ): JSONObject {
        val payload = JSONObject()
        if (statusOption != null) {
            payload.put("status", statusOption.apiValue ?: JSONObject.NULL)
            if (statusOption == WatchStatusOption.PLANNED) {
                payload.put("priority", 0)
            }
        }
        if (favourite != null) {
            payload.put("favourite", favourite)
        }
        if (progress != null) {
            payload.put("progress", progress.coerceAtLeast(0))
        }
        if (includeRatings) {
            val rateObj = JSONObject()
                .put("total", rateTotal?.takeIf { it in 1..10 } ?: JSONObject.NULL)
                .put("story", rateStory?.takeIf { it in 1..10 } ?: JSONObject.NULL)
                .put("graphics", rateGraphic?.takeIf { it in 1..10 } ?: JSONObject.NULL)
                .put("music", rateMusic?.takeIf { it in 1..10 } ?: JSONObject.NULL)
                .put("characters", rateCharacters?.takeIf { it in 1..10 } ?: JSONObject.NULL)
            payload.put("rate", rateObj)
        }
        return payload
    }

    internal fun parseListEntryJson(json: JSONObject, fallbackTitleId: Int): RemoteListEntry {
        val rawStatus = if (json.isNull("status")) null else json.optString("status", "")
        val rateObj = json.optJSONObject("rate")
        return RemoteListEntry(
            titleId = json.optInt("titleId", fallbackTitleId),
            status = WatchStatusOption.normalizeCode(rawStatus),
            progress = json.optInt("progress", 0).coerceAtLeast(0),
            favourite = json.optBoolean("favourite", false),
            rateTotal = rateObj.optValidScore("total"),
            rateStory = rateObj.optValidScore("story"),
            rateGraphic = rateObj.optValidScore("graphics") ?: rateObj.optValidScore("graphic"),
            rateMusic = rateObj.optValidScore("music"),
            rateCharacters = rateObj.optValidScore("characters")
        )
    }

    internal fun JSONObject?.optValidScore(key: String): Int? {
        if (this == null || isNull(key)) return null
        val v = optInt(key, 0).takeIf { it > 0 }
            ?: optString(key, "").toIntOrNull()
        return v?.takeIf { it in 1..10 }
    }

    internal fun parseCommentsHtml(html: String, animeId: Int): CommentsPage {
        if (html.isBlank()) return CommentsPage(emptyList())
        val doc = Jsoup.parseBodyFragment(html, ShindenUrls.BASE)
        val listEl = doc.selectFirst(".sh-c-list")
        val hasMore = listEl?.attr("data-more") == "1"
        val lastId = listEl?.attr("data-last")?.toLongOrNull()

        val result = mutableListOf<AnimeComment>()
        val threads = doc.select("article.sh-c-thread")
        if (threads.isNotEmpty()) {
            for (thread in threads) {
                val rootId = thread.attr("data-id").toLongOrNull()
                val rootCommentEl = thread.selectFirst("> .sh-c")
                    ?: thread.selectFirst(".sh-c:not(.sh-c-reply)")
                if (rootCommentEl != null) {
                    parseSingleCommentElement(rootCommentEl, animeId, parentId = null)?.let {
                        result.add(it)
                    }
                }
                val replyEls = thread.select(".sh-c-replies > .sh-c, .sh-c.sh-c-reply")
                for (replyEl in replyEls) {
                    parseSingleCommentElement(replyEl, animeId, parentId = rootId)?.let {
                        if (result.none { existing -> existing.id == it.id }) {
                            result.add(it)
                        }
                    }
                }
            }
        } else {
            for (el in doc.select(".sh-c[data-id]")) {
                val isReply = el.hasClass("sh-c-reply")
                val parentId = if (isReply) {
                    el.parents().firstOrNull { it.tagName() == "article" }?.attr("data-id")?.toLongOrNull()
                } else null
                parseSingleCommentElement(el, animeId, parentId)?.let { result.add(it) }
            }
        }
        return CommentsPage(
            comments = result,
            hasMore = hasMore,
            lastId = lastId
        )
    }

    private fun parseSingleCommentElement(
        el: Element,
        animeId: Int,
        parentId: Long?
    ): AnimeComment? {
        val id = el.attr("data-id").toLongOrNull() ?: return null
        val authorId = el.attr("data-author").toIntOrNull() ?: 0
        val authorName = el.attr("data-nick").trim().ifBlank {
            el.selectFirst(".sh-c-user")?.text()?.trim().orEmpty()
        }.ifBlank { "Użytkownik" }
        val createdRaw = el.selectFirst(".sh-c-time time")?.text()?.trim()
            .takeUnless { it.isNullOrBlank() }
            ?: el.attr("data-created").trim()
        val edited = el.selectFirst(".sh-c-edited") != null
        val likes = el.attr("data-likes").toIntOrNull() ?: 0
        val rank = el.selectFirst(".sh-c-rank")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val avatarImg = el.selectFirst(".sh-c-avatar img")
        val avatarSrc = sequenceOf(avatarImg?.attr("src"), avatarImg?.attr("data-src"))
            .firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
        val avatarUrl = when {
            avatarSrc.isBlank() -> null
            avatarSrc.startsWith("http://") || avatarSrc.startsWith("https://") -> avatarSrc
            avatarSrc.startsWith("//") -> "https:$avatarSrc"
            avatarSrc.startsWith("/") -> "${ShindenUrls.BASE}$avatarSrc"
            else -> "${ShindenUrls.BASE}/$avatarSrc"
        }

        val bodyEl = el.selectFirst(".sh-c-body")?.clone()
        val bodyText = if (bodyEl != null) {
            bodyEl.select("br").append("\\n")
            bodyEl.select("p").prepend("\\n")
            bodyEl.select("fieldset.quote").forEach { quote ->
                val legend = quote.selectFirst("legend")?.text()?.trim().orEmpty()
                quote.selectFirst("legend")?.remove()
                val quoted = quote.text().replace("\\n", "\n").trim()
                val formatted = if (legend.isNotBlank()) "„$quoted” ($legend)\n" else "„$quoted”\n"
                quote.text(formatted)
            }
            bodyEl.text()
                .replace("\\n", "\n")
                .lines()
                .joinToString("\n") { it.trimEnd() }
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        } else {
            ""
        }
        if (bodyText.isBlank()) return null

        return AnimeComment(
            id = id,
            animeId = animeId,
            authorId = authorId,
            authorName = authorName,
            avatarUrl = avatarUrl,
            rank = rank,
            body = bodyText,
            createdAt = createdRaw,
            edited = edited,
            likes = likes,
            parentId = parentId,
            isLocalOnly = false
        )
    }

    private fun executeV1Request(
        method: String,
        path: String,
        body: String? = null,
        refererPath: String = "/",
        extraCookie: String? = null,
        retriedAuth: Boolean = false
    ): Pair<Int, String> {
        val url = URL("$V1_BASE$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
            setHttpMethod(this, method)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7")
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Origin", ShindenUrls.BASE)
            setRequestProperty("Referer", "${ShindenUrls.BASE}$refererPath")
            ShindenAuth.attachSessionCookies(this, extraCookie)
            ShindenAuth.attachV1Auth(this, forceRefresh = retriedAuth)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) {
                conn.outputStream.use { out ->
                    out.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            val code = conn.responseCode
            if (code == 401 && !retriedAuth) {
                conn.disconnect()
                ShindenAuth.invalidateV1Token()
                return executeV1Request(
                    method = method,
                    path = path,
                    body = body,
                    refererPath = refererPath,
                    extraCookie = extraCookie,
                    retriedAuth = true
                )
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            code to responseText
        } finally {
            conn.disconnect()
        }
    }

    // shortcut: HttpURLConnection nie obsługuje PATCH przez setRequestMethod; po zmianie klienta HTTP usuń refleksję.
    private fun setHttpMethod(conn: HttpURLConnection, method: String) {
        if (method != "PATCH") {
            conn.requestMethod = method
            return
        }
        try {
            conn.requestMethod = method
        } catch (_: ProtocolException) {
            conn.requestMethod = "POST"
            conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            var current: Class<*>? = conn.javaClass
            while (current != null) {
                try {
                    val delegateField = current.getDeclaredField("delegate")
                    delegateField.isAccessible = true
                    val delegate = delegateField.get(conn)
                    if (delegate is HttpURLConnection) {
                        setHttpMethod(delegate, method)
                    }
                } catch (_: Exception) {
                }
                try {
                    val methodField = current.getDeclaredField("method")
                    methodField.isAccessible = true
                    methodField.set(conn, method)
                    break
                } catch (_: NoSuchFieldException) {
                    current = current.superclass
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun fetchCommentsFragmentHtml(startUrl: String, referer: String): String {
        var current = URL(startUrl)
        var extraCookie: String? = null
        repeat(4) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7")
                setRequestProperty("Referer", referer)
                ShindenAuth.attachSessionCookies(this, extraCookie)
            }
            try {
                val code = conn.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    val loc = conn.getHeaderField("Location")?.trim().orEmpty()
                    val setCookie = conn.headerFields["Set-Cookie"]
                        ?.mapNotNull { it.substringBefore(';').trim().takeIf(String::isNotBlank) }
                        ?.joinToString("; ")
                    if (!setCookie.isNullOrBlank()) extraCookie = setCookie
                    if (loc.isNotBlank()) {
                        val next = URL(current, loc)
                        if (next.protocol == "https" &&
                            (next.host.equals(ShindenUrls.HOST, true) || next.host.endsWith(".${ShindenUrls.HOST}", true))
                        ) {
                            current = next
                            return@repeat
                        }
                    }
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Zbyt wiele przekierowań dla komentarzy")
    }

    private fun extractErrorMessage(code: Int, text: String): String {
        val json = runCatching { JSONObject(text) }.getOrNull()
        val errObj = json?.optJSONObject("error")
        val errCode = errObj?.optString("code")?.ifBlank { null }
            ?: json?.optString("code")?.ifBlank { null }
        val errMsg = errObj?.optString("message")?.ifBlank { null }
            ?: json?.optString("message")?.ifBlank { null }
        return when (errCode) {
            "favourites_limit" -> "Osiągnięto limit ulubionych na koncie Shinden."
            "captcha_required", "captcha_invalid" -> "Shinden wymaga weryfikacji zabezpieczeń (Cloudflare)."
            "too_fast", "rate_limit" -> "Zbyt wiele żądań w krótkim czasie. Odczekaj chwilę."
            "unauthorized", "unauthenticated" -> "Sesja Shinden wygasła. Zaloguj się ponownie."
            "forbidden" -> "Brak uprawnień do wykonania tej operacji na Shinden."
            else -> errMsg ?: "Błąd komunikacji z Shinden (HTTP $code)."
        }
    }
}
