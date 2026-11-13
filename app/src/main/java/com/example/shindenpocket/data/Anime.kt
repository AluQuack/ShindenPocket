package com.example.shindenpocket.data

data class Anime(
    val id: Int,
    val coverId: Int,
    val title: String,
    val watched: Int,
    val total: Int,
    val favorite: Boolean,
    val watchStatus: String,
    val status: String,
    val description: String,
    val rating: String,
    val rateTotal: Int? = null,
    val rateStory: Int? = null,
    val rateGraphic: Int? = null,
    val rateMusic: Int? = null,
    val rateCharacters: Int? = null
) {
    val hasUserRating: Boolean
        get() = rateTotal != null ||
            rateStory != null ||
            rateGraphic != null ||
            rateMusic != null ||
            rateCharacters != null

    val isInUserLibrary: Boolean
        get() = watchStatus.isNotBlank() || favorite || hasUserRating
}
