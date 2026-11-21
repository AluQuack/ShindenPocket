package com.example.shindenpocket.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.net.HttpCookie
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Zapisuje wyłącznie cookies w pliku wyłączonym z kopii zapasowych, szyfrowanym
 * AES-GCM kluczem z Android Keystore. Hasło użytkownika nie trafia do magazynu.
 */
internal object SecureSessionStore {

    private const val KEY_ALIAS = "shinden_pocket_session_v1"
    private const val FILE_NAME = "shinden_session_v1.bin"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val MAX_SESSION_FILE_BYTES = 256 * 1024

    // Defense in depth: nie zapisujemy ciastek spoza głównego hosta Shinden.
    private fun isAllowedCookie(cookie: HttpCookie): Boolean {
        val domain = cookie.domain.orEmpty().trimStart('.')
        val path = cookie.path ?: "/"
        return !cookie.hasExpired() &&
            (domain.isBlank() || domain.equals("shinden.pl", ignoreCase = true)) &&
            cookie.name.matches(Regex("[A-Za-z0-9_-]{1,64}")) &&
            cookie.value.length <= 8192 &&
            cookie.value.none { it == '\r' || it == '\n' || it == ';' } &&
            path.startsWith('/') && path.length <= 256 &&
            path.none { it == '\r' || it == '\n' || it == ';' }
    }

    private fun sessionFile(context: Context): File =
        File(context.applicationContext.noBackupFilesDir, FILE_NAME)

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }

        val oldKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (oldKey != null) return oldKey

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun save(context: Context, cookies: List<HttpCookie>) {
        val now = System.currentTimeMillis()
        val items = JSONArray()

        cookies.filter(::isAllowedCookie).forEach { cookie ->
            val expiry = if (cookie.maxAge < 0) {
                -1L
            } else {
                // Ograniczenie czasu zapisu do roku zapobiega przepełnieniu.
                now + cookie.maxAge.coerceAtMost(365L * 24 * 3600) * 1000
            }

            val item = JSONObject()
                .put("name", cookie.name)
                .put("value", cookie.value)
                .put("domain", cookie.domain ?: "")
                .put("path", cookie.path ?: "/")
                .put("secure", cookie.secure)
                .put("httpOnly", cookie.isHttpOnly)
                .put("version", cookie.version)
                .put("expiresAt", expiry)
            items.put(item)
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(items.toString().toByteArray(Charsets.UTF_8))

        // Układ pliku: długość IV (1 bajt), IV, zaszyfrowane dane + tag GCM.
        val bytes = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted
        if (bytes.size > MAX_SESSION_FILE_BYTES) {
            throw IllegalArgumentException("Zbyt duża sesja Shinden.")
        }
        val outputFile = sessionFile(context)
        val tempFile = File(outputFile.parentFile, "$FILE_NAME.tmp")
        tempFile.writeBytes(bytes)

        if (!tempFile.renameTo(outputFile)) {
            tempFile.delete()
            throw IllegalStateException("Nie można bezpiecznie zapisać sesji.")
        }
    }

    fun load(context: Context): List<HttpCookie> {
        val file = sessionFile(context)
        if (!file.exists()) return emptyList()

        return try {
            if (file.length() > MAX_SESSION_FILE_BYTES) {
                throw IllegalArgumentException("Zbyt duży plik sesji")
            }
            val bytes = file.readBytes()
            if (bytes.size < 30) throw IllegalArgumentException("Niepoprawny plik sesji")

            val ivLength = bytes[0].toInt() and 0xff
            if (ivLength !in 12..16 || bytes.size <= ivLength + 17) {
                throw IllegalArgumentException("Niepoprawne IV")
            }
            val iv = bytes.copyOfRange(1, 1 + ivLength)
            val encrypted = bytes.copyOfRange(1 + ivLength, bytes.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, iv)
            )

            val json = JSONArray(
                String(cipher.doFinal(encrypted), Charsets.UTF_8)
            )
            val now = System.currentTimeMillis()
            val result = mutableListOf<HttpCookie>()

            for (i in 0 until json.length()) {
                val item = json.getJSONObject(i)
                val expiresAt = item.getLong("expiresAt")
                if (expiresAt >= 0 && expiresAt <= now) continue

                val cookie = HttpCookie(
                    item.getString("name"),
                    item.getString("value")
                )

                val domain = item.optString("domain", "")
                if (domain.isNotBlank()) cookie.domain = domain
                cookie.path = item.optString("path", "/")
                cookie.secure = item.optBoolean("secure", false)
                cookie.isHttpOnly = item.optBoolean("httpOnly", false)
                cookie.version = item.optInt("version", 0)
                cookie.maxAge = if (expiresAt < 0) {
                    -1
                } else {
                    ((expiresAt - now) / 1000).coerceAtLeast(1)
                }

                if (isAllowedCookie(cookie)) result.add(cookie)
            }
            result
        } catch (e: Exception) {
            // Dane bez odpowiadającego klucza Keystore są traktowane jako uszkodzone.
            
            clear(context)
            emptyList()
        }
    }

    fun clear(context: Context) {
        sessionFile(context).delete()
        File(context.applicationContext.noBackupFilesDir, "$FILE_NAME.tmp").delete()
    }
}
