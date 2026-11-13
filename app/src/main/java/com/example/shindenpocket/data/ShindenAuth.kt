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

object ShindenAuth {

    private const val LOGIN_URL = "https://shinden.pl/main/login"
    private const val AUTH_URL = "https://shinden.pl/v1/auth/login"
    private const val TOKEN_URL = "https://shinden.pl/api/user/jwt"
    private const val BROWSER_UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val cookies = CookieManager(
        null,
        CookiePolicy.ACCEPT_ORIGINAL_SERVER
    )

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

        CookieHandler.setDefault(cookies)
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
            val cookiesToSave = cookies.cookieStore.get(
                URI("https://shinden.pl/")
            )
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
            CookieHandler.setDefault(cookies)
            cookies.cookieStore.removeAll()

            val savedCookies = SecureSessionStore.load(context)
            if (savedCookies.isEmpty()) return@withContext null

            val uri = URI("https://shinden.pl/")
            savedCookies.forEach { cookie ->
                cookies.cookieStore.add(uri, cookie)
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
        if (jwt.isNotEmpty() && jwt != "null") {
            cachedJwt = jwt
            cachedCookieUid = null
            cachedTokenExpiresAt = System.currentTimeMillis() + 240_000L
        } else if (userId > 0) {
            cachedJwt = null
            cachedCookieUid = userId
            cachedTokenExpiresAt = System.currentTimeMillis() + 240_000L
        }

        if (userId > 0 && name.isNotBlank() && name != "null") {
            AuthenticatedUser(username = name, userId = userId)
        } else {
            if (jwt.isEmpty()) null else parseJwtUser(jwt)
        }
    } catch (_: Exception) {
        null
    }

    internal fun attachSessionCookies(connection: HttpURLConnection, extraCookie: String? = null) {
        val cookieList = cookies.cookieStore.get(URI("https://shinden.pl/"))
        val parts = mutableListOf<String>()
        cookieList.forEach { parts.add("${it.name}=${it.value}") }
        if (!extraCookie.isNullOrBlank()) parts.add(extraCookie)
        if (parts.isNotEmpty()) {
            connection.setRequestProperty("Cookie", parts.joinToString("; "))
        }
    }

    internal fun attachV1Auth(connection: HttpURLConnection, forceRefresh: Boolean = false) {
        val now = System.currentTimeMillis()
        if (forceRefresh || now >= cachedTokenExpiresAt || (cachedJwt == null && cachedCookieUid == null)) {
            runCatching { fetchAuthenticatedUser() }
        }
        val jwt = cachedJwt
        val uid = cachedCookieUid
        if (!jwt.isNullOrBlank()) {
            connection.setRequestProperty("Authorization", "Bearer $jwt")
        } else if (uid != null && uid > 0) {
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
     * 401 oznacza brak logowania, a 403 lub 404 pozostawia stan niepewny.
     */
    private fun fetchAuthenticatedUser(): AuthenticatedUser? {
        val uri = URI("https://shinden.pl/")
        val connection = URL(TOKEN_URL).openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", BROWSER_UA)
            connection.setRequestProperty("Referer", "https://shinden.pl/")
            // Ręcznie, z pominięciem dopasowania Path/Secure w CookieManager
            addCookiesToConnection(connection, uri)

            val sent = cookies.cookieStore.get(uri).map { "${it.name}(path=${it.path})" }
            val code = connection.responseCode
            Log.w("ShindenAuth", "user/jwt -> HTTP $code, wysłane cookies: $sent")

            when (code) {
                401, 404 -> null
                in 200..299 -> {
                    val jsonText = connection.inputStream
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                    parseSessionResponse(jsonText)
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
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", BROWSER_UA)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            connection.setRequestProperty("Accept-Language", "pl-PL,pl;q=0.9")
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            connection.inputStream.bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
        } finally {
            connection.disconnect()
        }
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

        val connection = URL(AUTH_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
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

    private fun followTicket(redirect: String) {
        val cleanRedirect = redirect.replace("&amp;", "&")
        val url = URL(URL("https://shinden.pl/"), cleanRedirect)

        if (!url.host.equals("shinden.pl", true) ||
            !url.path.startsWith("/login/ticket")
        ) {
            throw AuthException("Shinden zwrócił nieoczekiwany adres potwierdzenia logowania.")
        }

        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false // Automatyczne przekierowanie jest wyłączone, aby przechwycić cookies z biletu.
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", BROWSER_UA)
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            connection.setRequestProperty("Referer", "https://shinden.pl/main/login?return=%2Fmain")

            addCookiesToConnection(connection, url.toURI())

            val code = connection.responseCode

            // Bilet może ustawić cookies zarówno w odpowiedzi 200, jak i 302.
            val setCookieHeaders = connection.headerFields["Set-Cookie"]
            if (!setCookieHeaders.isNullOrEmpty()) {
                val uri = URI("https://shinden.pl/")
                for (header in setCookieHeaders) {
                    HttpCookie.parse(header).forEach { cookie ->
                        cookies.cookieStore.add(uri, cookie)
                    }
                }
            }

            Log.w("ShindenAuth", "login/ticket status=$code")

            // Odpowiedź 2xx lub 3xx oznacza poprawne przetworzenie biletu.
            if (code !in 200..399 && code != 302) {
                throw AuthException("Bilet logowania wygasł lub został użyty. Spróbuj ponownie.")
            }
        } finally {
            connection.disconnect()
        }
    }


    private fun addCookiesToConnection(connection: HttpURLConnection, uri: URI) {
        val cookieList = cookies.cookieStore.get(uri)
        if (cookieList.isNotEmpty()) {
            val cookieHeader = cookieList.joinToString("; ") { "${it.name}=${it.value}" }
            connection.setRequestProperty("Cookie", cookieHeader)
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

    /** Zwraca diagnostykę HTTP bez danych logowania: kod i początek odpowiedzi. */
    private fun logHttp(step: String, code: Int, body: String?) {
        val snippet = body.orEmpty().replace(Regex("\\s+"), " ").take(200)
        Log.w("ShindenAuth", "$step -> HTTP $code · $snippet")
    }

    private class AuthException(message: String) : IOException(message)

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
            if (domain.isNotEmpty() && domain != "shinden.pl") return@forEach
            if (cookie.hasExpired()) return@forEach
            val header = buildString {
                append(cookie.name).append('=').append(cookie.value)
                append("; Path=")
                append(cookie.path?.takeIf { it.startsWith('/') } ?: "/")
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
