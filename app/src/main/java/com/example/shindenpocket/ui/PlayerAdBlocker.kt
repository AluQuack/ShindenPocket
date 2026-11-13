package com.example.shindenpocket.ui

import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale

/** Domeny reklamowe i trackingowe blokowane na poziomie żądań sieciowych WebView. */
internal val adHosts: List<String> = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "googletagservices.com",
    "adservice.google.com", "2mdn.net", "moatads.com", "amazon-adsystem.com",
    "adnxs.com", "adform.net", "criteo.com", "criteo.net", "pubmatic.com",
    "rubiconproject.com", "openx.net", "smartadserver.com", "casalemedia.com",
    "taboola.com", "outbrain.com", "mgid.com", "revcontent.com",
    "popads.net", "popcash.net", "propellerads.com", "propellerclick.com",
    "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.com", "trafficfactory.biz",
    "adsterra.com", "adcash.com", "clickadu.com", "hilltopads.net", "hilltopads.com",
    "onclickads.net", "onclkds.com", "a-ads.com", "ad-maven.com", "admaven.com",
    "richads.com", "pushance.com", "profitabledisplaynetwork.com",
    "highperformanceformat.com", "effectivegatecpm.com", "effectiveratecpm.com",
    "cpmstar.com", "yllix.com", "tsyndicate.com", "bidvertiser.com", "adsco.re",
    "monetag.com", "tzegilo.com", "acscdn.com", "dtscout.com", "dtscdn.com",
    "bebi.com", "adskeeper.com", "adspyglass.com", "ero-advertising.com", "ads.yahoo.com",
    // Reklamy i sondy blokady reklam osadzane bezpośrednio na stronie odcinka Shindena.
    "spolecznosci.net", "reklama.shinden.eu", "jenonaw.com", "velocecdn.com",
    "visariomedia.com", "gmyyuqkubaycjc.com", "dxuyhdxnr.com", "llvpn.com",
    // Skrypt, który podmienia dokument odtwarzacza na stronę przekierowania (meta refresh).
    "aqpkokeeyyncfl.com"
)

private val adHostSet: Set<String> = adHosts.toHashSet()

private val emptyBytes = ByteArray(0)

private val emptyHtmlBytes: ByteArray =
    "<!doctype html><html><head><meta charset=\"utf-8\"></head><body></body></html>"
        .toByteArray(Charsets.UTF_8)

private val emptyJsonBytes: ByteArray =
    "{}".toByteArray(Charsets.UTF_8)

private val emptyXmlBytes: ByteArray =
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><VAST version=\"3.0\"/>"
        .toByteArray(Charsets.UTF_8)

private val emptySvgBytes: ByteArray =
    "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1\" height=\"1\"/>"
        .toByteArray(Charsets.UTF_8)

private val transparentGif: ByteArray by lazy {
    Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT)
}

internal enum class AdRequestContext {
    SCRIPT,
    STYLE,
    HTML_DOCUMENT,
    SVG_IMAGE,
    RASTER_IMAGE,
    JSON,
    XML,
    MEDIA_OR_FONT,
    EMPTY
}

/**
 * Sprawdza, czy podany host należy do listy domen reklamowych (uwzględniając subdomeny).
 * Złożoność oparta o przeszukiwanie poziomów domeny w HashSet bez alokacji prefiksów.
 */
internal fun isAdHost(host: String): Boolean {
    val normalized = host.trim().trimEnd('.').lowercase(Locale.ROOT)
    if (normalized.isEmpty()) return false
    if (normalized == "shinden.pl" || normalized.endsWith(".shinden.pl")) return false

    if (normalized in adHostSet) return true
    var dotIndex = normalized.indexOf('.')
    while (dotIndex != -1 && dotIndex + 1 < normalized.length) {
        val parentDomain = normalized.substring(dotIndex + 1)
        if (parentDomain in adHostSet) return true
        dotIndex = normalized.indexOf('.', dotIndex + 1)
    }
    return false
}

