package com.example.shindenpocket.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.shindenpocket.MainActivity
import com.example.shindenpocket.R
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/** Powiadamia wyłącznie o wersjach online wykrytych od poprzedniego snapshotu. */
internal object EpisodeNotification {
    private const val CHANNEL_ID = "shinden_new_episodes_v1"
    private const val ACTION_OPEN_ANIME = "com.example.shindenpocket.OPEN_ANIME"
    private const val EXTRA_USER_ID = "com.example.shindenpocket.extra.USER_ID"
    private const val EXTRA_ANIME_ID = "com.example.shindenpocket.extra.ANIME_ID"
    private const val EXTRA_EPISODE_ID = "com.example.shindenpocket.extra.EPISODE_ID"

    private const val POSTER_WIDTH_PX = 204
    private const val POSTER_HEIGHT_PX = 288
    private const val POSTER_CORNER_RADIUS_PX = 24f

    /** Jednorazowe żądanie otwarcia anime (opcjonalnie z konkretnym odcinkiem); nigdy nie przyjmuje danych innego konta. */
    data class Target(
        val userId: Int,
        val animeId: Int,
        val episodeId: Int? = null
    )

    internal data class FormattedNotificationContent(
        val badgeText: String,
        val metaText: String,
        val collapsedSubtitle: String,
        val episodePillText: String,
        val expandedEpisodeTitle: String,
        val hintText: String,
        val watchButtonText: String,
        val bigWatchButtonText: String,
        val targetEpisodeId: Int?
    )

    fun readTarget(intent: Intent?): Target? {
        if (intent?.action != ACTION_OPEN_ANIME) return null
        val userId = intent.getIntExtra(EXTRA_USER_ID, -1)
        val animeId = intent.getIntExtra(EXTRA_ANIME_ID, -1)
        if (userId <= 0 || animeId <= 0) return null
        val rawEpisodeId = intent.getIntExtra(EXTRA_EPISODE_ID, -1)
        val episodeId = rawEpisodeId.takeIf { it > 0 }
        // Parametry są akceptowane tylko dla bieżącego konta i znanej akcji.
        val expectedUri = if (episodeId != null) {
            Uri.parse("shindenpocket://anime/$userId/$animeId/episode/$episodeId")
        } else {
            Uri.parse("shindenpocket://anime/$userId/$animeId")
        }
        if (intent.data != expectedUri) return null
        return Target(userId = userId, animeId = animeId, episodeId = episodeId)
    }

