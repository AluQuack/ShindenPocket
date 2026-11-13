package com.example.shindenpocket

import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Immersive sticky: aplikacja zajmuje dostępny ekran, a paski systemowe
 * można chwilowo wysunąć gestem od krawędzi. Bez przestarzałych flag
 * SYSTEM_UI_FLAG_* i bez wyłączania gestu Wstecz.
 */
internal object ImmersiveMode {
    fun enable(window: Window, useCutout: Boolean = false) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (useCutout && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attributes = window.attributes
            attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attributes
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
}