/**
 * Przechwytuje wyłącznie żądania kierowane do domen reklamowych.
 * Dla pozostałych żądań zwraca `null`, pozostawiając natywną obsługę ciasteczek,
 * przekierowań HTTP, strumieni wideo oraz ramek iframe po stronie silnika WebView.
 */
internal fun shouldInterceptAdRequest(request: WebResourceRequest): WebResourceResponse? {
    val scheme = request.url?.scheme?.lowercase(Locale.ROOT) ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = request.url?.host.orEmpty()
    if (!isAdHost(host)) return null
    return createBlockedAdResponse(request)
}

/**
 * Tworzy neutralną technicznie odpowiedź dla zablokowanego zasobu reklamowego,
 * dopasowaną do kontekstu żądania (`Sec-Fetch-Dest`, `Accept`, ścieżka URL) z poprawnym
 * typem MIME i kodowaniem, bez wstrzykiwania fałszywych skryptów SDK czy udawania odsłon.
 */
internal fun createBlockedAdResponse(request: WebResourceRequest): WebResourceResponse {
    val headers = buildCorsHeaders(request)
    if (request.method.equals("OPTIONS", ignoreCase = true)) {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            204,
            "No Content",
            headers,
            ByteArrayInputStream(emptyBytes)
        )
    }

    return when (resolveAdRequestContext(request)) {
        AdRequestContext.SCRIPT -> WebResourceResponse(
            "text/javascript",
            "UTF-8",
            200,
            "OK",
            headers,
            ByteArrayInputStream(emptyBytes)
        )
        AdRequestContext.STYLE -> WebResourceResponse(
            "text/css",
            "UTF-8",
            200,
            "OK",
            headers,
            ByteArrayInputStream(emptyBytes)
        )
        AdRequestContext.HTML_DOCUMENT -> WebResourceResponse(
            "text/html",
            "UTF-8",
            200,
            "OK",
            headers,
            ByteArrayInputStream(emptyHtmlBytes)
        )
        AdRequestContext.SVG_IMAGE -> WebResourceResponse(
            "image/svg+xml",
            "UTF-8",
            200,
            "OK",
            headers,
            ByteArrayInputStream(emptySvgBytes)
        )
        AdRequestContext.RASTER_IMAGE -> WebResourceResponse(
            "image/gif",
            null,
            200,
            "OK",
            headers,
            ByteArrayInputStream(transparentGif)
        )
        AdRequestContext.JSON -> WebResourceResponse(
            "application/json",
            "UTF-8",
            200,
            "OK",
            headers,
            ByteArrayInputStream(emptyJsonBytes)
        )
        AdRequestContext.XML -> WebResourceResponse(
            "application/xml",
            "UTF-8",
            200,
            "OK",
            headers,
            ByteArrayInputStream(emptyXmlBytes)
        )
        AdRequestContext.MEDIA_OR_FONT -> WebResourceResponse(
            "text/plain",
            "UTF-8",
            204,
            "No Content",
            headers,
            ByteArrayInputStream(emptyBytes)
        )
        AdRequestContext.EMPTY -> WebResourceResponse(
            "text/plain",
            "UTF-8",
            200,
            "OK",
            headers,
            ByteArrayInputStream(emptyBytes)
        )
    }
}

internal fun resolveAdRequestContext(request: WebResourceRequest): AdRequestContext {
    val path = request.url?.path.orEmpty().lowercase(Locale.ROOT)
    val fetchDest = request.headerValue("Sec-Fetch-Dest").orEmpty().lowercase(Locale.ROOT)
    val accept = request.headerValue("Accept").orEmpty().lowercase(Locale.ROOT)

    return resolveAdRequestContext(
        path = path,
        fetchDest = fetchDest,
        accept = accept,
        isForMainFrame = request.isForMainFrame
    )
}

