package com.example.shindenpocket.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lokalny zapis uruchomionych odcinków, niezależny od potwierdzonego stanu obejrzenia.
 * Dane są rozdzielone według `userId` i nie zawierają sekretów ani pozycji odtwarzania.
 */
data class RecentPlayback(
    val animeId: Int,
    val episodeId: Int,
    val episodeNumber: String?,
    val service: String?
)

object PlaybackPreferences {
    private const val NAME = "shinden_pocket_playback_v1"
    private const val MAX_RECENT = 12
    private fun pref(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
    private fun recentKey(userId: Int) = "recent_v2_$userId"

    // Tryb wyboru źródła: dla anime, globalny albo pierwsze dostępne.
    fun sourceMode(context: Context, userId: Int): String =
        if (userId <= 0) "anime" else pref(context).getString("source_mode_$userId", "anime")
            ?.takeIf { it in setOf("anime", "global", "first") } ?: "anime"

    fun setSourceMode(context: Context, userId: Int, mode: String) {
        if (userId > 0 && mode in setOf("anime", "global", "first"))
            pref(context).edit().putString("source_mode_$userId", mode).apply()
    }

    fun askOnNext(context: Context, userId: Int): Boolean =
        userId > 0 && pref(context).getBoolean("ask_next_$userId", true)

    fun setAskOnNext(context: Context, userId: Int, value: Boolean) {
        if (userId > 0) pref(context).edit().putBoolean("ask_next_$userId", value).apply()
    }

    fun askOnClose(context: Context, userId: Int): Boolean =
        userId > 0 && pref(context).getBoolean("ask_close_$userId", true)

    fun setAskOnClose(context: Context, userId: Int, value: Boolean) {
        if (userId > 0) pref(context).edit().putBoolean("ask_close_$userId", value).apply()
    }

    fun defaultCompact(context: Context, userId: Int?): Boolean =
        if (userId == null || userId <= 0) true else pref(context).getBoolean("compact_$userId", true)

    fun setDefaultCompact(context: Context, userId: Int, value: Boolean) {
        if (userId > 0) pref(context).edit().putBoolean("compact_$userId", value).apply()
    }

    /** Zwraca globalną preferencję używaną przez ekrany bez kontekstu anime. */
    fun preferredService(context: Context, userId: Int): String? =
        if (userId <= 0) null else pref(context).getString("service_$userId", null)
            ?.takeIf { it.isNotBlank() }

    /** Preferencja anime z wartością globalną jako fallbackiem. */
    fun preferredService(context: Context, userId: Int, animeId: Int): String? =
        if (userId <= 0 || animeId <= 0) null else when (sourceMode(context, userId)) {
            "first" -> null
            "global" -> preferredService(context, userId)
            else -> pref(context).getString("service_${userId}_$animeId", null)
                ?.takeIf { it.isNotBlank() } ?: preferredService(context, userId)
        }

    fun saveService(context: Context, userId: Int, service: String) {
        if (userId > 0 && service.isNotBlank()) {
            pref(context).edit().putString("service_$userId", service).apply()
        }
    }

    /** Zapisuje źródło dla anime i jako bieżącą preferencję globalną. */
    fun saveService(context: Context, userId: Int, animeId: Int, service: String) {
        if (userId <= 0 || animeId <= 0 || service.isBlank()) return
        val current = recent(context, userId)
        val updated = current.map {
            if (it.animeId == animeId) it.copy(service = service) else it
        }
        pref(context).edit()
            .putString("service_$userId", service)
            .putString("service_${userId}_$animeId", service)
            .putString(recentKey(userId), encode(updated))
            .apply()
    }

    fun lastEpisode(context: Context, userId: Int, animeId: Int): Int? =
        if (userId <= 0 || animeId <= 0) null else
            pref(context).getInt("episode_${userId}_$animeId", -1).takeIf { it > 0 }

    fun lastAnime(context: Context, userId: Int): Int? =
        if (userId <= 0) null else
            pref(context).getInt("anime_$userId", -1).takeIf { it > 0 }

    /** Ostatnio otwarte anime, od najnowszego; uwzględnia także pojedynczy zapis bez listy. */
    fun recent(context: Context, userId: Int): List<RecentPlayback> {
        if (userId <= 0) return emptyList()
        val json = pref(context).getString(recentKey(userId), null)
        if (json.isNullOrBlank()) {
            val animeId = lastAnime(context, userId) ?: return emptyList()
            val episodeId = lastEpisode(context, userId, animeId) ?: return emptyList()
            return listOf(RecentPlayback(animeId, episodeId, null, preferredService(context, userId)))
        }
        return try {
            val array = JSONArray(json)
            val seen = HashSet<Int>()
            buildList {
                for (index in 0 until minOf(array.length(), 40)) {
                    val item = array.optJSONObject(index) ?: continue
                    val animeId = item.optInt("anime", -1)
                    val episodeId = item.optInt("episode", -1)
                    if (animeId <= 0 || episodeId <= 0 || !seen.add(animeId)) continue
                    add(RecentPlayback(
                        animeId = animeId,
                        episodeId = episodeId,
                        episodeNumber = item.optString("number").takeIf { it.isNotBlank() },
                        service = item.optString("service").takeIf { it.isNotBlank() }
                    ))
                    if (size >= MAX_RECENT) break
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Wywoływane dopiero po faktycznym otwarciu odtwarzacza. */
    fun saveOpened(
        context: Context,
        userId: Int,
        animeId: Int,
        episodeId: Int,
        episodeNumber: String? = null,
        service: String? = null
    ) {
        if (userId <= 0 || animeId <= 0 || episodeId <= 0) return
        val effectiveService = service?.takeIf { it.isNotBlank() }
            ?: preferredService(context, userId, animeId)
        val history = listOf(
            RecentPlayback(animeId, episodeId, episodeNumber, effectiveService)
        ) + recent(context, userId).filterNot { it.animeId == animeId }
        pref(context).edit()
            .putInt("episode_${userId}_$animeId", episodeId)
            .putInt("anime_$userId", animeId)
            .putString(recentKey(userId), encode(history.take(MAX_RECENT)))
            .apply()
    }

    private fun encode(items: List<RecentPlayback>): String {
        val array = JSONArray()
        items.take(MAX_RECENT).forEach { item ->
            array.put(JSONObject().apply {
                put("anime", item.animeId)
                put("episode", item.episodeId)
                item.episodeNumber?.let { put("number", it) }
                item.service?.let { put("service", it) }
            })
        }
        return array.toString()
    }
}
