package com.example.shindenpocket.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Nowe wersje online wykryte od ostatniego skanu, a nie sam katalog odcinków. */
data class EpisodeScanChanges(
    val firstScan: Boolean,
    val newlyOnline: List<TrackedEpisode>
)

/** Wpis lokalnej historii; `isTest = true` oznacza próbę interfejsu, a nie premierę. */
data class EpisodeHistoryEvent(
    val titleId: Int,
    val episodeId: Int,
    val animeTitle: String,
    val episodeNumber: String,
    val episodeTitle: String,
    val detectedAt: Long,
    val isRead: Boolean,
    val isTest: Boolean
)

object EpisodeSnapshotStore {
    
    private const val PREFS_NAME = "shinden_episode_snapshots_v1"
    private const val SCHEMA_VERSION = 1
    private const val HISTORY_SCHEMA = 1
    private const val HISTORY_LIMIT = 80

    private data class SavedEpisode(
        val knownOnline: Boolean?,
        val everOnline: Boolean
    )

    suspend fun compareAndSave(
        context: Context,
        userId: Int,
        titleId: Int,
        current: TrackedEpisodesResult
    ): EpisodeScanChanges = withContext(Dispatchers.IO) {
        compareAndSaveSync(context.applicationContext, userId, titleId, current)
    }

    /** Wspólna blokada chroni historię przed utratą wpisów przy równoległym zapisie z UI i WorkManagera. */
    @Synchronized
    private fun compareAndSaveSync(
        context: Context,
        userId: Int,
        titleId: Int,
        current: TrackedEpisodesResult
    ): EpisodeScanChanges {
        require(userId > 0 && titleId > 0) { "Nieprawidłowe ID konta lub anime" }
        if (current.items.isNotEmpty() && current.items.none { it.hasOnlineVersion != null }) {
            throw IOException("Nie zapisano skanu: brak potwierdzonych statusów online")
        }
        if (current.items.map { it.episodeId }.toSet().size != current.items.size) {
            throw IOException("Powtórzone ID odcinka — nie zapisano skanu")
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val snapshotKey = "account_${userId}_title_$titleId"
        val rawPrevious = prefs.getString(snapshotKey, null)
        val previous: Map<Int, SavedEpisode>? = if (rawPrevious == null) null else {
            try {
                val root = JSONObject(rawPrevious)
                if (root.optInt("version", -1) != SCHEMA_VERSION) {
                    throw IOException("Nieobsługiwana wersja historii skanów")
                }
                val arr = root.optJSONArray("episodes")
                    ?: throw IOException("Brak zapisanej listy odcinków")
                buildMap {
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val id = obj.optInt("id", -1)
                        if (id <= 0 || containsKey(id)) throw IOException("Błędny zapis odcinków")
                        val known = obj.opt("knownOnline") as? Boolean
                        put(id, SavedEpisode(known, obj.optBoolean("everOnline", false)))
                    }
                }
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("Nie można odczytać historii skanów — nie nadpisuję jej", e)
            }
        }

        // Pierwszy skan tworzy punkt odniesienia bez powiadomień i wpisów historii.
        val newOnline = if (previous == null) emptyList() else current.items.filter { episode ->
            val old = previous[episode.episodeId]
            episode.hasOnlineVersion == true && !episode.watched &&
                old?.everOnline != true && (old == null || old.knownOnline == false)
        }

        // Odcinek z datą emisji w przyszłości nie może mieć wersji online.
        // Dla niewyemitowanych odcinków zerujemy `everOnline`, aby nie pominąć właściwej premiery.
        
        
        val unreleasedIds = current.items
            .filter { it.hasOnlineVersion != true && ShindenEpisodeListParser.isFutureAirDate(it.airDate) }
            .map { it.episodeId }
            .toSet()

        // Wpis pozostaje w snapshotcie, gdy API tymczasowo ukryje odcinek.
        val merged = previous.orEmpty().toMutableMap()
        current.items.forEach { episode ->
            val old = merged[episode.episodeId]
            merged[episode.episodeId] = SavedEpisode(
                knownOnline = episode.hasOnlineVersion ?: old?.knownOnline,
                everOnline = if (episode.episodeId in unreleasedIds) false
                else old?.everOnline == true || episode.hasOnlineVersion == true
            )
        }
        val snapshotArray = JSONArray()
        merged.toSortedMap().forEach { (id, state) ->
            snapshotArray.put(JSONObject().apply {
                put("id", id)
                put("knownOnline", state.knownOnline ?: JSONObject.NULL)
                put("everOnline", state.everOnline)
            })
        }
        val snapshotPayload = JSONObject().apply {
            put("version", SCHEMA_VERSION)
            put("checkedAt", System.currentTimeMillis())
            put("episodes", snapshotArray)
        }.toString()

        // Snapshot i historia są zapisywane jednym `commit()`, aby awaria zapisu nie zgubiła premiery między skanami.
        
        val editor = prefs.edit().putString(snapshotKey, snapshotPayload)
        val historyKey = historyKey(userId)
        val oldHistory = readHistory(prefs.getString(historyKey, null))
        // Historia nie przechowuje wpisów odcinków, które według Shinden nie miały jeszcze emisji.
        
        val cleanedHistory = oldHistory.filterNot { event ->
            !event.isTest && event.titleId == titleId && event.episodeId in unreleasedIds
        }
        val alreadyRecorded = cleanedHistory.map { it.titleId to it.episodeId }.toSet()
        val now = System.currentTimeMillis()
        val additions = newOnline.filter { (titleId to it.episodeId) !in alreadyRecorded }
            .map { episode ->
                EpisodeHistoryEvent(
                    titleId = titleId,
                    episodeId = episode.episodeId,
                    animeTitle = current.htmlTitle.trim().ifEmpty { "Anime #$titleId" },
                    episodeNumber = episode.number,
                    episodeTitle = episode.title,
                    detectedAt = now,
                    isRead = false,
                    isTest = false
                )
            }
        if (additions.isNotEmpty() || cleanedHistory.size != oldHistory.size) {
            editor.putString(historyKey,
                writeHistory((additions + cleanedHistory).sortedByDescending { it.detectedAt }))
        }
        if (!editor.commit()) throw IOException("Nie udało się zapisać historii na urządzeniu")

        return EpisodeScanChanges(
            firstScan = previous == null,
            newlyOnline = newOnline.sortedWith(
                compareBy({ it.number.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.episodeId })
            )
        )
    }

