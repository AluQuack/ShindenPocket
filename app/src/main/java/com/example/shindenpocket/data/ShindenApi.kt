
package com.example.shindenpocket.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException

object ShindenApi {

    suspend fun fetchAllAnime(userId: Int): List<Anime> =
        withContext(Dispatchers.IO) {

            require(userId > 0) { "Nieprawidłowe ID użytkownika" }

            val allAnime = mutableListOf<Anime>()
            val limit = 30
            var offset = 0

            while (true) {
                val (code, response) = ShindenManagementApi.executeV1Request(
                    method = "GET",
                    path = "/lista/userlist/$userId/anime?limit=$limit&offset=$offset",
                    refererPath = "/user/$userId/animelist"
                )
                if (code !in 200..299) {
                    throw IOException("HTTP $code")
                }

                val (pageAnime, totalCount) = parseAnimePage(response)
                allAnime.addAll(pageAnime)
                offset += pageAnime.size

                if (pageAnime.isEmpty() || offset >= totalCount) {
                    break
                }
            }

            allAnime.distinctBy { it.id }
        }

    internal fun parseAnimePage(response: String): Pair<List<Anime>, Int> {
        val json = JSONObject(response)
        if (json.has("success") && !json.optBoolean("success")) {
            throw IOException("Błąd API Shindena")
        }

        // Nowe /v1/lista zwraca items i count bezpośrednio; result zachowuje zgodność ze starym API.
        val result = json.optJSONObject("result") ?: json
        val items = result.getJSONArray("items")
        val anime = (0 until items.length()).map { index ->
            parseAnimeItem(items.getJSONObject(index))
        }
        return anime to result.getInt("count")
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
