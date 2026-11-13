package com.example.shindenpocket.ui.theme

import androidx.compose.ui.graphics.Color

/** Wspólne tokeny kolorów ciemnego interfejsu Shinden Pocket. */
object PocketPalette {
    val background = Color(0xFF09090D)
    val surface = Color(0xFF101018)
    val surfaceRaised = Color(0xFF151520)
    val surfaceVariant = Color(0xFF1E1E2C)
    val surfaceHighest = Color(0xFF28283A)
    val primary = Color(0xFFA855F7)
    val primarySoft = Color(0xFFC084FC)
    val primaryContainer = Color(0xFF341B52)
    val onPrimaryContainer = Color(0xFFF6EDFF)
    val onBackground = Color(0xFFF7F5FA)
    val onSurfaceVariant = Color(0xFFAAA3B8)
    val outline = Color(0xFF6B6478)
    val outlineVariant = Color(0xFF292638)
    val success = Color(0xFF5EEAD4)
    val warning = Color(0xFFFBBF24)
    val error = Color(0xFFFFB4AB)

    val playerBackground = Color(0xFF0E0E15)
    val playerPanel = Color(0xFF1E1B2B)
    val playerAccent = Color(0xFFC084FC)
    val playerMuted = Color(0xFFADA9B9)
}

/** Skala odstępów kart, karuzel i sekcji ekranu w `dp`. */
object PocketSpacing {
    const val tiny = 4
    const val compact = 8
    const val regular = 12
    const val screen = 16
    const val section = 20
    const val large = 24
    const val extraLarge = 32
}

/** Skala promieni narożników kontrolek, plakatów i kart w `dp`. */
object PocketRadius {
    const val small = 10
    const val control = 14
    const val poster = 14
    const val card = 18
    const val large = 24
}