    /** Zwraca historię wspólną dla skanów ręcznych i WorkManagera. */
    @Synchronized
    fun getRecentHistory(context: Context, userId: Int): List<EpisodeHistoryEvent> {
        require(userId > 0)
        return readHistory(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(historyKey(userId), null))
    }

    @Synchronized
    fun markHistoryRead(context: Context, userId: Int) {
        require(userId > 0)
        updateHistory(context, userId) { history -> history.map { it.copy(isRead = true) } }
    }

    /** Oznacza jako przeczytany tylko wskazany wpis historii. */
    @Synchronized
    fun markHistoryEpisodeRead(context: Context, userId: Int, titleId: Int, episodeId: Int) {
        require(userId > 0 && titleId > 0 && episodeId > 0)
        updateHistory(context, userId) { history ->
            history.map { event ->
                if (!event.isTest && event.titleId == titleId && event.episodeId == episodeId) {
                    event.copy(isRead = true)
                } else event
            }
        }
    }

    private fun updateHistory(
        context: Context,
        userId: Int,
        change: (List<EpisodeHistoryEvent>) -> List<EpisodeHistoryEvent>
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = historyKey(userId)
        val updated = change(readHistory(prefs.getString(key, null)))
        if (!prefs.edit().putString(key, writeHistory(updated)).commit()) {
            throw IOException("Nie udało się zapisać historii nowych odcinków")
        }
    }

    private fun historyKey(userId: Int) = "account_${userId}_detected_online_v1"

    private fun readHistory(raw: String?): List<EpisodeHistoryEvent> {
        if (raw == null) return emptyList()
        try {
            val root = JSONObject(raw)
            if (root.optInt("version", -1) != HISTORY_SCHEMA) {
                throw IOException("Nieznana wersja historii wykrytych odcinków")
            }
            val items = root.optJSONArray("events")
                ?: throw IOException("Niepoprawny format historii wykrytych odcinków")
            val result = ArrayList<EpisodeHistoryEvent>(items.length())
            for (i in 0 until items.length()) {
                val obj = items.getJSONObject(i)
                val test = obj.optBoolean("test", false)
                val id = obj.optInt("episodeId", -1)
                val titleId = obj.optInt("titleId", -1)
                if (id < 0 || titleId < 0 || (!test && (id == 0 || titleId == 0))) {
                    throw IOException("Niepoprawny identyfikator w historii")
                }
                result.add(
                    EpisodeHistoryEvent(
                        titleId = titleId,
                        episodeId = id,
                        animeTitle = obj.optString("animeTitle", ""),
                        episodeNumber = obj.optString("episodeNumber", "?"),
                        episodeTitle = obj.optString("episodeTitle", ""),
                        detectedAt = obj.optLong("detectedAt", 0L),
                        isRead = obj.optBoolean("read", false),
                        isTest = test
                    )
                )
            }
            return result.sortedByDescending { it.detectedAt }
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            // Nie nadpisujemy uszkodzonej historii: jawny błąd do zdiagnozowania.
            throw IOException("Nie można odczytać historii nowych odcinków", e)
        }
    }

    private fun writeHistory(events: List<EpisodeHistoryEvent>): String {
        val items = JSONArray()
        events.sortedByDescending { it.detectedAt }.take(HISTORY_LIMIT).forEach { event ->
            items.put(JSONObject().apply {
                put("titleId", event.titleId)
                put("episodeId", event.episodeId)
                put("animeTitle", event.animeTitle)
                put("episodeNumber", event.episodeNumber)
                put("episodeTitle", event.episodeTitle)
                put("detectedAt", event.detectedAt)
                put("read", event.isRead)
                put("test", event.isTest)
            })
        }
        return JSONObject().apply {
            put("version", HISTORY_SCHEMA)
            put("events", items)
        }.toString()
    }
}
