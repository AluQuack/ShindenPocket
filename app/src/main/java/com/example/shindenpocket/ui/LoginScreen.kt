package com.example.shindenpocket.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.shindenpocket.data.Anime
import com.example.shindenpocket.data.EpisodeBackgroundScheduler
import com.example.shindenpocket.data.PlaybackPreferences
import com.example.shindenpocket.data.ShindenAuth
import com.example.shindenpocket.data.ShindenUrls
import java.util.Locale
import com.example.shindenpocket.ui.theme.PocketPalette
import com.example.shindenpocket.ui.theme.PocketRadius
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Ekran konta i preferencji użytkownika. */
@Composable
fun LoginScreen(
    loggedInUser: ShindenAuth.LoginResult.Success?,
    onLoginSuccess: (ShindenAuth.LoginResult.Success) -> Unit,
    onClearSession: () -> Unit,
    onSearch: (() -> Unit)? = null,
    onOpenFavorites: () -> Unit = {},
    onOpenLibrarySubTab: (Int) -> Unit = {},
    library: List<Anime> = emptyList()
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var username by rememberSaveable { mutableStateOf("") }
    // Hasło pozostaje wyłącznie w pamięci i nie jest utrwalane.
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var expandedSection by rememberSaveable { mutableStateOf<String?>(null) }
    val accountId = loggedInUser?.userId
    var sourceMode by remember(accountId) {
        mutableStateOf(accountId?.let { PlaybackPreferences.sourceMode(context, it) } ?: "anime")
    }
    var askNext by remember(accountId) {
        mutableStateOf(accountId?.let { PlaybackPreferences.askOnNext(context, it) } ?: true)
    }
    var askClose by remember(accountId) {
        mutableStateOf(accountId?.let { PlaybackPreferences.askOnClose(context, it) } ?: true)
    }
    var compactDefault by remember(accountId) {
        mutableStateOf(PlaybackPreferences.defaultCompact(context, accountId))
    }
    var monitoringEnabled by remember(accountId) {
        mutableStateOf(accountId != null && EpisodeBackgroundScheduler.isEnabledFor(context, accountId))
    }
    var monitoringInterval by remember(accountId) {
        mutableStateOf(EpisodeBackgroundScheduler.intervalHours(context))
    }
    var showMonitoringPrompt by remember(accountId) { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val id = accountId
        if (granted && id != null) {
            try {
                EpisodeBackgroundScheduler.enable(context, id, monitoringInterval)
                monitoringEnabled = true
                message = "Monitorowanie włączone."
                messageIsError = false
            } catch (_: Exception) {
                message = "Nie udało się włączyć monitorowania."
                messageIsError = true
            }
        } else {
            if (id != null) EpisodeBackgroundScheduler.disable(context, id)
            message = "Zgoda na powiadomienia jest wymagana."
            messageIsError = true
        }
    }
    LaunchedEffect(accountId) {
        val id = accountId
        if (id == null) {
            showMonitoringPrompt = false
        } else {
            when (EpisodeBackgroundScheduler.savedEnabledFor(context, id)) {
                null -> showMonitoringPrompt = true
                true -> {
                    try {
                        EpisodeBackgroundScheduler.enable(context, id, monitoringInterval)
                        monitoringEnabled = true
                    } catch (_: Exception) {
                        monitoringEnabled = false
                    }
                }
                false -> monitoringEnabled = false
            }
        }
    }
    val login: () -> Unit = {
        if (!loading && username.isNotBlank() && password.isNotBlank()) {
            loading = true
            message = null
            scope.launch {
                try {
                    when (val result = ShindenAuth.login(context, username.trim(), password)) {
                        is ShindenAuth.LoginResult.Success -> {
                            EpisodeBackgroundScheduler.disableIfDifferentAccount(context, result.userId)
                            onLoginSuccess(result)
                            message = null
                        }
                        is ShindenAuth.LoginResult.Error -> {
                            message = result.message
                            messageIsError = true
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    message = "Nie udało się połączyć z Shinden. Sprawdź internet i spróbuj ponownie."
                    messageIsError = true
                } finally {
                    password = ""
                    loading = false
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (loggedInUser == null) {
            PocketScreenHeader(
                title = "Konto",
                subtitle = "Połącz konto i zsynchronizuj swoją bibliotekę",
                actions = {
                    if (onSearch != null) {
                        IconButton(
                            onClick = onSearch,
                            modifier = Modifier.size(48.dp).semantics { contentDescription = "Szukaj w katalogu anime" }
                        ) { Text("⌕", fontSize = 22.sp) }
                    }
                }
            )
            PocketCard(modifier = Modifier.fillMaxWidth(), emphasized = true) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Połącz z Shinden", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            "Użyj danych swojego konta Shinden. Hasło nie jest zapisywane w aplikacji.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Login lub e-mail") },
                        singleLine = true,
                        enabled = !loading,
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Hasło") },
                        singleLine = true,
                        enabled = !loading,
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { login() }),
                        trailingIcon = {
                            IconButton(
                                onClick = { passwordVisible = !passwordVisible },
                                modifier = Modifier.size(48.dp).semantics {
                                    contentDescription = if (passwordVisible) "Ukryj hasło" else "Pokaż hasło"
                                }
                            ) { Text(if (passwordVisible) "◎" else "◉", color = scheme.onSurfaceVariant) }
                        },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = login,
                        enabled = !loading && username.isNotBlank() && password.isNotBlank(),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = PocketPalette.primary),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(Modifier.width(10.dp))
                            Text("Logowanie…", color = Color.White)
                        } else {
                            Text("Zaloguj się", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        } else {
            val favCount = remember(library) { library.count { it.favorite } }
            val totalWatchedEpisodes = remember(library) { library.sumOf { it.watched.coerceAtLeast(0) } }
            val ratedList = remember(library) { library.mapNotNull { it.rateTotal } }
            val avgUserScore = remember(ratedList) {
                if (ratedList.isEmpty()) null
                else String.format(Locale.US, "%.2f", ratedList.average())
            }
            
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier.size(88.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(PocketPalette.primary, PocketPalette.primaryContainer)
                            )
                        )
                        .padding(3.dp)
                ) {
                    PocketAvatar(
                        url = ShindenUrls.avatarUrl(loggedInUser.userId),
                        name = loggedInUser.username,
                        modifier = Modifier.fillMaxSize(),
                        backgroundColor = PocketPalette.surfaceRaised,
                        letterColor = PocketPalette.primarySoft,
                        letterStyle = MaterialTheme.typography.headlineLarge
                    )
                }
                Text(
                    loggedInUser.username,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = PocketPalette.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "ID: ${loggedInUser.userId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PocketPalette.onSurfaceVariant
                )
            }

            
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(PocketRadius.card.dp),
                color = PocketPalette.surfaceRaised,
                border = BorderStroke(1.dp, PocketPalette.outlineVariant.copy(alpha = 0.72f))
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        UserStatTile(
                            label = "W bibliotece",
                            value = library.size.toString(),
                            onClick = { onOpenLibrarySubTab(1) },
                            modifier = Modifier.weight(1f)
                        )
                        UserStatTile(
                            label = "Ulubione",
                            value = favCount.toString(),
                            onClick = onOpenFavorites,
                            modifier = Modifier.weight(1f)
                        )
                        UserStatTile(
                            label = "Odcinki",
                            value = totalWatchedEpisodes.toString(),
                            onClick = { onOpenLibrarySubTab(2) },
                            modifier = Modifier.weight(1f)
                        )
                        UserStatTile(
                            label = "Śr. ocena",
                            value = avgUserScore?.let { "★ $it" } ?: "—",
                            onClick = { onOpenLibrarySubTab(9) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            4 to "Oglądam",
                            2 to "Obejrzane",
                            5 to "Planuję",
                            6 to "Wstrzymane",
                            7 to "Porzucone",
                            8 to "Pomijam"
                        ).forEach { (tabIdx, label) ->
                            val count = library.count { matchesLibrarySubTab(it, tabIdx) }
                            Surface(
                                onClick = { onOpenLibrarySubTab(tabIdx) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(10.dp),
                                color = PocketPalette.surface,
                                border = BorderStroke(1.dp, PocketPalette.outlineVariant.copy(alpha = 0.5f))
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = count.toString(),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (count > 0) PocketPalette.primarySoft else PocketPalette.onSurfaceVariant
                                    )
                                    Text(
                                        text = label,
                                        fontSize = 9.sp,
                                        color = PocketPalette.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(PocketRadius.card.dp),
                color = PocketPalette.surfaceRaised,
                border = BorderStroke(1.dp, PocketPalette.outlineVariant.copy(alpha = 0.72f))
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    
                    AccountMenuRow(
                        icon = "⚙",
                        title = "Moje ustawienia",
                        subtitle = "Odtwarzacz, monitorowanie odcinków i powiadomienia",
                        expanded = expandedSection == "settings",
                        onClick = {
                            expandedSection = if (expandedSection == "settings") null else "settings"
                        }
                    )
                    AnimatedVisibility(visible = expandedSection == "settings") {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(PocketPalette.surface)
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text("Nowe odcinki i powiadomienia", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = PocketPalette.primarySoft)
                            PreferenceSwitchRow(
                                title = "Monitorowanie w tle",
                                checked = monitoringEnabled,
                                onChange = { enabled ->
                                    if (!enabled) {
                                        try {
                                            EpisodeBackgroundScheduler.disable(context, loggedInUser.userId)
                                            monitoringEnabled = false
                                            message = "Monitorowanie wyłączone."
                                            messageIsError = false
                                        } catch (_: Exception) {
                                            message = "Nie udało się wyłączyć monitorowania."
                                            messageIsError = true
                                        }
                                    } else {
                                        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                                            ContextCompat.checkSelfPermission(
                                                context, Manifest.permission.POST_NOTIFICATIONS
                                            ) != PackageManager.PERMISSION_GRANTED
                                        if (needsPermission) {
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            try {
                                                EpisodeBackgroundScheduler.enable(
                                                    context, loggedInUser.userId, monitoringInterval
                                                )
                                                monitoringEnabled = true
                                                message = "Monitorowanie włączone."
                                                messageIsError = false
                                            } catch (_: Exception) {
                                                message = "Nie udało się włączyć monitorowania."
                                                messageIsError = true
                                            }
                                        }
                                    }
                                }
                            )
                            Text(
                                "Co $monitoringInterval h",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                            Slider(
                                value = EpisodeBackgroundScheduler.INTERVAL_OPTIONS_HOURS
                                    .indexOf(monitoringInterval).coerceAtLeast(0).toFloat(),
                                onValueChange = { index ->
                                    monitoringInterval = EpisodeBackgroundScheduler.INTERVAL_OPTIONS_HOURS[
                                        index.roundToInt().coerceIn(
                                            0, EpisodeBackgroundScheduler.INTERVAL_OPTIONS_HOURS.lastIndex
                                        )
                                    ]
                                },
                                onValueChangeFinished = {
                                    try {
                                        EpisodeBackgroundScheduler.setInterval(context, monitoringInterval)
                                        monitoringEnabled = EpisodeBackgroundScheduler.isEnabledFor(
                                            context, loggedInUser.userId
                                        )
                                        message = "Interwał zmieniony na $monitoringInterval h."
                                        messageIsError = false
                                    } catch (_: Exception) {
                                        monitoringInterval = EpisodeBackgroundScheduler.intervalHours(context)
                                        monitoringEnabled = EpisodeBackgroundScheduler.isEnabledFor(
                                            context, loggedInUser.userId
                                        )
                                        message = "Nie udało się zmienić interwału."
                                        messageIsError = true
                                    }
                                },
                                valueRange = 0f..EpisodeBackgroundScheduler.INTERVAL_OPTIONS_HOURS.lastIndex.toFloat(),
                                steps = EpisodeBackgroundScheduler.INTERVAL_OPTIONS_HOURS.size - 2
                            )
                            PocketSecondaryButton(
                                label = "Powiadomienia ↗",
                                onClick = {
                                    try {
                                        context.startActivity(
                                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        )
                                    } catch (_: Exception) {
                                        message = "Nie udało się otworzyć ustawień powiadomień."
                                        messageIsError = true
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            HorizontalDivider(color = scheme.outlineVariant)
                            Text("Preferowany serwis wideo", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = PocketPalette.primarySoft)
                            listOf(
                                "anime" to "Zapamiętany dla danego anime",
                                "global" to "Ostatni wybrany serwis",
                                "first" to "Pierwsze dostępne źródło"
                            ).forEach { (mode, title) ->
                                val updateMode = {
                                    sourceMode = mode
                                    PlaybackPreferences.setSourceMode(context, loggedInUser.userId, mode)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                                        .selectable(selected = sourceMode == mode, role = Role.RadioButton, onClick = updateMode),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = sourceMode == mode, onClick = null)
                                    Text(title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                }
                            }
                            HorizontalDivider(color = scheme.outlineVariant)
                            PreferenceSwitchRow(
                                title = "Pytaj o obejrzenie przy następnym odcinku",
                                checked = askNext,
                                onChange = {
                                    askNext = it
                                    PlaybackPreferences.setAskOnNext(context, loggedInUser.userId, it)
                                }
                            )
                            PreferenceSwitchRow(
                                title = "Pytaj o obejrzenie przy zamknięciu filmu",
                                checked = askClose,
                                onChange = {
                                    askClose = it
                                    PlaybackPreferences.setAskOnClose(context, loggedInUser.userId, it)
                                }
                            )
                            PreferenceSwitchRow(
                                title = "Domyślnie pokazuj sam odtwarzacz",
                                checked = compactDefault,
                                onChange = {
                                    compactDefault = it
                                    PlaybackPreferences.setDefaultCompact(context, loggedInUser.userId, it)
                                }
                            )
                        }
                    }
                    HorizontalDivider(color = PocketPalette.outlineVariant.copy(alpha = 0.5f))

                    
                    AccountMenuRow(
                        icon = "?",
                        title = "Pomoc i FAQ",
                        subtitle = "Bezpieczeństwo sesji",
                        expanded = expandedSection == "faq",
                        onClick = {
                            expandedSection = if (expandedSection == "faq") null else "faq"
                        }
                    )
                    AnimatedVisibility(visible = expandedSection == "faq") {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                                .background(PocketPalette.surface)
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Bezpieczeństwo sesji", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = PocketPalette.primarySoft)
                            Text(
                                "Hasło nigdy nie jest zapisywane. Cookies sesji Shindena są szyfrowane lokalnie na urządzeniu.",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = {
                                    try {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shinden.pl")))
                                    } catch (_: Exception) {}
                                }
                            ) {
                                Text("Otwórz Shinden.pl w przeglądarce ↗", color = PocketPalette.primarySoft)
                            }
                            TextButton(
                                onClick = {
                                    try {
                                        context.startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                Uri.parse("https://github.com/AluQuack/ShindenPocket")
                                            )
                                        )
                                    } catch (_: Exception) {
                                        message = "Nie udało się otworzyć repozytorium."
                                        messageIsError = true
                                    }
                                }
                            ) {
                                Text("Repozytorium GitHub ↗", color = PocketPalette.primarySoft)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            
            Button(
                onClick = { showLogoutConfirm = true },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PocketPalette.primary,
                    contentColor = Color.White
                ),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(
                    "⇥  Wyloguj się",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        message?.let { info ->
            PocketMessage(
                message = info,
                type = if (messageIsError) PocketMessageType.ERROR else PocketMessageType.SUCCESS
            )
        }
    }

    if (showLogoutConfirm && loggedInUser != null) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("Wylogować z urządzenia?") },
            text = {
                Text("Zapisana sesja zostanie usunięta, a monitorowanie w tle wstrzymane. Twój wybór i historia wykrytych odcinków pozostaną na urządzeniu.")
            },
            confirmButton = {
                TextButton(onClick = {
                    EpisodeBackgroundScheduler.pause(context)
                    ShindenAuth.clearLocalSession(context)
                    onClearSession()
                    password = ""
                    showLogoutConfirm = false
                    message = "Wylogowano z tego urządzenia."
                    messageIsError = false
                }) { Text("Wyloguj") }
            },
            dismissButton = { TextButton(onClick = { showLogoutConfirm = false }) { Text("Anuluj") } }
        )
    }

    if (showMonitoringPrompt && loggedInUser != null) {
        val decline = {
            EpisodeBackgroundScheduler.disable(context, loggedInUser.userId)
            monitoringEnabled = false
            showMonitoringPrompt = false
        }
        AlertDialog(
            onDismissRequest = decline,
            title = { Text("Monitorować nowe odcinki?") },
            text = {
                Text(
                    "Aplikacja może sprawdzać nowe odcinki w tle i wysyłać powiadomienia. " +
                        "Interwał możesz później zmienić w ustawieniach konta."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMonitoringPrompt = false
                        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS
                            ) != PackageManager.PERMISSION_GRANTED
                        if (needsPermission) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            try {
                                EpisodeBackgroundScheduler.enable(
                                    context, loggedInUser.userId, monitoringInterval
                                )
                                monitoringEnabled = true
                            } catch (_: Exception) {
                                monitoringEnabled = false
                                message = "Nie udało się włączyć monitorowania."
                                messageIsError = true
                            }
                        }
                    }
                ) { Text("Włącz") }
            },
            dismissButton = {
                TextButton(onClick = decline) { Text("Nie teraz") }
            }
        )
    }
}

@Composable
private fun UserStatTile(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = PocketPalette.surface,
        border = BorderStroke(1.dp, PocketPalette.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.ExtraBold,
                color = PocketPalette.onBackground,
                maxLines = 1
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = PocketPalette.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AccountMenuRow(
    icon: String,
    title: String,
    subtitle: String? = null,
    expanded: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier.size(36.dp)
                .background(PocketPalette.primaryContainer.copy(alpha = 0.65f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = icon,
                color = PocketPalette.primarySoft,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = PocketPalette.onBackground,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = PocketPalette.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = if (expanded) "⌄" else "›",
            color = PocketPalette.onSurfaceVariant,
            style = MaterialTheme.typography.titleLarge
        )
    }
}

@Composable
private fun PreferenceSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = null)
    }
}
