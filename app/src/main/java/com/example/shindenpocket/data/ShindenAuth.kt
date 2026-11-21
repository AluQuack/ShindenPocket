package com.example.shindenpocket.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager as WebCookieManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64

internal object ShindenHttpSecurity {
    fun isTrusted(uri: URI): Boolean =
        !uri.isOpaque &&
            uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(ShindenUrls.HOST, ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawUserInfo == null

    fun requireTrusted(url: URL): URI {
        val uri = try {
            url.toURI()
        } catch (e: Exception) {
            throw IOException("Nieprawidłowy adres Shinden.", e)
        }
        if (!isTrusted(uri)) {
            throw IOException("Odrzucono niezaufany adres Shinden.")
        }
        return uri
    }

    fun resolveRedirect(current: URL, location: String): URL {
        if (location.isBlank()) throw IOException("Przekierowanie bez Location.")
        val next = try {
            URL(current, location)
        } catch (e: Exception) {
            throw IOException("Nieprawidłowy adres przekierowania.", e)
        }
        requireTrusted(next)
        return next
    }
}

internal class ShindenCookieManager : CookieManager(
    null,
    CookiePolicy.ACCEPT_ORIGINAL_SERVER
) {
    override fun get(
        uri: URI,
        requestHeaders: MutableMap<String, MutableList<String>>
    ): MutableMap<String, MutableList<String>> {
        if (!ShindenHttpSecurity.isTrusted(uri)) return mutableMapOf()

        // Nie dokładaj automatycznie drugiego Cookie, jeśli żądanie
        // ma już jawnie ustawiony nagłówek (np. potwierdzenie biletu).
        if (requestHeaders.keys.any { it.equals("Cookie", ignoreCase = true) }) {
            return mutableMapOf()
        }

        return super.get(uri, requestHeaders)
    }

    override fun put(
        uri: URI,
        responseHeaders: MutableMap<String, MutableList<String>>
    ) {
        if (ShindenHttpSecurity.isTrusted(uri)) {
            super.put(uri, responseHeaders)
        }
    }
}

object ShindenAuth {

    private const val LOGIN_URL = "https://shinden.pl/main/login"
    private const val AUTH_URL = "https://shinden.pl/v1/auth/login"
    private const val TOKEN_URL = "https://shinden.pl/api/user/jwt"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val cookies = ShindenCookieManager()

    @Volatile private var cachedJwt: String? = null
    @Volatile private var cachedCookieUid: Int? = null
    @Volatile private var cachedTokenExpiresAt: Long = 0L

    sealed class LoginResult {
        data class Success(
            val username: String,
            val userId: Int
        ) : LoginResult()

        data class Error(val message: String) : LoginResult()
    }

    internal data class AuthenticatedUser(
        val username: String,
        val userId: Int
    )

