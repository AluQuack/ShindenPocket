package com.example.shindenpocket.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PocketVisualsTest {

    @Test
    fun `language short code preserves machine translation marker`() {
        assertEquals("Polski AI", languageShortCode("iPL"))
        assertEquals("Polski AI", languageShortCode("Polski (tłumaczenie maszynowe)"))
        assertEquals("Polski AI", languageShortCode("Polski AI"))
        assertEquals("EN", languageShortCode("Angielski"))
    }
}
