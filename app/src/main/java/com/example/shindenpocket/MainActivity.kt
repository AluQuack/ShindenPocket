package com.example.shindenpocket

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.example.shindenpocket.data.AppRelease
import com.example.shindenpocket.data.AppUpdateManager
import com.example.shindenpocket.data.EpisodeNotification
import com.example.shindenpocket.ui.ShindenPocketApp
import com.example.shindenpocket.ui.theme.ShindenPocketTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private var notificationTarget by mutableStateOf<EpisodeNotification.Target?>(null)
    private var availableUpdate by mutableStateOf<AppRelease?>(null)
    private var pendingUpdateApk: File? = null
    private var awaitingInstallPermission = false

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
                availableUpdate?.let { release ->
                    AlertDialog(
                        onDismissRequest = { availableUpdate = null },
                        title = { Text("Dostępna aktualizacja") },
                        text = {
                            Text(
                                "Dostępna jest wersja ${release.version}. " +
                                    "Czy pobrać ją i uruchomić instalację?"
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    availableUpdate = null
                                    downloadAndInstall(release)
                                }
                            ) { Text("Tak") }
                        },
                        dismissButton = {
                            TextButton(onClick = { availableUpdate = null }) {
                                Text("Pomiń")
                            }
                        }
                    )
                }
            }
        }
        lifecycleScope.launch {
            try {
                availableUpdate = AppUpdateManager.findUpdate(
                    AppUpdateManager.currentVersion(this@MainActivity)
                )
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (awaitingInstallPermission) {
            awaitingInstallPermission = false
            if (packageManager.canRequestPackageInstalls()) {
                launchInstaller()
            } else {
                pendingUpdateApk = null
                Toast.makeText(
                    this,
                    "Bez zgody na instalowanie aplikacji aktualizacja nie może zostać zainstalowana.",
                    Toast.LENGTH_LONG
                ).show()
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

    private fun downloadAndInstall(release: AppRelease) {
        Toast.makeText(this, "Pobieranie aktualizacji…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                pendingUpdateApk = AppUpdateManager.downloadApk(applicationContext, release)
                if (packageManager.canRequestPackageInstalls()) {
                    launchInstaller()
                } else {
                    awaitingInstallPermission = true
                    Toast.makeText(
                        this@MainActivity,
                        "Zezwól ShindenPocket na instalowanie aplikacji.",
                        Toast.LENGTH_LONG
                    ).show()
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            "package:$packageName".toUri()
                        )
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                pendingUpdateApk = null
                awaitingInstallPermission = false
                Toast.makeText(
                    this@MainActivity,
                    "Nie udało się pobrać lub zweryfikować aktualizacji.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun launchInstaller() {
        val apk = pendingUpdateApk ?: return
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apk
            )
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
            pendingUpdateApk = null
        } catch (_: Exception) {
            pendingUpdateApk = null
            Toast.makeText(
                this,
                "Nie udało się uruchomić instalatora Androida.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
