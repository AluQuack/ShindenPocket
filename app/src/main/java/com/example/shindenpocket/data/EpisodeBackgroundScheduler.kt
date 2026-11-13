package com.example.shindenpocket.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Zarządza zgodą użytkownika i diagnostyką pracy w tle. */
object EpisodeBackgroundScheduler {
    private const val PREFS = "shinden_background_episodes_v1"
    private const val PERIODIC_NAME = "shinden_episodes_periodic_v1"
    private const val TEST_NAME = "shinden_episodes_test_v1"
    const val DEFAULT_INTERVAL_HOURS = 3L
    val INTERVAL_OPTIONS_HOURS = listOf(1L, 3L, 6L, 12L, 24L)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun enabledKey(userId: Int) = "enabledForUser_$userId"

    fun isEnabledFor(context: Context, userId: Int): Boolean =
        userId > 0 && prefs(context).getBoolean("enabled", false) &&
            prefs(context).getInt("userId", -1) == userId

    fun enabledUserId(context: Context): Int? =
        prefs(context).takeIf { it.getBoolean("enabled", false) }
            ?.getInt("userId", -1)?.takeIf { it > 0 }

    fun intervalHours(context: Context): Long =
        prefs(context).getLong("intervalHours", DEFAULT_INTERVAL_HOURS)
            .takeIf { it in INTERVAL_OPTIONS_HOURS } ?: DEFAULT_INTERVAL_HOURS

    fun savedEnabledFor(context: Context, userId: Int): Boolean? {
        if (userId <= 0) return null
        val preferences = prefs(context)
        val key = enabledKey(userId)
        return when {
            preferences.contains(key) -> preferences.getBoolean(key, false)
            isEnabledFor(context, userId) -> true
            else -> null
        }
    }

    fun enable(context: Context, userId: Int, intervalHours: Long = intervalHours(context)) {
        require(userId > 0)
        require(intervalHours in INTERVAL_OPTIONS_HOURS)
        val app = context.applicationContext
        // Identyfikator konta zapisujemy przed zleceniem pracy, aby Worker użył właściwej sesji.
        if (!prefs(app).edit()
                .putBoolean("enabled", true)
                .putInt("userId", userId)
                .putBoolean(enabledKey(userId), true)
                .putLong("intervalHours", intervalHours)
                .commit()
        ) {
            throw IllegalStateException("Nie zapisano ustawien monitorowania")
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val periodic = PeriodicWorkRequestBuilder<EpisodeBackgroundWorker>(
            intervalHours, TimeUnit.HOURS
        ).setConstraints(constraints).build()
        try {
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.UPDATE, periodic
            )
        } catch (e: Exception) {
            pause(app)
            throw e
        }
    }

    fun setInterval(context: Context, intervalHours: Long) {
        require(intervalHours in INTERVAL_OPTIONS_HOURS)
        val app = context.applicationContext
        val userId = enabledUserId(app)
        if (userId != null) {
            enable(app, userId, intervalHours)
        } else if (!prefs(app).edit().putLong("intervalHours", intervalHours).commit()) {
            throw IllegalStateException("Nie zapisano interwalu monitorowania")
        }
    }

    fun disable(context: Context, userId: Int) {
        require(userId > 0)
        prefs(context).edit().putBoolean(enabledKey(userId), false).commit()
        if (enabledUserId(context) == userId) pause(context)
    }

    /** Zatrzymuje pracę bez kasowania decyzji użytkownika dla konta. */
    fun pause(context: Context) {
        val app = context.applicationContext
        prefs(app).edit().putBoolean("enabled", false).remove("userId").commit()
        WorkManager.getInstance(app).cancelUniqueWork(PERIODIC_NAME)
        WorkManager.getInstance(app).cancelUniqueWork(TEST_NAME)
    }

    /** Anuluje pracę poprzedniego konta, aby nie wysłała jego powiadomień. */
    fun disableIfDifferentAccount(context: Context, currentUserId: Int) {
        val saved = enabledUserId(context)
        if (saved != null && saved != currentUserId) pause(context)
    }

    internal fun report(context: Context, userId: Int, message: String, ok: Int, errors: Int, new: Int) {
        prefs(context).edit()
            .putInt("lastUserId", userId)
            .putLong("lastAt", System.currentTimeMillis())
            .putString("lastMessage", message)
            .putInt("lastOk", ok)
            .putInt("lastErrors", errors)
            .putInt("lastNew", new)
            .apply()
    }
}
