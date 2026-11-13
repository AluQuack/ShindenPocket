package com.example.shindenpocket.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.shindenpocket.ImmersiveMode
import com.example.shindenpocket.data.EpisodeSource
import com.example.shindenpocket.data.PlaybackPreferences
import com.example.shindenpocket.data.ShindenAuth
import java.util.Locale

private val episodePathRegex = Regex("/(?:episode|epek)/[0-9]+(?:-[a-z0-9-]+)?/view/[0-9]+/?")

internal fun isValidShindenEpisodeUrl(episodeUrl: String): Boolean {
    val uri = Uri.parse(episodeUrl)
    return uri.scheme == "https" &&
            uri.host == "shinden.pl" &&
            uri.path.orEmpty().matches(episodePathRegex) &&
            uri.query == null &&
            uri.fragment == null
}

/**
 * Dialog odtwarzacza odcinka z natywnym paskiem sterowania, obsługą pełnego ekranu,
 * blokadą domen reklamowych, automatycznym wyborem źródła i zarządzaniem cyklem życia WebView.
 */
@Composable
internal fun EpisodePlayerDialog(
    source: EpisodeSource,
    sourceIndex: Int,
    episodeUrl: String,
    episodeNumber: String,
    userId: Int?,
    sources: List<EpisodeSource>,
    onSourceChange: (Int) -> Unit,
    onPreviousEpisode: (() -> Unit)?,
    onNextEpisode: (() -> Unit)?,
    movingEpisode: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findHostActivity() }

    // Pozwalamy na obrót ekranu tylko podczas otwartego odtwarzacza, a po zamknięciu
    // przywracamy wcześniejszą orientację okna bez resetowania stanu nawigacji.
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose {
            val restoreOrientation = when (previousOrientation) {
                null,
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else -> previousOrientation
            }
            activity?.requestedOrientation = restoreOrientation
        }
    }

    val valid = remember(episodeUrl) { isValidShindenEpisodeUrl(episodeUrl) }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var compact by remember(episodeUrl, userId) {
        mutableStateOf(PlaybackPreferences.defaultCompact(context, userId))
    }
    var menuOpen by remember { mutableStateOf(false) }
    var reload by remember(episodeUrl, sourceIndex) { mutableIntStateOf(0) }
    var loading by remember(episodeUrl, sourceIndex, reload) { mutableStateOf(true) }
    val currentCompact by rememberUpdatedState(compact)
    val startupScript = remember(source, sourceIndex) { playerSelectionScript(source, sourceIndex) }

    var exitFullscreen by remember(episodeUrl, sourceIndex, reload) {
        mutableStateOf<(() -> Unit)?>(null)
    }
    var activeWebView by remember { mutableStateOf<WebView?>(null) }

    // Zarządzanie cyklem życia WebView przy przejściu aplikacji w tło / powrocie na pierwszy plan.
    DisposableEffect(lifecycleOwner, activeWebView) {
        val webView = activeWebView
        if (webView == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> {
                        webView.onPause()
                        webView.pauseTimers()
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        webView.onResume()
                        webView.resumeTimers()
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    val handleSourceChange: (Int) -> Unit = { newIndex ->
        exitFullscreen?.invoke()
        onSourceChange(newIndex)
    }
    val handlePreviousEpisode: (() -> Unit)? = onPreviousEpisode?.let { action ->
        {
            exitFullscreen?.invoke()
            action()
        }
    }
    val handleNextEpisode: (() -> Unit)? = onNextEpisode?.let { action ->
        {
            exitFullscreen?.invoke()
            action()
        }
    }

    Dialog(
        onDismissRequest = { exitFullscreen?.invoke() ?: onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        // Dialog ma osobne Window: wymuszamy pełny rozmiar i tryb immersive także po obrocie ekranu.
        DisposableEffect(dialogWindow, landscape) {
            dialogWindow?.let { window ->
                window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                ImmersiveMode.enable(window, useCutout = true)
            }
            onDispose { }
        }

        Surface(modifier = Modifier.fillMaxSize(), color = playerBackground) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(playerPanel)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    IconButton(
                        onClick = { exitFullscreen?.invoke() ?: onDismiss() },
                        modifier = Modifier
                            .size(48.dp)
                            .semantics { contentDescription = "Zamknij odtwarzacz" }
                    ) {
                        Text("✕", color = playerAccent, fontSize = 22.sp)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Odcinek $episodeNumber",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = source.formatHeaderSubtitle(),
                            color = playerMuted,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (landscape) {
                        PlayerControls(
                            source = source,
                            sourceIndex = sourceIndex,
                            sources = sources,
                            onSourceChange = handleSourceChange,
                            onPreviousEpisode = handlePreviousEpisode,
                            onNextEpisode = handleNextEpisode,
                            movingEpisode = movingEpisode,
                            modifier = Modifier.width(250.dp)
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier
                                .size(48.dp)
                                .semantics { contentDescription = "Opcje odtwarzacza" }
                        ) {
                            Text("⋮", color = playerAccent, fontSize = 24.sp)
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (compact) "Pokaż całą stronę" else "Tylko odtwarzacz") },
                                onClick = {
                                    menuOpen = false
                                    compact = !compact
                                },
                                enabled = valid
                            )
                            DropdownMenuItem(
                                text = { Text("Odśwież odtwarzacz") },
                                onClick = {
                                    menuOpen = false
                                    exitFullscreen?.invoke()
                                    reload++
                                },
                                enabled = valid
                            )
                        }
                    }
                }

                if (!landscape) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = playerPanel,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                        )
                    ) {
                        PlayerControls(
                            source = source,
                            sourceIndex = sourceIndex,
                            sources = sources,
                            onSourceChange = handleSourceChange,
                            onPreviousEpisode = handlePreviousEpisode,
                            onNextEpisode = handleNextEpisode,
                            movingEpisode = movingEpisode,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                if (!valid) {
                    Text(
                        text = "Nieprawidłowy adres odcinka Shinden.",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(20.dp)
                    )
                } else {
                    val viewportModifier = if (landscape || !compact) {
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                    }

                    val progress = remember(episodeUrl, sourceIndex, reload) { Animatable(0f) }
                    LaunchedEffect(episodeUrl, sourceIndex, reload) {
                        progress.animateTo(0.92f, tween(9000, easing = LinearOutSlowInEasing))
                    }
                    LaunchedEffect(loading) {
                        if (!loading) progress.snapTo(1f)
                    }

                    Box(modifier = viewportModifier.background(Color.Black)) {
                        key(episodeUrl, sourceIndex, reload) {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { viewContext ->
                                    createPlayerView(
                                        context = viewContext,
                                        window = dialogWindow,
                                        episodeUrl = episodeUrl,
                                        script = startupScript,
                                        compactView = { currentCompact },
                                        onLoaded = { loading = false },
                                        onFullscreenExitChanged = { action -> exitFullscreen = action },
                                        onWebViewCreated = { webView -> activeWebView = webView }
                                    )
                                },
                                update = { frame ->
                                    val browser = frame.getChildAt(0) as? WebView
                                    if (frame.tag != compact) {
                                        frame.tag = compact
                                        browser?.evaluateJavascript(pocketScript(compact), null)
                                        if (!compact && browser != null) {
                                            val chrome = browser.webChromeClient as? PocketPlayerChromeClient
                                            if (chrome?.isFullscreen != true) {
                                                browser.visibility = View.VISIBLE
                                            }
                                            loading = false
                                        }
                                    }
                                },
                                onRelease = { frame ->
                                    val browser = frame.getChildAt(0) as? WebView
                                    if (activeWebView === browser) {
                                        activeWebView = null
                                    }
                                    exitFullscreen = null
                                    browser?.let { webView ->
                                        (webView.webViewClient as? PocketPlayerWebViewClient)?.markDisposed()
                                        (webView.webChromeClient as? PocketPlayerChromeClient)?.dispose()
                                        webView.handler?.removeCallbacksAndMessages(null)
                                        webView.stopLoading()
                                        webView.onPause()
                                        webView.webChromeClient = null
                                        webView.webViewClient = WebViewClient()
                                        webView.loadUrl("about:blank")
                                        frame.removeView(webView)
                                        webView.removeAllViews()
                                        webView.destroy()
                                    }
                                    frame.removeAllViews()
                                }
                            )
                        }
                        if (loading) {
                            Surface(
                                modifier = Modifier.align(Alignment.Center),
                                shape = RoundedCornerShape(20.dp),
                                color = playerPanel.copy(alpha = 0.96f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = playerAccent,
                                        modifier = Modifier.size(28.dp),
                                        strokeWidth = 2.5.dp
                                    )
                                    Text(
                                        text = "Ładowanie odtwarzacza… ${(progress.value * 100).toInt()}%",
                                        color = playerMuted,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    LinearProgressIndicator(
                                        progress = { progress.value },
                                        color = playerAccent,
                                        trackColor = playerBackground,
                                        modifier = Modifier.width(180.dp).height(4.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (movingEpisode) {
                        Surface(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            color = playerPanel,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = "Pobieram źródła kolejnego odcinka…",
                                color = playerMuted,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Dialog może otrzymać ContextWrapper — przeszukujemy łańcuch kontekstów w celu znalezienia Activity. */
private fun Context.findHostActivity(): Activity? {
    var current: Context = this
    repeat(16) {
        when (current) {
            is Activity -> return current
            is ContextWrapper -> {
                val base = current.baseContext
                if (base === current) return null
                current = base
            }
            else -> return null
        }
    }
    return null
}

@SuppressLint("SetJavaScriptEnabled")
private fun createPlayerView(
    context: Context,
    window: Window?,
    episodeUrl: String,
    script: String,
    compactView: () -> Boolean,
    onLoaded: () -> Unit,
    onFullscreenExitChanged: ((() -> Unit)?) -> Unit,
    onWebViewCreated: (WebView) -> Unit
): FrameLayout {
    val initialCompact = compactView()
    val container = FrameLayout(context).apply {
        tag = initialCompact
    }
    val browser = WebView(context).apply {
        setBackgroundColor(AndroidColor.BLACK)
        // Ukrywamy WebView do czasu wyizolowania samego odtwarzacza CSS-em.
        visibility = View.INVISIBLE
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.mediaPlaybackRequiresUserGesture = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    }
    container.addView(
        browser,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    )

    val documentStartInstalled = installDocumentStartScripts(browser)

    val webViewClient = PocketPlayerWebViewClient(
        documentStartInstalled = documentStartInstalled,
        selectionScript = script,
        compactView = compactView,
        onLoaded = onLoaded
    )
    browser.webViewClient = webViewClient

    browser.webChromeClient = PocketPlayerChromeClient(
        browser = browser,
        dialogWindow = window,
        fallbackContainer = container,
        isBrowserRevealed = { webViewClient.isRevealed },
        onExitChanged = onFullscreenExitChanged
    )

    onWebViewCreated(browser)

    try {
        ShindenAuth.preparePlayerCookies(context)
    } catch (_: Exception) {
        // Strona publiczna może być widoczna bez ciastek sesji.
    }
    browser.loadUrl(episodeUrl)
    return container
}

private fun installDocumentStartScripts(browser: WebView): Boolean {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
        return false
    }
    return try {
        WebViewCompat.addDocumentStartJavaScript(browser, antiAdblockScript, setOf("*"))
        true
    } catch (_: Exception) {
        false
    }
}

private class PocketPlayerWebViewClient(
    private val documentStartInstalled: Boolean,
    private val selectionScript: String,
    private val compactView: () -> Boolean,
    private val onLoaded: () -> Unit
) : WebViewClient() {
    @Volatile
    private var disposed = false

    var isRevealed: Boolean = false
        private set

    fun markDisposed() {
        disposed = true
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        if (errorResponse.statusCode >= 400 && request.isForMainFrame) {
            android.util.Log.w(
                "PP",
                "HTTP ${errorResponse.statusCode} ${request.url} main=${request.isForMainFrame}"
            )
        }
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        shouldInterceptAdRequest(request)?.let { return it }
        return super.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val scheme = request.url?.scheme?.lowercase(Locale.ROOT).orEmpty()
        val host = request.url?.host?.lowercase(Locale.ROOT).orEmpty()

        if (!request.isForMainFrame) {
            if (scheme == "about" || scheme == "blob" || scheme == "data") return false
            if (scheme != "https" && scheme != "http") return true
            return isAdHost(host)
        }

        return !(scheme == "https" && (host == "shinden.pl" || host.endsWith(".shinden.pl")))
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (disposed || documentStartInstalled || url == null) return
        view.evaluateJavascript(antiAdblockScript, null)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        if (disposed) return
        val host = Uri.parse(url).host?.lowercase(Locale.ROOT).orEmpty()
        if (host == "shinden.pl" || host.endsWith(".shinden.pl")) {
            if (!documentStartInstalled) {
                view.evaluateJavascript(antiAdblockScript, null)
            }
            val isCompact = compactView()
            view.evaluateJavascript(pocketScript(isCompact), null)
            view.evaluateJavascript(selectionScript, null)
            if (!isCompact) {
                reveal(view)
            } else {
                view.postDelayed({ revealWhenReady(view, 0) }, 650L)
            }
        } else {
            reveal(view)
        }
    }

    private fun revealWhenReady(view: WebView, attempt: Int) {
        if (disposed || view.parent == null) return
        if (!compactView()) {
            reveal(view)
            return
        }
        val check = "window.__pocketReady===true&&(window.__pocketFrameLoaded===true||" +
                "Date.now()-window.__pocketFoundAt>2200)"
        view.evaluateJavascript(check) { result ->
            if (disposed || view.parent == null) return@evaluateJavascript
            if (result == "true" || attempt >= 40) {
                view.postDelayed({ reveal(view) }, 250L)
            } else {
                view.postDelayed({ revealWhenReady(view, attempt + 1) }, 280L)
            }
        }
    }

    private fun reveal(view: WebView) {
        if (disposed || view.parent == null) return
        val chrome = view.webChromeClient as? PocketPlayerChromeClient
        if (chrome?.isFullscreen != true) {
            view.visibility = View.VISIBLE
        }
        if (!isRevealed) {
            isRevealed = true
            onLoaded()
        }
    }
}

/** WebChromeClient dołączający widok pełnoekranowy wideo bezpośrednio do korzenia okna dialogu. */
private class PocketPlayerChromeClient(
    private val browser: WebView,
    private val dialogWindow: Window?,
    private val fallbackContainer: FrameLayout,
    private val isBrowserRevealed: () -> Boolean,
    private val onExitChanged: ((() -> Unit)?) -> Unit
) : WebChromeClient() {
    private var fullscreenLayer: FrameLayout? = null
    private var fullscreenParent: ViewGroup? = null
    private var pageCallback: CustomViewCallback? = null
    private var statusWasVisible = false
    private var navigationWasVisible = false
    private var previousBarsBehavior: Int? = null

    val isFullscreen: Boolean get() = fullscreenLayer != null

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: Message?
    ): Boolean = false

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null) {
            callback?.onCustomViewHidden()
            return
        }
        if (fullscreenLayer != null) {
            closeFullscreen(notifyWebPage = true)
        }
        val parent = (dialogWindow?.decorView as? ViewGroup) ?: fallbackContainer
        val layer = FrameLayout(browser.context).apply {
            setBackgroundColor(AndroidColor.BLACK)
            isClickable = true
            isFocusable = true
        }
        (view.parent as? ViewGroup)?.removeView(view)
        layer.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        fullscreenParent = parent
        fullscreenLayer = layer
        pageCallback = callback
        parent.addView(
            layer,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        layer.bringToFront()
        layer.requestFocus()

        dialogWindow?.let { window ->
            val oldInsets = ViewCompat.getRootWindowInsets(window.decorView)
            statusWasVisible = oldInsets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: false
            navigationWasVisible =
                oldInsets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: false
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            previousBarsBehavior = controller.systemBarsBehavior
            ImmersiveMode.enable(window, useCutout = true)
        }
        browser.visibility = View.INVISIBLE
        onExitChanged { exitFullscreenFromApp() }
    }

    override fun onHideCustomView() {
        closeFullscreen(notifyWebPage = false)
    }

    private fun exitFullscreenFromApp() {
        closeFullscreen(notifyWebPage = true)
    }

    fun dispose() {
        closeFullscreen(notifyWebPage = true)
    }

    private fun closeFullscreen(notifyWebPage: Boolean) {
        val layer = fullscreenLayer ?: return
        val callback = pageCallback
        fullscreenLayer = null
        pageCallback = null
        fullscreenParent?.removeView(layer)
        fullscreenParent = null
        layer.removeAllViews()

        dialogWindow?.let { window ->
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (statusWasVisible) controller.show(WindowInsetsCompat.Type.statusBars())
            if (navigationWasVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
            previousBarsBehavior?.let { controller.systemBarsBehavior = it }
            ImmersiveMode.enable(window, useCutout = true)
        }

        if (isBrowserRevealed()) {
            browser.visibility = View.VISIBLE
        }
        onExitChanged(null)
        if (notifyWebPage) {
            try {
                callback?.onCustomViewHidden()
            } catch (_: Exception) {
            }
        }
    }
}
