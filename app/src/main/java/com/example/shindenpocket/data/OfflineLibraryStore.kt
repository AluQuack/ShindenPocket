package com.example.shindenpocket.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.withContext

/**
 * Kopia danych do odczytu, rozdzielona według `userId` i zapisywana atomowo.
 * Nie zawiera sekretów i nie potwierdza aktywnej sesji ani stanu obejrzeń.
 */
object OfflineLibraryStore {
    data class Profile(val userId: Int, val name: String)
    data class CoverProgress(val completed: Int, val total: Int, val saved: Int)

    private const val PREFS = "shinden_offline_profile_v1"
    private const val LIBRARY_VERSION = 2
    private const val USER_ENTRIES_VERSION = 1
    private const val COMMENTS_VERSION = 1
    private const val MAX_CACHE_BYTES = 8L * 1024 * 1024
    private const val MAX_COVER_BYTES = 450_000
    private const val MAX_COVERS_TOTAL_BYTES = 55L * 1024 * 1024

    private val storeLock = Any()

    internal data class UserEntryOverride(
        val anime: Anime,
        val pendingSync: Boolean = true
    )

    private fun root(context: Context) = File(context.filesDir, "pocket_offline_8c")
    private fun userDir(context: Context, userId: Int) = File(root(context), "user_$userId")
    private fun libraryFile(context: Context, userId: Int) = File(userDir(context, userId), "library.json")
    private fun userEntriesFile(context: Context, userId: Int) = File(userDir(context, userId), "user_entries.json")
    private fun commentsFile(context: Context, userId: Int, animeId: Int) =
        File(userDir(context, userId), "comments_$animeId.json")
    private fun episodeFile(context: Context, userId: Int, animeId: Int) =
        File(userDir(context, userId), "episodes_$animeId.json")

    fun rememberProfile(context: Context, userId: Int, name: String) {
        if (userId <= 0 || name.isBlank()) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("last_user", userId).putString("last_name", name).apply()
    }

