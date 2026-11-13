package com.example.shindenpocket

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.shindenpocket.data.EpisodeNotification
import com.example.shindenpocket.ui.ShindenPocketApp
import com.example.shindenpocket.ui.theme.ShindenPocketTheme

class MainActivity : ComponentActivity() {
    private var notificationTarget by mutableStateOf<EpisodeNotification.Target?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Ekrany aplikacji blokują orientację pionową; odtwarzacz czasowo zwalnia tę blokadę.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ImmersiveMode.enable(window)
        // Usuwamy wyłącznie nieużywane znaczniki czasu odtwarzania.
        // Czyszczenie nie obejmuje sesji, historii odcinków ani preferencji playera.
        deleteSharedPreferences("shinden_pocket_positions_v1")
        notificationTarget = EpisodeNotification.readTarget(intent)
        setContent {
            ShindenPocketTheme(darkTheme = true) {
                // Zawartość główna nie nachodzi na aparat/wycięcie ekranu;
                // systemowe paski są ukryte, więc nie zabierają miejsca.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .windowInsetsPadding(WindowInsets.displayCutout)
                ) {
                    ShindenPocketApp(
                        notificationTarget = notificationTarget,
                        onNotificationHandled = {
                            notificationTarget = null
                            EpisodeNotification.clearTarget(intent)
                        }
                    )
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Powrót z odtwarzacza, dialogu lub zewnętrznej aplikacji nie
        // powinien przywracać paska statusu i nawigacji Androida.
        if (hasFocus) ImmersiveMode.enable(window)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationTarget = EpisodeNotification.readTarget(intent)
    }
}
