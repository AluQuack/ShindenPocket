package com.example.shindenpocket.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URL

class ShindenCookieSecurityTest {

    @Test
    fun trustedOriginRequiresHttpsExactHostDefaultPortAndNoUserInfo() {
        assertTrue(ShindenHttpSecurity.isTrusted(URI("https://shinden.pl/path")))
        assertTrue(ShindenHttpSecurity.isTrusted(URI("https://shinden.pl:443/path")))

        assertFalse(ShindenHttpSecurity.isTrusted(URI("http://shinden.pl/path")))
        assertFalse(ShindenHttpSecurity.isTrusted(URI("https://evil.example/path")))
        assertFalse(ShindenHttpSecurity.isTrusted(URI("https://api.shinden.pl/path")))
        assertFalse(ShindenHttpSecurity.isTrusted(URI("https://shinden.pl:8443/path")))
        assertFalse(ShindenHttpSecurity.isTrusted(URI("https://user@shinden.pl/path")))
    }

    @Test
    fun cookieManagerAppliesPathDomainSecureAndExpiryRules() {
        val manager = ShindenCookieManager()
        val source = URI("https://shinden.pl/account/login")
        manager.put(
            source,
            mutableMapOf(
                "Set-Cookie" to mutableListOf(
                    "account=one; Path=/account; Secure",
                    "root=two; Path=/; Secure",
                    "domain=three; Domain=.shinden.pl; Path=/; Secure",
                    "expired=gone; Path=/; Max-Age=0",
                    "foreign=no; Domain=evil.example; Path=/; Secure"
                )
            )
        )

        val accountHeader = manager.cookieHeader(URI("https://shinden.pl/account/profile"))
        assertTrue(accountHeader.contains("account=one"))
        assertTrue(accountHeader.contains("root=two"))
        assertTrue(accountHeader.contains("domain=three"))
        assertFalse(accountHeader.contains("expired=gone"))
        assertFalse(accountHeader.contains("foreign=no"))
        assertTrue(manager.cookieStore.cookies.first { it.name == "root" }.secure)

        val apiHeader = manager.cookieHeader(URI("https://shinden.pl/v1/me"))
        assertFalse(apiHeader.contains("account=one"))
        assertTrue(apiHeader.contains("root=two"))
        assertTrue(manager.cookieHeader(URI("https://shinden.pl:443/v1/me")).contains("root=two"))
        assertTrue(manager.cookieHeader(URI("http://shinden.pl/account/profile")).isEmpty())
        assertTrue(manager.cookieHeader(URI("https://api.shinden.pl/account/profile")).isEmpty())
    }

    @Test
    fun redirectSetCookieIsAvailableOnlyToTrustedRedirectTarget() {
        val manager = ShindenCookieManager()
        val responseUri = URI("https://shinden.pl/comments")
        manager.put(
            responseUri,
            mutableMapOf("Set-Cookie" to mutableListOf("_rnd=verified; Path=/; Secure"))
        )

        val next = ShindenHttpSecurity.resolveRedirect(
            URL(responseUri.toString()),
            "/comments?r307=1"
        )
        assertTrue(manager.cookieHeader(next.toURI()).contains("_rnd=verified"))

        val rejected = runCatching {
            ShindenHttpSecurity.resolveRedirect(URL(responseUri.toString()), "https://evil.example/")
        }
        assertTrue(rejected.isFailure)
        assertTrue(manager.cookieHeader(URI("https://evil.example/")).isEmpty())
    }

    @Test
    fun foreignResponsesCannotStoreCookies() {
        val manager = ShindenCookieManager()
        manager.put(
            URI("https://evil.example/"),
            mutableMapOf("Set-Cookie" to mutableListOf("session=stolen; Path=/; Secure"))
        )

        assertTrue(manager.cookieStore.cookies.isEmpty())
    }

    @Test
    fun loginCookiesKeepTheirScopeAfterSessionRestore() {
        val loginManager = ShindenCookieManager()
        loginManager.put(
            URI("https://shinden.pl/login/ticket"),
            mutableMapOf(
                "Set-Cookie" to mutableListOf(
                    "session=active; Path=/; Secure; HttpOnly",
                    "ticket=used; Path=/login; Secure; HttpOnly"
                )
            )
        )

        val restoredManager = ShindenCookieManager()
        val root = URI("https://shinden.pl/")
        loginManager.cookieStore.cookies.forEach { restoredManager.cookieStore.add(root, it) }

        val jwtHeader = restoredManager.cookieHeader(URI("https://shinden.pl/api/user/jwt"))
        assertTrue(jwtHeader.contains("session=active"))
        assertFalse(jwtHeader.contains("ticket=used"))
    }

    private fun ShindenCookieManager.cookieHeader(uri: URI): String =
        get(uri, mutableMapOf())
            .entries
            .firstOrNull { it.key.equals("Cookie", ignoreCase = true) }
            ?.value
            ?.joinToString("; ")
            .orEmpty()
}
