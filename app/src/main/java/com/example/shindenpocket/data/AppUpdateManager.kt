package com.example.shindenpocket.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppRelease(
    val version: String,
    val apkUrl: String,
    val sha256: String?
)

object AppUpdateManager {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/AluQuack/ShindenPocket/releases/latest"
    private const val USER_AGENT = "ShindenPocket-Android"

    fun currentVersion(context: Context): String =
        context.packageManager.installedInfo(context.packageName).versionName.orEmpty()

    suspend fun findUpdate(currentVersion: String): AppRelease? = withContext(Dispatchers.IO) {
        val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub HTTP ${connection.responseCode}")
            }

            val json = JSONObject(
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            )
            val version = json.getString("tag_name").removePrefix("v").removePrefix("V")
            val assets = json.getJSONArray("assets")
            val asset = (0 until assets.length())
                .asSequence()
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: throw IOException("Wydanie nie zawiera pliku APK")
            val apkUrl = asset.getString("browser_download_url")
            validateDownloadUrl(URL(apkUrl), firstRequest = true)
            val digest = asset.optString("digest")
                .takeIf { it.isNotBlank() }
                ?.let {
                    if (!it.matches(Regex("sha256:[0-9a-fA-F]{64}"))) {
                        throw IOException("Nieprawidłowa suma APK")
                    }
                    it.substringAfter(':').lowercase()
                }

            AppRelease(version, apkUrl, digest)
                .takeIf { isNewerVersion(it.version, currentVersion) }
        } finally {
            connection.disconnect()
        }
    }

    suspend fun downloadApk(context: Context, release: AppRelease): File =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "updates")
            if (!directory.exists() && !directory.mkdirs()) {
                throw IOException("Nie można utworzyć katalogu aktualizacji")
            }
            val temporary = File(directory, "update.apk.download")
            val target = File(directory, "update.apk")
            temporary.delete()

            try {
                val actualDigest = download(release.apkUrl, temporary)
                if (release.sha256 != null && actualDigest != release.sha256) {
                    throw IOException("Suma pobranego APK jest nieprawidłowa")
                }
                validateApk(context, temporary)
                target.delete()
                if (!temporary.renameTo(target)) {
                    throw IOException("Nie można zapisać aktualizacji")
                }
                target
            } finally {
                temporary.delete()
            }
        }

    internal fun isNewerVersion(candidate: String, current: String): Boolean {
        val candidateParts = versionParts(candidate) ?: return false
        val currentParts = versionParts(current) ?: return false
        val size = maxOf(candidateParts.size, currentParts.size)
        for (index in 0 until size) {
            val candidatePart = candidateParts.getOrElse(index) { 0 }
            val currentPart = currentParts.getOrElse(index) { 0 }
            if (candidatePart != currentPart) return candidatePart > currentPart
        }
        return false
    }

    private fun versionParts(version: String): List<Int>? {
        val normalized = version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .substringBefore('+')
        if (normalized.isBlank()) return null
        return normalized.split('.').map { it.toIntOrNull() ?: return null }
    }

    private fun download(startUrl: String, destination: File): String {
        var current = URL(startUrl)
        repeat(6) { redirect ->
            validateDownloadUrl(current, firstRequest = redirect == 0)
            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 30_000
                connection.setRequestProperty(
                    "Accept",
                    "application/vnd.android.package-archive,application/octet-stream"
                )
                connection.setRequestProperty("User-Agent", USER_AGENT)
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val digest = MessageDigest.getInstance("SHA-256")
                        connection.inputStream.use { input ->
                            destination.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    digest.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        return digest.digest().joinToString("") { "%02x".format(it) }
                    }

                    HttpURLConnection.HTTP_MOVED_PERM,
                    HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER,
                    307,
                    308 -> {
                        val location = connection.getHeaderField("Location")
                            ?: throw IOException("Przekierowanie bez adresu")
                        current = URL(current, location)
                    }

                    else -> throw IOException("Pobieranie APK: HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Zbyt wiele przekierowań podczas pobierania APK")
    }

    private fun validateDownloadUrl(url: URL, firstRequest: Boolean) {
        val allowedHost = if (firstRequest) {
            url.host.equals("github.com", ignoreCase = true) &&
                url.path.startsWith("/AluQuack/ShindenPocket/releases/download/")
        } else {
            url.host.equals("github.com", ignoreCase = true) ||
                url.host.endsWith(".githubusercontent.com", ignoreCase = true)
        }
        if (url.protocol != "https" || url.port != -1 || url.userInfo != null || !allowedHost) {
            throw IOException("Niedozwolony adres APK")
        }
    }

    private fun validateApk(context: Context, file: File) {
        val packageManager = context.packageManager
        val archive = packageManager.archiveInfo(file)
            ?: throw IOException("Pobrany plik nie jest prawidłowym APK")
        val installed = packageManager.installedInfo(context.packageName)
        if (archive.packageName != context.packageName) {
            throw IOException("APK ma nieprawidłową nazwę pakietu")
        }
        if (archive.longCode() <= installed.longCode()) {
            throw IOException("APK nie ma nowszego versionCode")
        }
        if (!isNewerVersion(archive.versionName.orEmpty(), installed.versionName.orEmpty())) {
            throw IOException("APK nie ma nowszej wersji")
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageManager.archiveInfo(path: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            getPackageArchiveInfo(path.absolutePath, PackageManager.PackageInfoFlags.of(0))
        } else {
            getPackageArchiveInfo(path.absolutePath, 0)
        }

    @Suppress("DEPRECATION")
    private fun PackageManager.installedInfo(packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) {
            getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            getPackageInfo(packageName, 0)
        }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longCode(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()
}
