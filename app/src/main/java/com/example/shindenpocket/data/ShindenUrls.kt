package com.example.shindenpocket.data

import java.net.URL

/** Wspólne reguły adresów `/titles` i `/series` oraz odcinków `/episode` i `/epek`. */
internal object ShindenUrls {
    const val BASE = "https://shinden.pl"
    const val HOST = "shinden.pl"

    private const val SLUG = "(?:-[a-z0-9-]+)?"

    /** Rozpoznaje link anime w formacie `/titles` lub `/series`. */
    val titleLink = Regex("^/(?:titles|series)/([1-9][0-9]*)$SLUG/?$")

    /** Rozpoznaje link odcinka w formacie `/episode` lub `/epek`. */
    val episodeLink = Regex("^/(?:episode|epek)/([1-9][0-9]*)$SLUG/view/([1-9][0-9]*)/?$")

    fun titlePath(titleId: Int): Regex = Regex("^/(?:titles|series)/$titleId$SLUG/?$")

    fun episodesPath(titleId: Int): Regex =
        Regex("^/(?:titles|series)/$titleId$SLUG/episodes/?$")

    fun titleUrl(titleId: Int): String = "$BASE/titles/$titleId"

    fun episodesUrl(titleId: Int): String = "$BASE/titles/$titleId/episodes"

    // shortcut: Shinden serwuje awatary wg stałej ścieżki 36x48/{userId}.jpg;
    // gdyby magazyn się zmienił, trzeba wrócić do adresu wyciąganego z HTML.
    fun avatarUrl(userId: Int): String = "$BASE/res/avatars/36x48/$userId.jpg"

    /** Zwraca ścieżkę bezpiecznego linku wewnętrznego Shinden albo `null`. */
    fun pathOf(raw: String): String? = try {
        val url = URL(URL("$BASE/"), raw.trim())
        if (url.protocol == "https" && url.host.equals(HOST, true) &&
            url.port == -1 && url.query == null && url.ref == null
        ) url.path else null
    } catch (_: Exception) {
        null
    }

    fun isTitleDetailUrl(raw: String, titleId: Int): Boolean = try {
        val url = URL(raw)
        url.protocol == "https" && url.host.equals(HOST, true) && url.port == -1 &&
            url.query == null && url.ref == null && titlePath(titleId).matches(url.path)
    } catch (_: Exception) {
        false
    }
}