    fun clearTarget(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_ANIME) {
            intent.removeExtra(EXTRA_USER_ID)
            intent.removeExtra(EXTRA_ANIME_ID)
            intent.removeExtra(EXTRA_EPISODE_ID)
            intent.action = null
            intent.data = null
        }
    }

    private fun animeIntent(
        context: Context,
        userId: Int,
        animeId: Int,
        episodeId: Int? = null
    ): Intent {
        require(userId > 0 && animeId > 0)
        val validEpisodeId = episodeId?.takeIf { it > 0 }
        return Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_ANIME
            // URI odróżnia PendingIntenty dla różnych kont, anime i odcinków.
            data = if (validEpisodeId != null) {
                Uri.parse("shindenpocket://anime/$userId/$animeId/episode/$validEpisodeId")
            } else {
                Uri.parse("shindenpocket://anime/$userId/$animeId")
            }
            putExtra(EXTRA_USER_ID, userId)
            putExtra(EXTRA_ANIME_ID, animeId)
            if (validEpisodeId != null) {
                putExtra(EXTRA_EPISODE_ID, validEpisodeId)
            }
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
    }

    internal fun formatContent(
        episodes: List<TrackedEpisode>,
        isPreview: Boolean = false
    ): FormattedNotificationContent {
        val primary = episodes.firstOrNull { !it.watched } ?: episodes.first()
        val targetEpisodeId = primary.episodeId.takeIf { it > 0 }

        if (episodes.size == 1) {
            val epNum = primary.number.trim().ifBlank { "?" }
            val cleanTitle = cleanEpisodeTitle(primary)
            val langs = primary.languageLabels
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString("/")

            val airDate = primary.airDate?.trim().orEmpty()
            return FormattedNotificationContent(
                badgeText = "NOWY ODCINEK",
                metaText = langs,
                collapsedSubtitle = if (cleanTitle != null) {
                    "Odc. $epNum • $cleanTitle"
                } else {
                    "Odcinek $epNum • Dostępny online"
                },
                episodePillText = "Odcinek $epNum",
                expandedEpisodeTitle = cleanTitle ?: "Nowa wersja online gotowa do odtworzenia",
                hintText = when {
                    isPreview && airDate.isNotEmpty() -> "Podgląd • Premiera: $airDate"
                    isPreview -> "Podgląd powiadomienia"
                    airDate.isNotEmpty() -> "Premiera: $airDate"
                    else -> "Dostępny online w Shinden Pocket"
                },
                watchButtonText = "▶ Oglądaj",
                bigWatchButtonText = "▶ Oglądaj odc. $epNum",
                targetEpisodeId = targetEpisodeId
            )
        }

        val numbers = episodes
            .map { it.number.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")
        val firstNum = primary.number.trim().ifBlank { "?" }
        val firstCleanTitle = cleanEpisodeTitle(primary)
        val langs = episodes
            .flatMap { it.languageLabels }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("/")

        return FormattedNotificationContent(
            badgeText = "NOWE ODCINKI (${episodes.size})",
            metaText = langs,
            collapsedSubtitle = "Odcinki: $numbers",
            episodePillText = "Odc. $numbers",
            expandedEpisodeTitle = if (firstCleanTitle != null) {
                "Od odc. $firstNum: $firstCleanTitle"
            } else {
                "Wykryto ${episodes.size} nowych odcinków online"
            },
            hintText = if (isPreview) {
                "Podgląd • Nowe wersje online (${episodes.size})"
            } else {
                "Dostępne nowe wersje online (${episodes.size})"
            },
            watchButtonText = "▶ Oglądaj",
            bigWatchButtonText = "▶ Oglądaj odc. $firstNum",
            targetEpisodeId = targetEpisodeId
        )
    }

    private fun cleanEpisodeTitle(episode: TrackedEpisode): String? {
        val raw = episode.title.trim()
        if (raw.isEmpty() || raw == "-") return null
        val num = episode.number.trim()
        if (raw.equals("Odcinek $num", ignoreCase = true) ||
            raw.equals("Episode $num", ignoreCase = true) ||
            raw.equals("Odc. $num", ignoreCase = true) ||
            raw.equals(num, ignoreCase = true)
        ) {
            return null
        }
        return raw
    }

    @SuppressLint("MissingPermission")
    fun show(context: Context, userId: Int, anime: Anime, episodes: List<TrackedEpisode>) {
        if (episodes.isEmpty() || userId <= 0 || anime.id <= 0) return
        postEpisodeNotification(
            context = context,
            userId = userId,
            anime = anime,
            episodes = episodes,
            notificationId = anime.id,
            isPreview = false
        )
    }

    /** Wyświetla testowe powiadomienie dla wybranej serii z biblioteki użytkownika. */
    @SuppressLint("MissingPermission")
    fun showPreview(
        context: Context,
        userId: Int,
        anime: Anime,
        episode: TrackedEpisode? = null
    ): Boolean {
        if (userId <= 0 || anime.id <= 0) return false
        val previewEpisode = episode ?: TrackedEpisode(
            episodeId = -1,
            number = (anime.watched + 1).coerceAtLeast(1).toString(),
            title = "Nowa wersja online w Shinden Pocket",
            watched = false,
            hasOnlineVersion = true,
            airDate = "Dzisiaj",
            languageLabels = listOf("PL"),
            detailUrl = ""
        )
        return postEpisodeNotification(
            context = context,
            userId = userId,
            anime = anime,
            episodes = listOf(previewEpisode),
            notificationId = 91920,
            isPreview = true
        )
    }

    @SuppressLint("MissingPermission")
    private fun postEpisodeNotification(
        context: Context,
        userId: Int,
        anime: Anime,
        episodes: List<TrackedEpisode>,
        notificationId: Int,
        isPreview: Boolean
    ): Boolean {
        val app = context.applicationContext
        if (!canPostNotifications(app)) return false
        val notificationManager = NotificationManagerCompat.from(app)
        ensureChannel(app)

        val content = formatContent(episodes = episodes, isPreview = isPreview)
        val posterBitmap = resolvePosterBitmap(app, userId, anime)

        val openEpisodeIntent = animeIntent(
            context = app,
            userId = userId,
            animeId = anime.id,
            episodeId = content.targetEpisodeId
        )
        val requestCode = ((anime.id * 31 + userId) * 31) + (content.targetEpisodeId ?: 0)
        val openEpisodePendingIntent = PendingIntent.getActivity(
            app,
            requestCode,
            openEpisodeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val collapsedView = RemoteViews(app.packageName, R.layout.notification_episode_collapsed).apply {
            setImageViewBitmap(R.id.notif_cover, posterBitmap)
            setTextViewText(R.id.notif_badge, content.badgeText)
            setTextViewText(R.id.notif_anime_title, anime.title)
            setTextViewText(R.id.notif_episode_subtitle, content.collapsedSubtitle)
            setTextViewText(R.id.notif_watch_btn, content.watchButtonText)
            setOnClickPendingIntent(R.id.notif_watch_btn, openEpisodePendingIntent)
        }

        val expandedView = RemoteViews(app.packageName, R.layout.notification_episode_expanded).apply {
            setImageViewBitmap(R.id.notif_big_cover, posterBitmap)
            setTextViewText(R.id.notif_big_badge, content.badgeText)
            if (content.metaText.isNotEmpty()) {
                setViewVisibility(R.id.notif_big_meta, View.VISIBLE)
                setTextViewText(R.id.notif_big_meta, content.metaText)
            } else {
                setViewVisibility(R.id.notif_big_meta, View.GONE)
            }
            setTextViewText(R.id.notif_big_anime_title, anime.title)
            setTextViewText(R.id.notif_big_episode_pill, content.episodePillText)
            setTextViewText(R.id.notif_big_episode_title, content.expandedEpisodeTitle)
            setTextViewText(R.id.notif_big_hint, content.hintText)
            setTextViewText(R.id.notif_big_watch_btn, content.bigWatchButtonText)
            setOnClickPendingIntent(R.id.notif_big_watch_btn, openEpisodePendingIntent)
        }

        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_episode)
            .setColor(0xFFA855F7.toInt())
            .setContentTitle(anime.title)
            .setContentText(content.collapsedSubtitle)
            .setCustomContentView(collapsedView)
            .setCustomBigContentView(expandedView)
            .setCustomHeadsUpContentView(collapsedView)
            .setContentIntent(openEpisodePendingIntent)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!isPreview)
            .build()

        // ID anime: kolejne alerty dla tego samego tytułu zastąpią poprzedni.
        return try {
            notificationManager.notify(notificationId, notification)
            true
        } catch (_: SecurityException) {
            // Uprawnienie mogło zostać cofnięte w trakcie skanu.
            false
        }
    }

    private fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun resolvePosterBitmap(context: Context, userId: Int, anime: Anime): Bitmap {
        val raw = loadRawCoverBitmap(context, userId, anime.coverId)
        return createStyledPosterBitmap(raw, anime.title)
    }

    private fun loadRawCoverBitmap(context: Context, userId: Int, coverId: Int): Bitmap? {
        if (coverId <= 0) return null
        try {
            val localFile = OfflineLibraryStore.coverFile(context, userId, coverId)
            if (localFile != null && localFile.isFile && localFile.length() > 0L) {
                BitmapFactory.decodeFile(localFile.absolutePath)?.let { return it }
            }
        } catch (_: Exception) {
            // W razie problemu z plikiem lokalnym próbujemy pobrać z sieci lub użyć fallbacku.
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return null
        }

        val url = "https://shinden.pl/res/images/225x350/$coverId.jpg"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4500
                readTimeout = 4500
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android) ShindenPocket")
            }
            if (connection.responseCode in 200..299) {
                val bytes = connection.inputStream.use { it.readBytes() }
                if (bytes.isNotEmpty()) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                } else {
                    null
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun createStyledPosterBitmap(source: Bitmap?, title: String): Bitmap {
        val output = Bitmap.createBitmap(
            POSTER_WIDTH_PX,
            POSTER_HEIGHT_PX,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(output)
        val rect = RectF(0f, 0f, POSTER_WIDTH_PX.toFloat(), POSTER_HEIGHT_PX.toFloat())
        val radius = POSTER_CORNER_RADIUS_PX

        if (source != null && source.width > 0 && source.height > 0) {
            val scale = max(
                POSTER_WIDTH_PX.toFloat() / source.width.toFloat(),
                POSTER_HEIGHT_PX.toFloat() / source.height.toFloat()
            )
            val dx = (POSTER_WIDTH_PX - source.width * scale) * 0.5f
            val dy = (POSTER_HEIGHT_PX - source.height * scale) * 0.5f
            val shaderMatrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(dx, dy)
            }
            val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(shaderMatrix)
            }
            val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                this.shader = shader
            }
            canvas.drawRoundRect(rect, radius, radius, imagePaint)

            
            val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.shader = LinearGradient(
                    0f,
                    POSTER_HEIGHT_PX * 0.65f,
                    0f,
                    POSTER_HEIGHT_PX.toFloat(),
                    0x00000000,
                    0x6609090D,
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(rect, radius, radius, vignettePaint)
        } else {
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f,
                    0f,
                    POSTER_WIDTH_PX.toFloat(),
                    POSTER_HEIGHT_PX.toFloat(),
                    intArrayOf(0xFF341B52.toInt(), 0xFF1A1429.toInt(), 0xFF0E0D16.toInt()),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
            canvas.drawRoundRect(rect, radius, radius, bgPaint)

            val badgeCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x38A855F7
                style = Paint.Style.FILL
            }
            val cx = POSTER_WIDTH_PX * 0.5f
            val cy = POSTER_HEIGHT_PX * 0.44f
            canvas.drawCircle(cx, cy, 42f, badgeCirclePaint)

            val initials = extractInitials(title)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFF6EDFF.toInt()
                textAlign = Paint.Align.CENTER
                textSize = 34f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val textOffset = (textPaint.descent() + textPaint.ascent()) * 0.5f
            canvas.drawText(initials, cx, cy - textOffset, textPaint)

            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFC084FC.toInt()
                textAlign = Paint.Align.CENTER
                textSize = 18f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            canvas.drawText("SHINDEN", cx, POSTER_HEIGHT_PX * 0.80f, labelPaint)
        }

        
        val borderInset = 1.5f
        val borderRect = RectF(
            borderInset,
            borderInset,
            POSTER_WIDTH_PX - borderInset,
            POSTER_HEIGHT_PX - borderInset
        )
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x66A855F7
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(borderRect, radius, radius, borderPaint)

        return output
    }

    private fun extractInitials(title: String): String {
        val words = title
            .trim()
            .split(Regex("\\s+"))
            .mapNotNull { token -> token.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
        return when {
            words.size >= 2 -> "${words[0]}${words[1]}"
            words.size == 1 -> "${words[0]}"
            else -> "▶"
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Nowe odcinki anime", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Nowe wersje online obserwowanych anime według Shindena" }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}