internal fun resolveAdRequestContext(
    path: String,
    fetchDest: String,
    accept: String,
    isForMainFrame: Boolean
): AdRequestContext {
    when (fetchDest) {
        "script", "worker", "sharedworker", "serviceworker", "audioworklet", "paintworklet" ->
            return AdRequestContext.SCRIPT
        "style" ->
            return AdRequestContext.STYLE
        "iframe", "frame", "subframe", "document", "fencedframe" ->
            return AdRequestContext.HTML_DOCUMENT
        "image" ->
            return if (path.endsWith(".svg") || accept.contains("image/svg+xml")) {
                AdRequestContext.SVG_IMAGE
            } else {
                AdRequestContext.RASTER_IMAGE
            }
        "video", "audio", "track", "font" ->
            return AdRequestContext.MEDIA_OR_FONT
    }

    if (isForMainFrame) {
        return AdRequestContext.HTML_DOCUMENT
    }

    return when {
        path.endsWith(".js") || path.endsWith(".mjs") ||
                accept.contains("text/javascript") ||
                accept.contains("application/javascript") ||
                accept.contains("application/x-javascript") ||
                accept.contains("application/ecmascript") ->
            AdRequestContext.SCRIPT

        path.endsWith(".css") || accept.contains("text/css") ->
            AdRequestContext.STYLE

        path.endsWith(".svg") || accept.contains("image/svg+xml") ->
            AdRequestContext.SVG_IMAGE

        accept.startsWith("image/") || hasImageExtension(path) ->
            AdRequestContext.RASTER_IMAGE

        path.endsWith(".html") || path.endsWith(".htm") ||
                accept.contains("text/html") || accept.contains("application/xhtml+xml") ->
            AdRequestContext.HTML_DOCUMENT

        path.endsWith(".json") || accept.contains("application/json") ->
            AdRequestContext.JSON

        path.endsWith(".xml") || accept.contains("application/xml") || accept.contains("text/xml") ->
            AdRequestContext.XML

        hasMediaOrFontExtension(path) ->
            AdRequestContext.MEDIA_OR_FONT

        else ->
            AdRequestContext.EMPTY
    }
}

private fun hasImageExtension(path: String): Boolean =
    path.endsWith(".gif") ||
            path.endsWith(".png") ||
            path.endsWith(".jpg") ||
            path.endsWith(".jpeg") ||
            path.endsWith(".webp") ||
            path.endsWith(".ico") ||
            path.endsWith(".avif") ||
            path.endsWith(".bmp")

private fun hasMediaOrFontExtension(path: String): Boolean =
    path.endsWith(".mp4") ||
            path.endsWith(".webm") ||
            path.endsWith(".m3u8") ||
            path.endsWith(".mpd") ||
            path.endsWith(".mp3") ||
            path.endsWith(".ogg") ||
            path.endsWith(".woff2") ||
            path.endsWith(".woff") ||
            path.endsWith(".ttf") ||
            path.endsWith(".otf")

private fun buildCorsHeaders(request: WebResourceRequest): Map<String, String> {
    val origin = request.headerValue("Origin")?.takeIf { it.isNotBlank() && it != "null" }
    val requestedHeaders = request.headerValue("Access-Control-Request-Headers")
    return buildMap {
        put("Cache-Control", "no-store, no-cache, must-revalidate")
        put("X-Content-Type-Options", "nosniff")
        put("Timing-Allow-Origin", "*")
        if (origin != null) {
            put("Access-Control-Allow-Origin", origin)
            put("Access-Control-Allow-Credentials", "true")
            put("Vary", "Origin")
        } else {
            put("Access-Control-Allow-Origin", "*")
        }
        put("Access-Control-Allow-Methods", "GET, HEAD, POST, OPTIONS")
        if (!requestedHeaders.isNullOrBlank()) {
            put("Access-Control-Allow-Headers", requestedHeaders)
        } else {
            put("Access-Control-Allow-Headers", "*")
        }
    }
}

private fun WebResourceRequest.headerValue(name: String): String? {
    val headers = requestHeaders ?: return null
    headers[name]?.let { return it }
    for ((key, value) in headers) {
        if (key != null && key.equals(name, ignoreCase = true)) {
            return value
        }
    }
    return null
}
