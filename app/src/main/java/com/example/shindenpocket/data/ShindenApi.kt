
package com.example.shindenpocket.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object ShindenApi {

    private const val API_BASE =
        "https://lista.shinden.pl/api"

    
    suspend fun fetchAllAnime(userId: Int): List<Anime> =
        withContext(Dispatchers.IO) {

            require(userId > 0) { "Nieprawidłowe ID użytkownika" }

            val allAnime = mutableListOf<Anime>()
            val limit = 30
            var offset = 0

            while (true) {

                val url =
                    "$API_BASE/userlist/$userId/anime" +
                            "?limit=$limit&offset=$offset"

                val connection =
                    URL(url).openConnection() as HttpURLConnection

                val response = try {

                    connection.requestMethod = "GET"
                    connection.connectTimeout = 15000
                    connection.readTimeout = 15000

                    connection.setRequestProperty(
                        "Accept",
                        "application/json"
                    )

                    val code = connection.responseCode

                    if (code !in 200..299) {
                        throw IOException("HTTP $code")
                    }

                    connection.inputStream
                        .bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }

                } finally {
                    connection.disconnect()
                }

                val json = JSONObject(response)

                if (!json.optBoolean("success")) {
                    throw IOException("Błąd API Shindena")
                }

                val result = json.getJSONObject("result")
                val items = result.getJSONArray("items")
                val totalCount = result.getInt("count")

                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    allAnime.add(parseAnimeItem(item))
                }

                offset += items.length()

                if (
                    items.length() == 0 ||
                    offset >= totalCount
                ) {
                    break
                }
            }

            allAnime.distinctBy { it.id }
        }

    internal fun parseAnimeItem(item: JSONObject): Anime {
        val descriptionPl = item.optString("descriptionPl", "")
        val descriptionEn = item.optString("descriptionEn", "")
        val description = when {
            descriptionPl.isNotBlank() && descriptionPl != "null" -> descriptionPl
            descriptionEn.isNotBlank() && descriptionEn != "null" -> descriptionEn
            else -> ""
        }
        return Anime(
            id = item.getInt("titleId"),
            coverId = item.optInt("coverId", 0),
            title = item.getString("title"),
            watched = item.optString("watchedEpisodesCnt", "0").toIntOrNull() ?: 0,
            total = item.optInt("episodes", 0),
            favorite = item.optInt("isFavourite", 0) == 1,
            watchStatus = item.optString("watchStatus", "").takeUnless { it == "null" }.orEmpty(),
            status = item.optString("titleStatus", "").takeUnless { it == "null" }.orEmpty(),
            description = description,
            rating = item.optString("summaryRatingTotal", "").takeUnless { it == "null" }.orEmpty(),
            rateTotal = with(ShindenManagementApi) { item.optValidScore("rateTotal") },
            rateStory = with(ShindenManagementApi) { item.optValidScore("rateStory") },
            rateGraphic = with(ShindenManagementApi) {
                item.optValidScore("rateGraphic") ?: item.optValidScore("rateGraphics")
            },
            rateMusic = with(ShindenManagementApi) { item.optValidScore("rateMusic") },
            rateCharacters = with(ShindenManagementApi) {
                item.optValidScore("rateCharacters") ?: item.optValidScore("rateTitlecahracters")
            }
        )
    }
}