    fun lastProfile(context: Context): Profile? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getInt("last_user", -1)
        val name = p.getString("last_name", null).orEmpty()
        return if (id > 0 && name.isNotBlank() && libraryFile(context, id).isFile)
            Profile(id, name) else null
    }

    private fun atomicWrite(file: File, text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_CACHE_BYTES) return
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            temp.writeText(text, Charsets.UTF_8)
            if (!temp.renameTo(file)) {
                java.nio.file.Files.move(
                    temp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            temp.delete()
        }
    }

    private fun readJson(file: File): JSONObject? = try {
        if (!file.isFile || file.length() !in 1L..MAX_CACHE_BYTES) null
        else JSONObject(file.readText(Charsets.UTF_8))
    } catch (_: Exception) { null }

    internal fun animeToJson(a: Anime): JSONObject = JSONObject().apply {
        put("id", a.id)
        put("cover", a.coverId)
        put("title", a.title)
        put("watched", a.watched)
        put("total", a.total)
        put("favorite", a.favorite)
        put("watchStatus", a.watchStatus)
        put("status", a.status)
        put("description", a.description)
        put("rating", a.rating)
        put("rateTotal", a.rateTotal ?: JSONObject.NULL)
        put("rateStory", a.rateStory ?: JSONObject.NULL)
        put("rateGraphic", a.rateGraphic ?: JSONObject.NULL)
        put("rateMusic", a.rateMusic ?: JSONObject.NULL)
        put("rateCharacters", a.rateCharacters ?: JSONObject.NULL)
    }

    internal fun animeFromJson(item: JSONObject): Anime? {
        val id = item.optInt("id", 0)
        if (id <= 0) return null
        return Anime(
            id = id,
            coverId = item.optInt("cover"),
            title = item.optString("title"),
            watched = item.optInt("watched"),
            total = item.optInt("total"),
            favorite = item.optBoolean("favorite"),
            watchStatus = item.optString("watchStatus"),
            status = item.optString("status"),
            description = item.optString("description"),
            rating = item.optString("rating"),
            rateTotal = with(ShindenManagementApi) { item.optValidScore("rateTotal") },
            rateStory = with(ShindenManagementApi) { item.optValidScore("rateStory") },
            rateGraphic = with(ShindenManagementApi) { item.optValidScore("rateGraphic") },
            rateMusic = with(ShindenManagementApi) { item.optValidScore("rateMusic") },
            rateCharacters = with(ShindenManagementApi) { item.optValidScore("rateCharacters") }
        )
    }

    internal fun serializeLibraryJson(anime: List<Anime>): String {
        val list = JSONArray()
        anime.forEach { a -> list.put(animeToJson(a)) }
        return JSONObject().apply {
            put("version", LIBRARY_VERSION)
            put("savedAt", System.currentTimeMillis())
            put("items", list)
        }.toString()
    }

    internal fun deserializeLibraryJson(json: JSONObject): List<Anime>? {
        // Odczyt obsługuje format bez ocen użytkownika oraz format z ocenami.
        val version = json.optInt("version", 0)
        if (version != 1 && version != LIBRARY_VERSION) return null
        val items = json.optJSONArray("items") ?: return null
        return try {
            buildList {
                for (i in 0 until items.length()) {
                    animeFromJson(items.getJSONObject(i))?.let { add(it) }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun saveLibrary(context: Context, userId: Int, anime: List<Anime>) = withContext(Dispatchers.IO) {
        if (userId <= 0) return@withContext
        synchronized(storeLock) {
            atomicWrite(libraryFile(context, userId), serializeLibraryJson(anime))
        }
    }

    suspend fun loadLibrary(context: Context, userId: Int): List<Anime>? = withContext(Dispatchers.IO) {
        if (userId <= 0) return@withContext null
        synchronized(storeLock) {
            val file = libraryFile(context, userId)
            val json = readJson(file)
            val base = if (json != null) deserializeLibraryJson(json) else null
            val overrides = readUserEntriesMap(userEntriesFile(context, userId)).mapValues { it.value.anime }
            if (base == null && overrides.isEmpty()) return@withContext null
            val merged = mergeAnimeListWithOverrides(base.orEmpty(), overrides)
            if (json != null && json.optInt("version", 0) == 1) {
                // Po odczycie dane są zapisywane w bieżącym formacie `library.json`.
                runCatching { atomicWrite(file, serializeLibraryJson(merged)) }
            }
            merged
        }
    }

    private fun readUserEntriesMap(file: File): Map<Int, UserEntryOverride> {
        val json = readJson(file) ?: return emptyMap()
        if (json.optInt("version", 0) != USER_ENTRIES_VERSION) return emptyMap()
        val items = json.optJSONArray("items") ?: return emptyMap()
        val map = LinkedHashMap<Int, UserEntryOverride>()
        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            val anime = animeFromJson(obj) ?: continue
            // Brak pola `pendingSync` oznacza stan zsynchronizowany, aby dane z Shinden mogły go odświeżyć.
            
            val pendingSync = obj.optBoolean("pendingSync", false)
            map[anime.id] = UserEntryOverride(anime = anime, pendingSync = pendingSync)
        }
        return map
    }

    private fun writeUserEntriesMap(file: File, entries: Map<Int, UserEntryOverride>) {
        val array = JSONArray()
        entries.values.forEach { entry ->
            array.put(animeToJson(entry.anime).put("pendingSync", entry.pendingSync))
        }
        atomicWrite(
            file,
            JSONObject().apply {
                put("version", USER_ENTRIES_VERSION)
                put("savedAt", System.currentTimeMillis())
                put("items", array)
            }.toString()
        )
    }

    internal fun mergeAnimeListWithOverrides(
        remoteAnime: List<Anime>,
        overrides: Map<Int, Anime>
    ): List<Anime> {
        if (overrides.isEmpty()) return remoteAnime
        val result = ArrayList<Anime>(remoteAnime.size + overrides.size)
        val seenIds = HashSet<Int>()

        for (remote in remoteAnime) {
            seenIds.add(remote.id)
            val local = overrides[remote.id]
            if (local == null) {
                result.add(remote)
            } else if (!local.isInUserLibrary) {
                // Użytkownik ustawił "Bez statusu", usunął z ulubionych i wyczyścił oceny.
                continue
            } else {
                result.add(mergeSingleAnime(remote, local))
            }
        }

        for ((id, local) in overrides) {
            if (id !in seenIds && local.isInUserLibrary) {
                result.add(local)
            }
        }
        return result
    }

    private fun mergeSingleAnime(remote: Anime, local: Anime): Anime = remote.copy(
        watched = maxOf(remote.watched, local.watched),
        total = if (remote.total > 0) remote.total else local.total,
        coverId = if (remote.coverId > 0) remote.coverId else local.coverId,
        description = remote.description.ifBlank { local.description },
        rating = remote.rating.ifBlank { local.rating },
        status = remote.status.ifBlank { local.status },
        favorite = local.favorite,
        watchStatus = local.watchStatus,
        rateTotal = local.rateTotal,
        rateStory = local.rateStory,
        rateGraphic = local.rateGraphic,
        rateMusic = local.rateMusic,
        rateCharacters = local.rateCharacters
    )

    /**
     * Łączy dane serwera z lokalnymi zmianami. Wpisy `pendingSync` mają pierwszeństwo,
     * a pozycje bez statusu zachowują ocenę lub ulubione pomijane przez API listy.
     */
    internal fun reconcileRemoteWithOverrides(
        remoteAnime: List<Anime>,
        overrides: Map<Int, UserEntryOverride>
    ): Pair<List<Anime>, Map<Int, UserEntryOverride>> {
        if (overrides.isEmpty()) return remoteAnime to emptyMap()
        val result = ArrayList<Anime>(remoteAnime.size + overrides.size)
        val remainingOverrides = LinkedHashMap<Int, UserEntryOverride>()
        val seenIds = HashSet<Int>()

        for (remote in remoteAnime) {
            seenIds.add(remote.id)
            val entry = overrides[remote.id]
            when {
                entry == null -> result.add(remote)
                entry.pendingSync -> {
                    remainingOverrides[remote.id] = entry
                    if (entry.anime.isInUserLibrary) {
                        result.add(mergeSingleAnime(remote, entry.anime))
                    }
                }
                else -> {
                    // Dane serwera zastępują stan lokalny tylko po zakończonej synchronizacji.
                    result.add(remote)
                }
            }
        }

        for ((id, entry) in overrides) {
            if (id in seenIds) continue
            if (entry.pendingSync) {
                remainingOverrides[id] = entry
                if (entry.anime.isInUserLibrary) {
                    result.add(entry.anime)
                }
            } else if (entry.anime.watchStatus.isBlank() && (entry.anime.favorite || entry.anime.hasUserRating)) {
                // Pozycje "Bez statusu" posiadające wyłącznie ocenę lub ulubione nie zawsze występują w userlist API.
                remainingOverrides[id] = entry
                result.add(entry.anime)
            }
        }
        return result to remainingOverrides
    }

    suspend fun saveUserEntry(
        context: Context,
        userId: Int,
        anime: Anime,
        pendingSync: Boolean = true
    ): List<Anime> = withContext(Dispatchers.IO) {
        if (userId <= 0 || anime.id <= 0) return@withContext emptyList()
        synchronized(storeLock) {
            val entriesFile = userEntriesFile(context, userId)
            val entries = LinkedHashMap(readUserEntriesMap(entriesFile))
            if (!pendingSync && !anime.isInUserLibrary) {
                entries.remove(anime.id)
            } else {
                entries[anime.id] = UserEntryOverride(anime = anime, pendingSync = pendingSync)
            }
            writeUserEntriesMap(entriesFile, entries)

            val libFile = libraryFile(context, userId)
            val currentLib = readJson(libFile)?.let { deserializeLibraryJson(it) }.orEmpty()
            val updatedLib = mergeAnimeListWithOverrides(currentLib, entries.mapValues { it.value.anime })
                .let { list -> if (!anime.isInUserLibrary) list.filterNot { it.id == anime.id } else list }
            atomicWrite(libFile, serializeLibraryJson(updatedLib))
            updatedLib
        }
    }

    suspend fun markUserEntrySynced(context: Context, userId: Int, syncedAnime: Anime) =
        withContext(Dispatchers.IO) {
            if (userId <= 0 || syncedAnime.id <= 0) return@withContext
            synchronized(storeLock) {
                val entriesFile = userEntriesFile(context, userId)
                val entries = LinkedHashMap(readUserEntriesMap(entriesFile))
                val current = entries[syncedAnime.id]
                // Oznaczamy jako zsynchronizowane tylko wtedy, gdy w międzyczasie użytkownik nie wykonał nowszej zmiany.
                if (current != null && current.anime == syncedAnime) {
                    if (!syncedAnime.isInUserLibrary) {
                        entries.remove(syncedAnime.id)
                    } else {
                        entries[syncedAnime.id] = UserEntryOverride(anime = syncedAnime, pendingSync = false)
                    }
                    writeUserEntriesMap(entriesFile, entries)
                }
            }
        }

    /** Aktualizuje `library.json` danymi serwera bez tworzenia lokalnej zmiany `pendingSync`. */
    suspend fun updateCachedLibraryAnime(context: Context, userId: Int, serverAnime: Anime) =
        withContext(Dispatchers.IO) {
            if (userId <= 0 || serverAnime.id <= 0) return@withContext
            synchronized(storeLock) {
                val entriesFile = userEntriesFile(context, userId)
                val entries = LinkedHashMap(readUserEntriesMap(entriesFile))
                val existingOverride = entries[serverAnime.id]
                if (existingOverride?.pendingSync == true) {
                    return@synchronized
                }
                if (serverAnime.watchStatus.isBlank() && (serverAnime.favorite || serverAnime.hasUserRating)) {
                    entries[serverAnime.id] = UserEntryOverride(anime = serverAnime, pendingSync = false)
                    writeUserEntriesMap(entriesFile, entries)
                } else if (existingOverride != null) {
                    entries.remove(serverAnime.id)
                    writeUserEntriesMap(entriesFile, entries)
                }

                val libFile = libraryFile(context, userId)
                val currentLib = readJson(libFile)?.let { deserializeLibraryJson(it) }.orEmpty()
                val nextLib = if (serverAnime.isInUserLibrary) {
                    val idx = currentLib.indexOfFirst { it.id == serverAnime.id }
                    if (idx >= 0) {
                        currentLib.toMutableList().also { it[idx] = serverAnime }
                    } else {
                        listOf(serverAnime) + currentLib
                    }
                } else {
                    currentLib.filterNot { it.id == serverAnime.id }
                }
                if (nextLib != currentLib) {
                    atomicWrite(libFile, serializeLibraryJson(nextLib))
                }
            }
        }

    suspend fun loadUserEntry(context: Context, userId: Int, animeId: Int): Anime? =
        withContext(Dispatchers.IO) {
            if (userId <= 0 || animeId <= 0) return@withContext null
            synchronized(storeLock) {
                readUserEntriesMap(userEntriesFile(context, userId))[animeId]?.anime
                    ?: readJson(libraryFile(context, userId))
                        ?.let { deserializeLibraryJson(it) }
                        ?.firstOrNull { it.id == animeId }
            }
        }

    suspend fun hasPendingUserEntry(context: Context, userId: Int, animeId: Int): Boolean =
        withContext(Dispatchers.IO) {
            if (userId <= 0 || animeId <= 0) return@withContext false
            synchronized(storeLock) {
                readUserEntriesMap(userEntriesFile(context, userId))[animeId]?.pendingSync == true
            }
        }

    suspend fun syncPendingUserEntries(context: Context, userId: Int) = withContext(Dispatchers.IO) {
        if (userId <= 0) return@withContext
        val pendingList = synchronized(storeLock) {
            readUserEntriesMap(userEntriesFile(context, userId))
                .values
                .filter { it.pendingSync }
                .map { it.anime }
        }
        for (pending in pendingList) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try {
                val statusOpt = WatchStatusOption.fromCode(pending.watchStatus)
                if (statusOpt == WatchStatusOption.NONE && !pending.favorite && !pending.hasUserRating) {
                    ShindenManagementApi.deleteListEntry(pending.id)
                } else {
                    ShindenManagementApi.updateListEntry(
                        titleId = pending.id,
                        statusOption = statusOpt,
                        favourite = pending.favorite,
                        includeRatings = true,
                        rateTotal = pending.rateTotal,
                        rateStory = pending.rateStory,
                        rateGraphic = pending.rateGraphic,
                        rateMusic = pending.rateMusic,
                        rateCharacters = pending.rateCharacters
                    )
                }
                markUserEntrySynced(context, userId, pending)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Nieudana wysyłka pozostawia `pendingSync` do kolejnej próby.
            }
        }
    }

    suspend fun mergeWithUserEntries(
        context: Context,
        userId: Int,
        remoteAnime: List<Anime>
    ): List<Anime> = withContext(Dispatchers.IO) {
        if (userId <= 0) return@withContext remoteAnime
        synchronized(storeLock) {
            val entriesFile = userEntriesFile(context, userId)
            val overrides = readUserEntriesMap(entriesFile)
            val (reconciledList, remainingOverrides) = reconcileRemoteWithOverrides(remoteAnime, overrides)
            if (remainingOverrides != overrides) {
                writeUserEntriesMap(entriesFile, remainingOverrides)
            }
            reconciledList
        }
    }

    internal fun commentToJson(c: AnimeComment): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("animeId", c.animeId)
        put("authorId", c.authorId)
        put("authorName", c.authorName)
        put("avatarUrl", c.avatarUrl ?: JSONObject.NULL)
        put("rank", c.rank ?: JSONObject.NULL)
        put("body", c.body)
        put("createdAt", c.createdAt)
        put("edited", c.edited)
        put("likes", c.likes)
        put("parentId", c.parentId ?: JSONObject.NULL)
        put("isLocalOnly", c.isLocalOnly)
    }

    internal fun commentFromJson(obj: JSONObject, fallbackAnimeId: Int): AnimeComment? {
        val id = obj.optLong("id", 0L)
        val body = obj.optString("body", "").trim()
        if (id == 0L || body.isBlank()) return null
        return AnimeComment(
            id = id,
            animeId = obj.optInt("animeId", fallbackAnimeId),
            authorId = obj.optInt("authorId", 0),
            authorName = obj.optString("authorName", "Użytkownik"),
            avatarUrl = if (obj.isNull("avatarUrl")) null else obj.optString("avatarUrl").takeIf { it.isNotBlank() },
            rank = if (obj.isNull("rank")) null else obj.optString("rank").takeIf { it.isNotBlank() },
            body = body,
            createdAt = obj.optString("createdAt", ""),
            edited = obj.optBoolean("edited", false),
            likes = obj.optInt("likes", 0),
            parentId = if (obj.isNull("parentId")) null else obj.optLong("parentId", 0L).takeIf { it > 0L },
            isLocalOnly = obj.optBoolean("isLocalOnly", false)
        )
    }

    private data class StoredCommentsData(
        val cached: List<AnimeComment>,
        val own: List<AnimeComment>,
        val deletedIds: Set<Long>
    )

    private fun readStoredComments(file: File, animeId: Int): StoredCommentsData {
        val json = readJson(file) ?: return StoredCommentsData(emptyList(), emptyList(), emptySet())
        if (json.optInt("version", 0) != COMMENTS_VERSION) {
            return StoredCommentsData(emptyList(), emptyList(), emptySet())
        }
        val cachedArr = json.optJSONArray("cached")
        val ownArr = json.optJSONArray("own")
        val delArr = json.optJSONArray("deletedIds")
        val cached = buildList {
            if (cachedArr != null) {
                for (i in 0 until cachedArr.length()) {
                    cachedArr.optJSONObject(i)?.let { commentFromJson(it, animeId) }?.let { add(it) }
                }
            }
        }
        val own = buildList {
            if (ownArr != null) {
                for (i in 0 until ownArr.length()) {
                    ownArr.optJSONObject(i)?.let { commentFromJson(it, animeId) }?.let { add(it) }
                }
            }
        }
        val deleted = buildSet {
            if (delArr != null) {
                for (i in 0 until delArr.length()) {
                    val id = delArr.optLong(i, 0L)
                    if (id != 0L) add(id)
                }
            }
        }
        return StoredCommentsData(cached, own, deleted)
    }

    private fun writeStoredComments(file: File, data: StoredCommentsData) {
        val cachedArr = JSONArray().apply { data.cached.forEach { put(commentToJson(it)) } }
        val ownArr = JSONArray().apply { data.own.forEach { put(commentToJson(it)) } }
        val delArr = JSONArray().apply { data.deletedIds.forEach { put(it) } }
        atomicWrite(
            file,
            JSONObject().apply {
                put("version", COMMENTS_VERSION)
                put("savedAt", System.currentTimeMillis())
                put("cached", cachedArr)
                put("own", ownArr)
                put("deletedIds", delArr)
            }.toString()
        )
    }

    internal fun combineComments(
        remoteOrCached: List<AnimeComment>,
        own: List<AnimeComment>,
        deletedIds: Set<Long>
    ): List<AnimeComment> {
        val ownById = own.associateBy { it.id }
        val merged = ArrayList<AnimeComment>(own.size + remoteOrCached.size)
        val addedIds = HashSet<Long>()

        // Lokalne komentarze bez odpowiednika z serwera zachowują pierwszeństwo.
        for (ownComment in own) {
            if (ownComment.id !in deletedIds && remoteOrCached.none { it.id == ownComment.id }) {
                merged.add(ownComment)
                addedIds.add(ownComment.id)
            }
        }
        for (item in remoteOrCached) {
            if (item.id in deletedIds || item.id in addedIds) continue
            val overridden = ownById[item.id]
            merged.add(overridden ?: item)
            addedIds.add(item.id)
        }
        return merged
    }

    suspend fun loadComments(context: Context, userId: Int, animeId: Int): List<AnimeComment> =
        withContext(Dispatchers.IO) {
            if (userId <= 0 || animeId <= 0) return@withContext emptyList()
            synchronized(storeLock) {
                val stored = readStoredComments(commentsFile(context, userId, animeId), animeId)
                combineComments(stored.cached, stored.own, stored.deletedIds)
            }
        }

    suspend fun saveRemoteComments(
        context: Context,
        userId: Int,
        animeId: Int,
        remote: List<AnimeComment>
    ): List<AnimeComment> = withContext(Dispatchers.IO) {
        if (userId <= 0 || animeId <= 0) return@withContext remote
        synchronized(storeLock) {
            val file = commentsFile(context, userId, animeId)
            val stored = readStoredComments(file, animeId)
            val combined = combineComments(remote, stored.own, stored.deletedIds)
            writeStoredComments(file, stored.copy(cached = combined))
            combined
        }
    }

    suspend fun addOwnComment(
        context: Context,
        userId: Int,
        animeId: Int,
        comment: AnimeComment
    ): List<AnimeComment> = withContext(Dispatchers.IO) {
        if (userId <= 0 || animeId <= 0) return@withContext listOf(comment)
        synchronized(storeLock) {
            val file = commentsFile(context, userId, animeId)
            val stored = readStoredComments(file, animeId)
            val updatedOwn = listOf(comment) + stored.own.filterNot { it.id == comment.id }
            val updatedDeleted = stored.deletedIds - comment.id
            val combined = combineComments(stored.cached, updatedOwn, updatedDeleted)
            writeStoredComments(file, StoredCommentsData(combined, updatedOwn, updatedDeleted))
            combined
        }
    }

    suspend fun editOwnComment(
        context: Context,
        userId: Int,
        animeId: Int,
        commentId: Long,
        newBody: String
    ): List<AnimeComment> = withContext(Dispatchers.IO) {
        if (userId <= 0 || animeId <= 0) return@withContext emptyList()
        synchronized(storeLock) {
            val file = commentsFile(context, userId, animeId)
            val stored = readStoredComments(file, animeId)
            val trimmed = newBody.trim()
            val existing = stored.own.firstOrNull { it.id == commentId }
                ?: stored.cached.firstOrNull { it.id == commentId }
            val updatedComment = existing?.copy(body = trimmed, edited = true)
            val updatedOwn = if (updatedComment != null) {
                listOf(updatedComment) + stored.own.filterNot { it.id == commentId }
            } else stored.own
            val updatedCached = stored.cached.map {
                if (it.id == commentId) it.copy(body = trimmed, edited = true) else it
            }
            val combined = combineComments(updatedCached, updatedOwn, stored.deletedIds)
            writeStoredComments(file, StoredCommentsData(combined, updatedOwn, stored.deletedIds))
            combined
        }
    }

    suspend fun deleteOwnComment(
        context: Context,
        userId: Int,
        animeId: Int,
        commentId: Long
    ): List<AnimeComment> = withContext(Dispatchers.IO) {
        if (userId <= 0 || animeId <= 0) return@withContext emptyList()
        synchronized(storeLock) {
            val file = commentsFile(context, userId, animeId)
            val stored = readStoredComments(file, animeId)
            val updatedOwn = stored.own.filterNot { it.id == commentId }
            val updatedCached = stored.cached.filterNot { it.id == commentId }
            val updatedDeleted = stored.deletedIds + commentId
            val combined = combineComments(updatedCached, updatedOwn, updatedDeleted)
            writeStoredComments(file, StoredCommentsData(combined, updatedOwn, updatedDeleted))
            combined
        }
    }

    suspend fun saveEpisodes(context: Context, userId: Int, animeId: Int, data: TrackedEpisodesResult) =
        withContext(Dispatchers.IO) {
            if (userId <= 0 || animeId <= 0) return@withContext
            val array = JSONArray()
            data.items.forEach { ep ->
                array.put(JSONObject().apply {
                    put("id", ep.episodeId); put("no", ep.number); put("title", ep.title)
                    put("watched", ep.watched)
                    put("online", ep.hasOnlineVersion ?: JSONObject.NULL)
                    put("air", ep.airDate ?: JSONObject.NULL)
                    put("langs", JSONArray(ep.languageLabels))
                    put("url", ep.detailUrl ?: JSONObject.NULL)
                })
            }
            val payload = JSONObject().apply {
                put("version", 1); put("savedAt", System.currentTimeMillis())
                put("title", data.htmlTitle); put("checks", data.htmlChecks)
                put("times", data.htmlTimes); put("unknown", data.htmlUnknowns)
                put("items", array)
            }.toString()
            synchronized(storeLock) {
                atomicWrite(episodeFile(context, userId, animeId), payload)
            }
        }

    suspend fun loadEpisodes(context: Context, userId: Int, animeId: Int): TrackedEpisodesResult? =
        withContext(Dispatchers.IO) {
            if (userId <= 0 || animeId <= 0) return@withContext null
            val data = synchronized(storeLock) {
                readJson(episodeFile(context, userId, animeId))
            } ?: return@withContext null
            if (data.optInt("version") != 1) return@withContext null
            val items = data.optJSONArray("items") ?: return@withContext null
            try {
                val episodes = buildList {
                    for (i in 0 until items.length()) {
                        val ep = items.getJSONObject(i)
                        val id = ep.getInt("id")
                        if (id <= 0) continue
                        val langs = ep.optJSONArray("langs")
                        add(TrackedEpisode(
                            episodeId = id, number = ep.getString("no"), title = ep.optString("title"),
                            watched = ep.optBoolean("watched"),
                            hasOnlineVersion = if (ep.isNull("online")) null else ep.optBoolean("online"),
                            airDate = if (ep.isNull("air")) null else ep.optString("air"),
                            languageLabels = buildList {
                                if (langs != null) for (j in 0 until langs.length()) add(langs.optString(j))
                            },
                            detailUrl = if (ep.isNull("url")) null else ep.optString("url")
                        ))
                    }
                }
                TrackedEpisodesResult(episodes, data.optString("title"), data.optInt("checks"),
                    data.optInt("times"), data.optInt("unknown"))
            } catch (_: Exception) { null }
        }

    fun coverFile(context: Context, userId: Int?, coverId: Int): File? {
        if (userId == null || userId <= 0 || coverId <= 0) return null
        return File(userDir(context, userId), "covers/$coverId.jpg").takeIf { it.isFile && it.length() > 100 }
    }

    /** Ręcznie uruchamiane pobieranie okładek. Nie blokuje odświeżania biblioteki. */
    suspend fun downloadCovers(
        context: Context, userId: Int, anime: List<Anime>,
        onProgress: (CoverProgress) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (userId <= 0) return@withContext
        val ids = anime.map { it.coverId }.filter { it > 0 }.distinct()
        val dir = File(userDir(context, userId), "covers")
        dir.mkdirs()
        var used = dir.listFiles()?.sumOf { it.length() } ?: 0L
        var saved = 0
        ids.forEachIndexed { index, id ->
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (used < MAX_COVERS_TOTAL_BYTES && coverFile(context, userId, id) == null) {
                val connection = URL("https://shinden.pl/res/images/225x350/$id.jpg")
                    .openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 7000
                    connection.readTimeout = 7000
                    connection.setRequestProperty("Accept", "image/jpeg")
                    if (connection.responseCode == 200 &&
                        connection.contentType.orEmpty().lowercase().startsWith("image/jpeg") &&
                        connection.contentLengthLong in -1L..MAX_COVER_BYTES.toLong()
                    ) {
                        val bytes = connection.inputStream.use { stream ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (output.size() <= MAX_COVER_BYTES) {
                                val read = stream.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                            output.toByteArray()
                        }
                        if (bytes.size in 101..MAX_COVER_BYTES && bytes[0] == 0xFF.toByte() &&
                            bytes[1] == 0xD8.toByte() && used + bytes.size <= MAX_COVERS_TOTAL_BYTES
                        ) {
                            val file = File(dir, "$id.jpg")
                            val tmp = File(dir, "$id.tmp")
                            try {
                                tmp.writeBytes(bytes)
                                if (tmp.renameTo(file)) { used += bytes.size; saved++ }
                            } finally { tmp.delete() }
                        }
                    }
                } catch (_: IOException) {
                    // Jeden niedostępny obraz nie przerywa pozostałych.
                } finally { connection.disconnect() }
            }
            withContext(Dispatchers.Main) { onProgress(CoverProgress(index + 1, ids.size, saved)) }
        }
    }
}