    suspend fun login(
        context: Context,
        username: String,
        password: String
    ): LoginResult = withContext(Dispatchers.IO) {

        if (username.isBlank() || password.isBlank()) {
            return@withContext LoginResult.Error("Podaj login i hasło.")
        }

        installCookieHandler()
        clearLocalSession(context)

        try {
            // Pobranie strony logowania przygotowuje cookies, ale jego błąd nie blokuje żądania JSON.
            
            runCatching { getPage(LOGIN_URL) }

            // Logowanie JSON zwraca bilet, którego potwierdzenie ustawia cookies sesji.
            val ticket = postLogin(username, password)

            followTicket(ticket)

            val user = fetchAuthenticatedUser()
                ?: throw AuthException(
                    "Bilet został wykorzystany, ale Shinden nie potwierdził sesji."
                )

            // Trwale zapisujemy wyłącznie cookies, bez hasła i danych profilu.
            val cookiesToSave = cookies.cookieStore.cookies.filterNot(HttpCookie::hasExpired)
            SecureSessionStore.save(context, cookiesToSave)

            LoginResult.Success(
                username = user.username,
                userId = user.userId
            )
        } catch (e: AuthException) {
            fail(context, e.message ?: "Nie udało się zalogować.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(context, "Nie udało się zalogować: ${e.message ?: "Nieznany błąd"}")
        }
    }

    /** Czy na urządzeniu nadal istnieje zapisana sesja (bez jej weryfikacji). */
    fun hasSavedSession(context: Context): Boolean = SecureSessionStore.load(context).isNotEmpty()

    /**
     * Sesja zostaje uznana za aktywną dopiero po odpowiedzi z API.
     * Na błąd połączenia nie reagujemy usuwaniem zapisanych cookies.
     */
    suspend fun restoreSession(context: Context): LoginResult.Success? =
        withContext(Dispatchers.IO) {
            installCookieHandler()
            invalidateV1Token()
            cookies.cookieStore.removeAll()

            val savedCookies = SecureSessionStore.load(context)
            if (savedCookies.isEmpty()) return@withContext null

            val uri = URI("https://shinden.pl/")
            savedCookies.forEach { cookie ->
                val domain = cookie.domain.orEmpty().trimStart('.')
                if (!cookie.hasExpired() &&
                    (domain.isEmpty() || domain.equals(ShindenUrls.HOST, ignoreCase = true))
                ) {
                    cookies.cookieStore.add(uri, cookie)
                }
            }

            try {
                val user = fetchAuthenticatedUser()
                if (user == null) {
                    // Odpowiedź 401 z `api/user/jwt` oznacza, że sesji nie należy przywracać.
                    clearLocalSession(context)
                    null
                } else {
                    LoginResult.Success(user.username, user.userId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Błąd sieci, 403 lub nieoczekiwana odpowiedź serwera:
                // Niepewny stan nie potwierdza logowania, ale zachowuje zapis cookies.
                null
            }
        }

    /** Odpowiedź /api/user/jwt: albo dane użytkownika wprost, albo {"jwt": "..."}. */
    internal fun parseSessionResponse(jsonText: String): AuthenticatedUser? = try {
        val json = JSONObject(jsonText)

        val userId = json.optInt("uid", 0)
        val name = json.optString("username").trim()
        val jwt = json.optString("jwt").trim()
        val authenticatedUser = if (userId > 0 && name.isNotBlank() && name != "null") {
            AuthenticatedUser(username = name, userId = userId)
        } else {
            if (jwt.isEmpty() || jwt == "null") null else parseJwtUser(jwt)
        }

        // Nie przechowuj tokenu, gdy nie udało się potwierdzić użytkownika.
        if (authenticatedUser != null) {
            if (jwt.isNotEmpty() && jwt != "null") {
                cachedJwt = jwt
                cachedCookieUid = null
            } else {
                cachedJwt = null
                cachedCookieUid = authenticatedUser.userId
            }
            cachedTokenExpiresAt = System.currentTimeMillis() + 240_000L
        }
        authenticatedUser
    } catch (_: Exception) {
        null
    }

    @Synchronized
    internal fun installCookieHandler() {
        if (CookieHandler.getDefault() !== cookies) {
            CookieHandler.setDefault(cookies)
        }
    }

    internal fun openConnection(url: URL): HttpURLConnection {
        ShindenHttpSecurity.requireTrusted(url)
        installCookieHandler()
        return url.openConnection() as HttpURLConnection
    }

    /** Diagnostyka tylko nazw cookies, bez wartości, biletów i tokenów. */
    private fun cookieNamesForRequest(url: URL): List<String> {
        val uri = ShindenHttpSecurity.requireTrusted(url)
        val requestCookies = cookies.get(
            uri,
            mutableMapOf<String, MutableList<String>>()
        )
        return requestCookies.entries
            .filter { it.key.equals("Cookie", ignoreCase = true) }
            .flatMap { it.value }
            .flatMap { it.split(';') }
            .map { it.substringBefore('=').trim() }
            .filter { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
            .distinct()
            .sorted()
    }

    /** Nazwy i zakresy ciastek w magazynie, bez ich wartości. */
    private fun cookieScopes(): List<String> = cookies.cookieStore.cookies
        .filterNot(HttpCookie::hasExpired)
        .map { cookie ->
            val name = cookie.name.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: "?"
            val domain = cookie.domain.orEmpty().take(80).replace(Regex("[^A-Za-z0-9.-]"), "?")
            val path = cookie.path.orEmpty().take(80).replace(Regex("[^A-Za-z0-9_./-]"), "?")
            "$name(domain=$domain,path=$path,secure=${cookie.secure})"
        }.sorted()

    /** Kod błędu z JSON, bez logowania niebezpiecznej treści odpowiedzi. */
    private fun errorCodeOnly(body: String): String? = try {
        val json = JSONObject(body)
        val code = json.optJSONObject("error")?.optString("code")
            ?.ifBlank { null } ?: json.optString("code").ifBlank { null }
        code?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) }
    } catch (_: Exception) {
        null
    }

    internal fun setShLvCookie(url: URL, value: Long) {
        val uri = ShindenHttpSecurity.requireTrusted(url)
        val cookie = HttpCookie("sh_lv", value.toString()).apply {
            domain = ShindenUrls.HOST
            path = "/"
            secure = true
        }
        cookies.cookieStore.add(uri, cookie)
    }

    /**
     * UID to identyfikator, a nie sekret. Wysyłamy go wyłącznie w trybie
     * cookies-session, po potwierdzeniu sesji przez /api/user/jwt i tylko
     * razem z aktywnym cookie Shinden. Uprawnienia musi sprawdzać serwer.
     */
    private fun hasAuthenticatedSessionCookie(): Boolean = cookies.cookieStore.cookies.any { cookie ->
        cookie.name == "sess_shinden" &&
            !cookie.hasExpired() &&
            cookie.secure &&
            cookie.domain.orEmpty().trimStart('.').equals(ShindenUrls.HOST, ignoreCase = true)
    }

    internal fun attachV1Auth(connection: HttpURLConnection, forceRefresh: Boolean = false) {
        ShindenHttpSecurity.requireTrusted(connection.url)
        val now = System.currentTimeMillis()
        if (forceRefresh || now >= cachedTokenExpiresAt ||
            (cachedJwt == null && cachedCookieUid == null)
        ) {
            // Nie wysyłamy starego tokenu, jeżeli odświeżenie się nie powiedzie.
            invalidateV1Token()
            fetchAuthenticatedUser()
        }

        val jwt = cachedJwt
        val uid = cachedCookieUid
        if (!jwt.isNullOrBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $jwt")
        } else if (uid != null && uid > 0 && hasAuthenticatedSessionCookie()) {
            // Kompatybilność z mechanizmem Shinden: UID sam w sobie niczego nie uwierzytelnia.
            connection.setRequestProperty("X-Sh-Auth", uid.toString())
        }
    }

    internal fun invalidateV1Token() {
        cachedJwt = null
        cachedCookieUid = null
        cachedTokenExpiresAt = 0L
    }

    /**
     * `GET /api/user/jwt` potwierdza sesję: 200 zwraca JWT z `uid` i nazwą,
     * 401 oznacza brak logowania; 403/404 i błędy sieci pozostawiają stan niepewny.
     */
    private fun fetchAuthenticatedUser(): AuthenticatedUser? {
        var current = URL(TOKEN_URL)
        repeat(5) {
            val connection = openConnection(current)
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", BROWSER_UA)
                connection.setRequestProperty("Referer", "https://shinden.pl/")

                val code = connection.responseCode
                Log.w("ShindenAuth", "user/jwt -> HTTP $code")
                if (code in REDIRECT_CODES) {
                    current = ShindenHttpSecurity.resolveRedirect(
                        current,
                        connection.getHeaderField("Location").orEmpty()
                    )
                    return@repeat
                }

                return when (code) {
                    401 -> null
                    404 -> throw IOException("Endpoint sprawdzania sesji jest niedostępny (HTTP 404).")
                    in 200..299 -> {
                        val jsonText = connection.inputStream
                            .bufferedReader(Charsets.UTF_8)
                            .use { it.readText() }
                        parseSessionResponse(jsonText)
                            ?: throw IOException("Nieoczekiwana odpowiedź API sprawdzającego sesję.")
                    }
                    else -> {
                        logHttp("user/jwt", code, null)
                        throw IOException("Sprawdzenie sesji: HTTP $code")
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Zbyt wiele przekierowań podczas sprawdzania sesji.")
    }

    /** Odczytuje `uid` i nazwę z JWT bez używania tokenu jako dowodu autentyczności. */
    internal fun parseJwtUser(jwt: String): AuthenticatedUser? = try {
        val parts = jwt.split('.')
        val payload = parts.getOrNull(1) ?: return null

        // Uzupełnienie paddingu jest wymagane przez dekoder Base64URL.
        val remainder = payload.length % 4
        val paddingLength = if (remainder > 0) 4 - remainder else 0
        val padded = payload + "=".repeat(paddingLength)

        val bytes = Base64.getUrlDecoder().decode(padded)
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val userId = json.optInt("uid", 0).takeIf { it > 0 }
            ?: json.optString("sub").toIntOrNull()?.takeIf { it > 0 }
            ?: return null
        val name = json.optString("username").trim()
            .ifBlank { json.optString("name").trim() }
        if (name.isBlank() || name == "null") null
        else AuthenticatedUser(username = name, userId = userId)
    } catch (_: Exception) {
        null
    }

    private fun getPage(url: String): String {
        var current = URL(url)
        repeat(5) {
            val connection = openConnection(current)
            try {
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", BROWSER_UA)
                connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
                connection.setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9")
                val code = connection.responseCode
                Log.w("ShindenAuth", "main/login -> HTTP $code, cookies=${cookieNamesForRequest(current)}")
                if (code in REDIRECT_CODES) {
                    current = ShindenHttpSecurity.resolveRedirect(
                        current,
                        connection.getHeaderField("Location").orEmpty()
                    )
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                return connection.inputStream.bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Zbyt wiele przekierowań strony logowania.")
    }

    /** `POST /v1/auth/login` zwraca przekierowanie do biletu ustawiającego cookies sesji. */
    private fun postLogin(username: String, password: String): String {
        val payload = JSONObject()
            .put("login", username.trim())
            .put("password", password)
            .put("remember", true)
            .put("captcha", JSONObject.NULL)
            // Pole `return` przyjmuje ścieżkę, a nie pełny adres.
            .put("return", "/")
            .toString()

        var current = URL(AUTH_URL)
        repeat(5) {
            val connection = openConnection(current)
            try {
                Log.w("ShindenAuth", "auth/login cookies przed=${cookieNamesForRequest(current)}")
                connection.requestMethod = "POST"
                connection.instanceFollowRedirects = false
                connection.doOutput = true
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "*/*")
                connection.setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9,en-US;q=0.8,en;q=0.7")
                connection.setRequestProperty("Cache-Control", "no-cache")
                connection.setRequestProperty("Pragma", "no-cache")
                connection.setRequestProperty("Origin", "https://shinden.pl")
                connection.setRequestProperty("Referer", "https://shinden.pl/main/login?return=%2Fmain")
                connection.setRequestProperty("User-Agent", BROWSER_UA)

                connection.outputStream.use {
                    it.write(payload.toByteArray(Charsets.UTF_8))
                }

                val code = connection.responseCode
                Log.w("ShindenAuth", "auth/login -> HTTP $code, cookies po=${cookieNamesForRequest(current)}")
                Log.w("ShindenAuth", "auth/login zakres cookies=${cookieScopes()}")
                if (code in REDIRECT_CODES) {
                    val next = ShindenHttpSecurity.resolveRedirect(
                        current,
                        connection.getHeaderField("Location").orEmpty()
                    )
                    if (code == 307 || code == 308) {
                        current = next
                        return@repeat
                    }
                    throw AuthException("Nieoczekiwane przekierowanie podczas logowania.")
                }
                // Diagnostyka zawiera wyłącznie nazwy cookies, nigdy ich wartości.
                val cookieNames = connection.headerFields
                    .filterKeys { it?.equals("Set-Cookie", true) == true }
                    .values.flatten()
                    .mapNotNull { it.substringBefore('=').trim().ifBlank { null } }
                    .distinct()
                if (cookieNames.isNotEmpty()) {
                    Log.w("ShindenAuth", "auth/login set-cookie: $cookieNames")
                }
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) logHttp("auth/login", code, text)

                if (code !in 200..299) throw AuthException(describeAuthError(code, text))

                val redirect = JSONObject(text).optString("redirect").trim()
                if (redirect.isEmpty()) {
                    throw AuthException("Shinden nie potwierdził logowania. Spróbuj ponownie.")
                }
                return redirect
            } finally {
                connection.disconnect()
            }
        }
        throw AuthException("Zbyt wiele przekierowań podczas logowania.")
    }

    private fun followTicket(redirect: String) {
        val cleanRedirect = redirect.replace("&amp;", "&")
        val ticketUrl = URL(URL("https://shinden.pl/"), cleanRedirect)

        // Bilet wolno wykorzystać wyłącznie przez HTTPS na głównym hoście Shindena.
        ShindenHttpSecurity.requireTrusted(ticketUrl)
        if (!ticketUrl.path.startsWith("/login/ticket")) {
            throw AuthException("Shinden zwrócił nieoczekiwany adres potwierdzenia logowania.")
        }

        val connection = openConnection(ticketUrl)
        try {
            Log.w("ShindenAuth", "login/ticket cookies przed=${cookieNamesForRequest(ticketUrl)}")
            Log.w("ShindenAuth", "login/ticket zakres cookies=${cookieScopes()}")
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", BROWSER_UA)
            connection.setRequestProperty(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            connection.setRequestProperty(
                "Referer",
                "https://shinden.pl/main/login?return=%2Fmain"
            )

            // Przywróć sprawdzony format Cookie: name=value; name2=value2.
            // Tylko dla HTTPS shinden.pl i tylko dla jednorazowego ticketu.
            // Pozostałe żądania nadal korzystają z CookieManager z Path/Secure.
            val ticketCookies = cookies.cookieStore.get(ticketUrl.toURI())
                .filter { cookie ->
                    val domain = cookie.domain.orEmpty().trimStart('.')
                    val path = cookie.path ?: "/"
                    !cookie.hasExpired() &&
                        (domain.isBlank() || domain.equals(ShindenUrls.HOST, ignoreCase = true)) &&
                        (path == "/" || ticketUrl.path == path ||
                            (path.endsWith("/") && ticketUrl.path.startsWith(path)) ||
                            ticketUrl.path.startsWith("$path/" ) ||
                            // shortcut: WAF wymaga _rnd także poza Path=/main/; usuń wyjątek, gdy Shinden ustawi Path=/.
                            (cookie.name == "_rnd" && path == "/main/")) &&
                        cookie.name.matches(Regex("[A-Za-z0-9_-]+")) &&
                        cookie.value.none { it == '\r' || it == '\n' || it == ';' }
                }
            val header = ticketCookies.joinToString("; ") { "${it.name}=${it.value}" }
            if (header.isNotEmpty()) {
                connection.setRequestProperty("Cookie", header)
            }
            Log.w("ShindenAuth", "login/ticket jawne cookies=${ticketCookies.map { it.name }}")

            val code = connection.responseCode

            // Zapisz cookies ustawione podczas wykorzystania biletu, także przy HTTP 302.
            // CookieManager sprawdza ich Domain i Path; nie dodajemy ich wprost do CookieStore.
            // Niektóre implementacje HttpURLConnection robią to automatycznie, ale
            // ponowne przetworzenie nagłówka Set-Cookie jest bezpieczne.
            cookies.put(ticketUrl.toURI(), connection.headerFields)

            Log.w(
                "ShindenAuth",
                "login/ticket -> HTTP $code, liczba cookies=" +
                    cookies.cookieStore.cookies.count { !it.hasExpired() }
            )

            if (code == 400) {
                val serverCode = connection.errorStream
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText().take(8192) }
                    ?.let(::errorCodeOnly)
                Log.w(
                    "ShindenAuth",
                    "login/ticket errorCode=${serverCode ?: "brak"}, " +
                        "contentType=${connection.contentType?.substringBefore(';') ?: "brak"}"
                )
            }

            when {
                code in 200..299 -> return
                code in REDIRECT_CODES -> {
                    // Ticket bywa potwierdzany przez HTTP 302. Nie pobieramy strony
                    // docelowej: może być blokowana przez WAF niezależnie od logowania.
                    // Weryfikacja sesji nastąpi w fetchAuthenticatedUser().
                    val location = connection.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        ShindenHttpSecurity.resolveRedirect(ticketUrl, location)
                    }
                    return
                }
                else -> throw AuthException(
                    "Bilet logowania wygasł lub został użyty (HTTP $code). Spróbuj ponownie."
                )
            }
        } finally {
            connection.disconnect()
        }
    }


    private fun describeAuthError(code: Int, text: String): String {
        val error = try { JSONObject(text).optJSONObject("error") } catch (_: Exception) { null }
        val message = error?.optString("message").orEmpty().trim()
        return when (error?.optString("code")) {
            "invalid_credentials" -> "Zły login lub hasło."
            "invalid_input" -> "Podaj poprawny login lub e-mail i hasło."
            "captcha_required", "captcha_invalid", "captcha_unavailable" ->
                "Shinden wymaga weryfikacji Cloudflare. Spróbuj ponownie za chwilę."
            "too_fast" -> "Za dużo prób logowania. Odczekaj chwilę i spróbuj ponownie."
            "busy" -> "Shinden jest teraz zajęty. Spróbuj ponownie za chwilę."
            "login_disabled" -> "Logowanie na tym koncie jest wyłączone."
            "banned" -> "To konto zostało zablokowane przez Shinden."
            "network" -> "Błąd połączenia z Shinden. Sprawdź internet i spróbuj ponownie."
            "login_unavailable" -> "Logowanie jest teraz niedostępne. Spróbuj później."
            else -> message.ifBlank { "Nie udało się zalogować (HTTP $code)." }
        }
    }

    /** Loguj tylko kod HTTP i niejawny kod błędu z JSON, nigdy odpowiedź ani token. */
    private fun logHttp(step: String, code: Int, body: String?) {
        Log.w("ShindenAuth", "$step -> HTTP $code, errorCode=${body?.let(::errorCodeOnly) ?: "brak"}")
    }

    private class AuthException(message: String) : IOException(message)

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    private fun fail(context: Context, message: String): LoginResult.Error {
        clearLocalSession(context)
        return LoginResult.Error(message)
    }

    /**
     * WebView ma osobny magazyn cookies, dlatego przed otwarciem playera otrzymuje
     * wyłącznie cookies Shinden. Cookies nie są przekazywane zewnętrznym hostom.
     */
    fun preparePlayerCookies(context: Context) {
        val manager = WebCookieManager.getInstance()
        manager.setAcceptCookie(true)
        SecureSessionStore.load(context).forEach { cookie ->
            val domain = cookie.domain.orEmpty().trimStart('.').lowercase()
            val path = cookie.path ?: "/"
            if (domain.isNotEmpty() && domain != ShindenUrls.HOST) return@forEach
            if (cookie.hasExpired() || !cookie.name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return@forEach
            if (!path.startsWith('/') || path.any { it == '\r' || it == '\n' || it == ';' }) return@forEach
            if (cookie.value.any { it == '\r' || it == '\n' || it == ';' }) return@forEach

            val header = buildString {
                append(cookie.name).append('=').append(cookie.value)
                append("; Path=").append(path)
                // Nie rozszerzaj zakresu host-only cookies na subdomeny.
                if (cookie.domain?.startsWith('.') == true) append("; Domain=.shinden.pl")
                if (cookie.secure) append("; Secure")
                if (cookie.isHttpOnly) append("; HttpOnly")
            }
            manager.setCookie("https://shinden.pl/", header)
        }
        manager.flush()
    }

    // Wylogowanie usuwa cookies lokalnie, bez wywołania endpointu.
    fun clearLocalSession(context: Context) {
        invalidateV1Token()
        cookies.cookieStore.removeAll()
        SecureSessionStore.clear(context)
        // Magazyn WebView jest czyszczony razem z sesją aplikacji.
        Handler(Looper.getMainLooper()).post {
            WebCookieManager.getInstance().removeAllCookies {
                WebCookieManager.getInstance().flush()
            }
        }
    }
}
