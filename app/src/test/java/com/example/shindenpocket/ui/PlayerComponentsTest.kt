package com.example.shindenpocket.ui

import com.example.shindenpocket.data.EpisodeSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerComponentsTest {

    @Test
    fun `isAdHost blocks known ad domains and their subdomains while allowing shinden and video hosts`() {
        assertTrue(isAdHost("doubleclick.net"))
        assertTrue(isAdHost("ad.doubleclick.net"))
        assertTrue(isAdHost("PAGEAD2.GOOGLESYNDICATION.COM."))
        assertTrue(isAdHost("cdn.adsterra.com"))
        assertTrue(isAdHost("sub.a.b.exoclick.com"))
        assertTrue(isAdHost("www.aqpkokeeyyncfl.com"))
        assertTrue(isAdHost("aqpkokeeyyncfl.com"))

        assertFalse(isAdHost(""))
        assertFalse(isAdHost("shinden.pl"))
        assertFalse(isAdHost("api.shinden.pl"))
        assertFalse(isAdHost("doubleclick.net.shinden.pl"))
        assertFalse(isAdHost("cda.pl"))
        assertFalse(isAdHost("ebd.cda.pl"))
        assertFalse(isAdHost("video.sibnet.ru"))
        assertFalse(isAdHost("notdoubleclick.net"))
    }

    @Test
    fun `resolveAdRequestContext prioritizes Sec-Fetch-Dest and falls back to path and Accept`() {
        // Skrypt rozpoznany przez `Sec-Fetch-Dest`, bez rozszerzenia `.js`.
        assertEquals(
            AdRequestContext.SCRIPT,
            resolveAdRequestContext(
                path = "/pagead/js/tag",
                fetchDest = "script",
                accept = "*/*",
                isForMainFrame = false
            )
        )

        // Arkusz stylów rozpoznany przez `Sec-Fetch-Dest` lub `.css`.
        assertEquals(
            AdRequestContext.STYLE,
            resolveAdRequestContext(
                path = "/ads/style",
                fetchDest = "style",
                accept = "*/*",
                isForMainFrame = false
            )
        )
        assertEquals(
            AdRequestContext.STYLE,
            resolveAdRequestContext(
                path = "/banner.css",
                fetchDest = "",
                accept = "text/css,*/*;q=0.1",
                isForMainFrame = false
            )
        )

        // Dokument HTML ramki podrzędnej
        assertEquals(
            AdRequestContext.HTML_DOCUMENT,
            resolveAdRequestContext(
                path = "/frame",
                fetchDest = "iframe",
                accept = "*/*",
                isForMainFrame = false
            )
        )

        // Obraz SVG i rastrowy
        assertEquals(
            AdRequestContext.SVG_IMAGE,
            resolveAdRequestContext(
                path = "/icon.svg",
                fetchDest = "image",
                accept = "image/avif,image/webp,*/*",
                isForMainFrame = false
            )
        )
        assertEquals(
            AdRequestContext.RASTER_IMAGE,
            resolveAdRequestContext(
                path = "/pixel",
                fetchDest = "image",
                accept = "image/avif,image/webp,*/*",
                isForMainFrame = false
            )
        )

        // Dane JSON i XML (VAST)
        assertEquals(
            AdRequestContext.JSON,
            resolveAdRequestContext(
                path = "/bid",
                fetchDest = "empty",
                accept = "application/json, text/plain, */*",
                isForMainFrame = false
            )
        )
        assertEquals(
            AdRequestContext.XML,
            resolveAdRequestContext(
                path = "/vast.xml",
                fetchDest = "empty",
                accept = "application/xml",
                isForMainFrame = false
            )
        )

        // Reklamowy strumień multimediów
        assertEquals(
            AdRequestContext.MEDIA_OR_FONT,
            resolveAdRequestContext(
                path = "/preroll.mp4",
                fetchDest = "video",
                accept = "*/*",
                isForMainFrame = false
            )
        )
    }

    @Test
    fun `scripts avoid prohibited ad-SDK spoofing and async timer buffer races`() {
        assertFalse(antiAdblockScript.contains("adsbygoogle"))
        assertFalse(antiAdblockScript.contains("googletag"))
        assertTrue(antiAdblockScript.contains("canRunAds"))

        val compactJs = pocketScript(true)
        assertTrue(compactJs.contains("markedNodes"))
        assertTrue(compactJs.contains("hasRelevantMutation"))

        val source = EpisodeSource(
            service = "Cda",
            quality = "1080p",
            languages = listOf("Polski", "Japoński"),
            addedAt = null,
            playerId = "98765"
        )
        val selectionJs = playerSelectionScript(source, 2)
        assertTrue(selectionJs.contains("\"98765\""))
        assertTrue(selectionJs.contains("\"Cda\""))
        assertEquals("Cda · 1080p · Polski/Japoński", source.formatHeaderSubtitle())
        assertEquals("✓ Cda · 1080p (Polski, Japoński)", source.formatDropdownLabel(isSelected = true))
        assertEquals("Cda · 1080p (Polski, Japoński)", source.formatDropdownLabel(isSelected = false))
    }
}
