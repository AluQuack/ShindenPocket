package com.example.shindenpocket.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Skanuje wyłącznie obserwowane anime i porównuje wyniki z zapisanymi snapshotami. */
class EpisodeBackgroundWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        // Wspólna blokada zapobiega równoległym skanom okresowym i testowym.
        private val scanMutex = Mutex()
    }

    override suspend fun doWork(): Result = scanMutex.withLock { runScan() }

    private suspend fun runScan(): Result {
        val app = applicationContext
        val expectedUserId = EpisodeBackgroundScheduler.enabledUserId(app)
            ?: return Result.success()

        try {
            // Worker działający w osobnym procesie odtwarza zaszyfrowaną sesję.
            // Dane WorkManager nie zawierają cookies ani JWT.
            val session = ShindenAuth.restoreSession(app)
            if (session == null) {
                EpisodeBackgroundScheduler.report(
                    app, expectedUserId, "Nie mozna potwierdzic sesji Shindena. Otworz aplikacje.", 0, 0, 0
                )
                return Result.success() // Brak sesji nie usuwa snapshotów i nie uruchamia bezcelowych ponowień.
            }
            if (session.userId != expectedUserId ||
                !EpisodeBackgroundScheduler.isEnabledFor(app, expectedUserId)
            ) return Result.success()

            val observed = ShindenApi.fetchAllAnime(expectedUserId).filter { anime ->
                anime.status.trim().equals("Currently Airing", ignoreCase = true) &&
                    (anime.watchStatus.trim().equals("in progress", ignoreCase = true) ||
                        anime.favorite)
            }.distinctBy { it.id }

            var ok = 0
            var failures = 0
            var newEpisodes = 0
            for (anime in observed) {
                if (isStopped || !EpisodeBackgroundScheduler.isEnabledFor(app, expectedUserId)) {
                    return Result.success()
                }
                try {
                    val fetched = ShindenEpisodesApi.fetchTrackedEpisodes(anime.id, expectedUserId)
                    if (isStopped || !EpisodeBackgroundScheduler.isEnabledFor(app, expectedUserId)) {
                        return Result.success()
                    }
                    val changes = EpisodeSnapshotStore.compareAndSave(
                        context = app,
                        userId = expectedUserId,
                        titleId = anime.id,
                        current = fetched
                    )
                    ok++
                    if (changes.newlyOnline.isNotEmpty()) {
                        newEpisodes += changes.newlyOnline.size
                        if (EpisodeBackgroundScheduler.isEnabledFor(app, expectedUserId)) {
                            EpisodeNotification.show(app, expectedUserId, anime, changes.newlyOnline)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++ // Błąd jednej serii nie przerywa skanowania pozostałych.
                }
            }
            if (EpisodeBackgroundScheduler.isEnabledFor(app, expectedUserId)) {
                EpisodeBackgroundScheduler.report(
                    app, expectedUserId,
                    "Zakonczono skan ${observed.size} obserwowanych anime.",
                    ok, failures, newEpisodes
                )
            }
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Awaria sieci lub sesji nie może wyczyścić historii.
            if (EpisodeBackgroundScheduler.isEnabledFor(app, expectedUserId)) {
                EpisodeBackgroundScheduler.report(
                    app, expectedUserId, "Skan nieudany: problem pobierania danych. Sprobujemy ponownie.",
                    0, 1, 0
                )
            }
            return if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
}
