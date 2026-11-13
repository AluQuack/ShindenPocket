package com.example.shindenpocket.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class ShindenEndpointParsersTest {

    @Test
    fun newEpisodeListParsesRowsPaginationAndBothDetailRoutes() {
        val html = """
            <html><body>
            <section class="box episode-list ep-list" id="odcinki">
              <ol class="ep-rows">
                <li class="ep-row" id="ep-670">
                  <div class="ep-row__main">
                    <a class="ep-row__link" href="/epek/11-naruto/view/670">
                      <span class="ep-row__no">220</span>
                      <span class="ep-row__title">Wyruszam w podróż</span>
                    </a>
                  </div>
                  <span class="ep-row__langs">
                    <span class="lang-flag" title="Polski">
                      <span class="flag-icon flag-icon-pl"></span>
                    </span>
                  </span>
                  <span class="ep-row__date"><time datetime="2007-02-08">2007-02-08</time></span>
                </li>
                <li class="ep-row" id="ep-26">
                  <div class="ep-row__main">
                    <a class="ep-row__link" href="/episode/11-naruto/view/26">
                      <span class="ep-row__no">26</span>
                      <span class="ep-row__title">Prawdziwy blues</span>
                    </a>
                  </div>
                  <span class="ep-row__langs"></span>
                  <span class="ep-row__date"><span title="Brak daty emisji">—</span></span>
                </li>
                <li class="ep-row" id="ep-999" hidden>
                  <div class="ep-row__main">
                    <a class="ep-row__link" href="/episode/11-naruto/view/999">
                      <span class="ep-row__no">13</span>
                      <span class="ep-row__title"></span>
                    </a>
                  </div>
                  <span class="ep-row__langs"></span>
                  <span class="ep-row__date"><time datetime="2026-11-06">jutro</time></span>
                </li>
                <li class="ep-row" id="ep-1000">
                  <div class="ep-row__main">
                    <a class="ep-row__link" href="/episode/11-naruto/view/1000">
                      <span class="ep-row__no">14</span>
                      <span class="ep-row__title"></span>
                    </a>
                  </div>
                  <span class="ep-row__langs"></span>
                  <span class="ep-row__date"><time datetime="2999-01-01">2999-01-01</time></span>
                </li>
              </ol>
            </section>
            <a href="/titles/11-naruto/episodes?strona=3&amp;na_stronie=100#odcinki">3</a>
            </body></html>
        """.trimIndent()

        val rows = ShindenEpisodeListParser.parse(html, titleId = 11)

        assertEquals(4, rows.size)
        assertEquals(670, rows[0].episodeId)
        assertEquals("220", rows[0].number)
        assertEquals("Wyruszam w podróż", rows[0].title)
        assertEquals(listOf("Polski"), rows[0].languages)
        assertEquals("2007-02-08", rows[0].airDate)
        assertEquals(true, rows[0].online)
        assertEquals("https://shinden.pl/epek/11-naruto/view/670", rows[0].detailUrl)

        assertEquals(26, rows[1].episodeId)
        assertNull(rows[1].airDate)
        assertEquals(true, rows[1].online)
        assertEquals("https://shinden.pl/episode/11-naruto/view/26", rows[1].detailUrl)

        // Sam link do strony przyszłego odcinka nie oznacza jego premiery.
        
        assertEquals(false, rows[2].online)
        assertEquals("2026-11-06", rows[2].airDate)
        assertEquals(false, rows[3].online)
        assertEquals("2999-01-01", rows[3].airDate)

        assertEquals(3, ShindenEpisodeListParser.maxPage(html))
        assertEquals(
            "https://shinden.pl/titles/11/episodes?na_stronie=100&strona=2",
            ShindenEpisodeListParser.listUrl(11, 2)
        )
    }

    @Test
    fun newSearchCardsUseTitlesRouteAndMetadata() {
        val html = """
            <html><body>
            <ul class="sh-tagi-wyniki">
              <li class="sh-tagi-wynik">
                <a href="/titles/11-naruto">
                  <span class="sh-okl"><img src="/res/images/280x400/441947.webp?v=1"></span>
                  <span class="sh-tagi-nazwa">Naruto</span>
                  <span class="sh-tagi-meta">
                    <span class="sh-tagi-typ">TV</span>
                    <span class="sh-tagi-rok">2002</span>
                    <b><span class="sh-tagi-ukryte">Ocena </span>8,21</b>
                  </span>
                </a>
                <a class="sh-karta-lista" href="/main/login" data-title-id="11" data-episodes="220">Dodaj do listy</a>
              </li>
            </ul>
            <a href="/series?page=4">4</a>
            </body></html>
        """.trimIndent()

        val (anime, maxPage) = SearchApi.parseSearchPage(html, "https://shinden.pl/series")

        assertEquals(1, anime.size)
        assertEquals(4, maxPage)
        val item = anime.single()
        assertEquals(11, item.id)
        assertEquals("Naruto", item.title)
        assertEquals("https://shinden.pl/titles/11-naruto", item.detailUrl)
        assertEquals(441947, item.coverId)
        assertEquals("TV", item.kind)
        assertEquals(220, item.episodes)
        assertEquals(8.21f, item.rating ?: 0f, 0.001f)
        assertTrue(item.tags.isEmpty())
        assertNull(item.status)
    }

    @Test
    fun relatedSeriesReadTilesNotTheEditHeader() {
        val html = """
            <html><head>
              <link rel="canonical" href="https://shinden.pl/series/70907-kusuriya-no-hitorigoto-3rd-season">
            </head><body>
            <section class="sh-tp-section" aria-labelledby="sh-tp-h-rels">
              <div class="sh-tp-section__head">
                <h2 id="sh-tp-h-rels">Powiązane serie</h2>
                <a class="sh-tp-pencil" href="/series/70907-kusuriya-no-hitorigoto-3rd-season/edit#powiazania">edit</a>
              </div>
              <ul class="sh-tp-tiles">
                <li class="sh-tp-tile">
                  <a class="sh-okl" href="/series/67498-kusuriya-no-hitorigoto-2nd-season">
                    <img src="/res/images/280x400/409145.webp?v=1792161808" alt="">
                    <span class="sh-tp-tile__rel">Prequel</span>
                    <span class="sh-tp-tile__foot">
                      <span class="sh-tp-tile__name">Kusuriya no Hitorigoto 2nd Season</span>
                      <span class="sh-tp-tile__kind">TV</span>
                    </span>
                  </a>
                </li>
                <li class="sh-tp-tile">
                  <a class="sh-okl" href="/series/71663-kusuriya-no-hitorigoto-3rd-season-part-2">
                    <img src="/res/other/placeholders/title-v3/225x350.jpg" alt="">
                    <span class="sh-tp-tile__rel">Sequel</span>
                    <span class="sh-tp-tile__foot">
                      <span class="sh-tp-tile__name">Kusuriya no Hitorigoto 3rd Season Part 2</span>
                    </span>
                  </a>
                </li>
                <li class="sh-tp-tile">
                  <a class="sh-okl" href="/manga/54233-kusuriya-no-hitorigoto-ln">
                    <span class="sh-tp-tile__rel">Pierwowzór</span>
                    <span class="sh-tp-tile__name">Kusuriya no hitorigoto (LN)</span>
                  </a>
                </li>
              </ul>
            </section>
            </body></html>
        """.trimIndent()

        val related = SeasonSeriesApi.parseRelatedSeries(html, titleId = 70907)

        assertEquals(2, related.size)
        assertEquals("Prequel", related[0].relation)
        assertEquals(67498, related[0].series.id)
        assertEquals("Kusuriya no Hitorigoto 2nd Season", related[0].series.title)
        assertEquals("https://shinden.pl/series/67498-kusuriya-no-hitorigoto-2nd-season",
            related[0].series.detailUrl)
        assertEquals("https://shinden.pl/res/images/280x400/409145.webp?v=1792161808",
            related[0].series.coverUrl)
        assertEquals("Sequel", related[1].relation)
        assertEquals(71663, related[1].series.id)
        assertNull(related[1].series.coverUrl)
    }

    @Test
    fun seriesInfoParsesShindenTpCardFields() {
        val html = """
            <html><head>
              <link rel="canonical" href="https://shinden.pl/series/70907-kusuriya-no-hitorigoto-3rd-season">
            </head><body>
            <div class="sh-tp sh-tp--video sh-tp--baner">
              <div class="sh-tp-banner">
                <img class="sh-tp-banner__img" src="/res/images/950x200/442765.webp?v=1793554998" srcset="/res/images/950x200/442765.webp?v=1793554998 950w, /res/images/genuine/442765.webp?v=1793534007 1900w" alt="">
              </div>
              <div class="sh-tp-top">
                <span class="sh-okl sh-tp-cover"><img src="/res/images/280x400/441947.webp?v=1792162739" alt="Kusuriya no Hitorigoto 3rd Season"></span>
                <p class="sh-fav-cnt" data-fav-cnt="418">418 osób ma w ulubionych</p>
              </div>
              <div class="sh-tp-head">
                <span class="sh-tp-kind">Anime · TV</span>
                <h1>Kusuriya no Hitorigoto 3rd Season</h1>
                <p class="sh-tp-meta"><span>2026</span><span>Emitowane</span></p>
                <div class="sh-tp-alttitles">
                  <div class="alt-titles"><ul>
                    <li><span class="flag-icon flag-icon-pl" title="Polski"></span> <abbr title="Polski">PL</abbr> <span lang="pl">Zapiski Zielarki - Sezon 3</span></li>
                    <li><span class="flag-icon flag-icon-jp" title="Japoński"></span> <abbr title="Japoński">JP</abbr> <span lang="ja">薬屋のひとりごと 第3期</span></li>
                  </ul></div>
                </div>
                <div class="sh-tp-wynik">
                  <div class="sh-tp-wynik__main">
                    <div class="sh-tp-score"><b>9,19</b>/ 10</div>
                    <p><span><b>274</b> głosy</span><span><b>#480</b> w rankingu</span></p>
                  </div>
                  <div class="sh-tp-parts">
                    <span><b>9,16</b>Fabuła</span>
                    <span><b>9,17</b>Grafika</span>
                    <span><b>8,83</b>Muzyka</span>
                    <span><b>9,26</b>Postacie</span>
                  </div>
                </div>
              </div>
              <div class="sh-tp-video">
                <a class="sh-media-yt sh-tp-video__link" href="https://www.youtube.com/watch?v=YvNvvUeCztE" data-yt="YvNvvUeCztE" aria-label="Odtwórz: Zwiastun 4: Kusuriya no Hitorigoto 3rd Season">
                  <img class="sh-tp-video__img" src="https://i.ytimg.com/vi/YvNvvUeCztE/mqdefault.jpg" alt="Zwiastun 4: Kusuriya no Hitorigoto 3rd Season">
                </a>
                <div class="sh-tp-video__foot">
                  <p class="sh-tp-video__caption">Zwiastun 4</p>
                  <a class="sh-tp-video__more" href="/series/70907-kusuriya-no-hitorigoto-3rd-season/video">Więcej wideo (6)</a>
                </div>
              </div>
              <section class="sh-tp-section" aria-labelledby="sh-tp-h-desc">
                <div id="description" class="sh-tp-desc sh-zwin"><p>Maomao powróciła do codziennego życia jako zielarka w dzielnicy czerwonych latarni.</p></div>
                <ul class="sh-tp-tags">
                  <li><a class="sh-tp-tag" href="/targetgroup/48-seinen">Seinen</a></li>
                  <li><a class="sh-tp-tag" href="/source/1976-light-novel">Light novel</a></li>
                </ul>
                <details class="sh-tp-tags-all">
                  <summary>Wszystkie tagi (2)</summary>
                  <div>
                    <div class="sh-tp-taggroup"><h3>Grupy docelowe</h3><ul><li><a class="sh-tp-tag" href="/targetgroup/48-seinen">Seinen</a></li></ul></div>
                    <div class="sh-tp-taggroup"><h3>Pierwowzór</h3><ul><li><a class="sh-tp-tag" href="/source/1976-light-novel">Light novel</a></li></ul></div>
                  </div>
                </details>
              </section>
              <aside class="sh-tp-side">
                <section class="sh-tp-stats" aria-labelledby="sh-tp-h-stats">
                  <h3>Rozkład ocen</h3>
                  <ul class="sh-tp-dist">
                    <li><span class="sh-tp-dist__bar sh-tp-h-2"></span><span class="sh-tp-dist__tip">Ocena 1: 3 osoby</span></li>
                    <li><span class="sh-tp-dist__bar sh-tp-h-100"></span><span class="sh-tp-dist__tip">Ocena 10: 152 osoby</span></li>
                  </ul>
                  <h3>Na listach</h3>
                  <ul class="sh-tp-statuslist">
                    <li><a href="/series/70907/stats"><span>Ogląda</span><b>5954</b></a></li>
                    <li><a href="/series/70907/stats"><span>Planuje</span><b>2324</b></a></li>
                    <li><a href="/series/70907/stats"><span>Ulubione</span><b>418</b></a></li>
                  </ul>
                </section>
                <section class="sh-tp-sidebox" aria-labelledby="sh-tp-h-info">
                  <dl class="sh-tp-info">
                    <dt>Typ</dt><dd>TV</dd>
                    <dt>Status</dt><dd>Emitowane</dd>
                    <dt>Emisja</dt><dd>02.10.2026</dd>
                    <dt>Odcinki</dt><dd>12 po 24 min</dd>
                    <dt>Studio</dt><dd class="sh-tp-chips">
                      <a class="sh-tp-tag" href="/studio/60-olm">OLM</a>
                      <a class="sh-tp-tag" href="/studio/247-dentsu">Dentsu</a>
                      <details class="sh-tp-more"><summary>+5 więcej</summary><div>
                        <a class="sh-tp-tag" href="/studio/256-square-enix">Square Enix</a>
                      </div></details>
                    </dd>
                  </dl>
                </section>
              </aside>
              <section class="sh-tp-section" aria-labelledby="sh-tp-h-chars">
                <ul class="sh-tp-chars">
                  <li class="sh-tp-char">
                    <a class="sh-okl sh-tp-char__pic" href="/character/83108-maomao"><img src="/res/images/280x400/374829.webp?v=1792160824" alt="Maomao"></a>
                    <div class="sh-tp-char__who">
                      <a class="sh-tp-char__name" href="/character/83108-maomao"><bdi>Maomao</bdi></a>
                      <span class="sh-tp-char__role">Główna</span>
                    </div>
                    <div class="sh-tp-char__vas">
                      <div class="sh-tp-char__va">
                        <div class="sh-tp-char__who sh-tp-char__who--va">
                          <a class="sh-tp-char__name" href="/staff/6456-yuuki-aoi"><bdi>Yuuki, Aoi</bdi></a>
                          <span class="sh-tp-char__role">Seiyuu</span>
                        </div>
                        <a class="sh-okl sh-tp-char__pic" href="/staff/6456-yuuki-aoi"><img src="/res/images/280x400/360679.webp?v=1792160399" alt="Yuuki, Aoi"></a>
                      </div>
                    </div>
                  </li>
                </ul>
              </section>
            </div>
            </body></html>
        """.trimIndent()

        val info = SeasonSeriesApi.parseInfo(html, titleId = 70907)

        assertEquals("https://shinden.pl/res/images/950x200/442765.webp?v=1793554998", info.bannerUrl)
        assertEquals("https://shinden.pl/res/images/280x400/441947.webp?v=1792162739", info.coverUrl)
        assertEquals("Anime · TV", info.kind)
        assertEquals(listOf("2026", "Emitowane"), info.metaBadges)
        assertEquals(2, info.altTitles.size)
        assertEquals("PL", info.altTitles[0].langCode)
        assertEquals("Zapiski Zielarki - Sezon 3", info.altTitles[0].title)
        assertEquals("9,19", info.rating)
        assertEquals("274 głosy", info.votesCount)
        assertEquals("#480 w rankingu", info.rankingPosition)
        assertEquals(4, info.subRatings.size)
        assertEquals("Fabuła", info.subRatings[0].label)
        assertEquals("9,16", info.subRatings[0].score)
        assertEquals("418 osób ma w ulubionych", info.favoritesLabel)
        assertEquals("YvNvvUeCztE", info.trailer?.youtubeId)
        assertEquals("Zwiastun 4", info.trailer?.caption)
        assertEquals("Więcej wideo (6)", info.trailer?.moreCountLabel)
        assertEquals(listOf("Seinen", "Light novel"), info.tags)
        assertEquals(2, info.tagGroups.size)
        assertEquals(5, info.infoFields.size)
        assertEquals(listOf("OLM", "Dentsu"), info.infoFields[4].primaryChips)
        assertEquals(listOf("Square Enix"), info.infoFields[4].extraChips)
        assertEquals(2, info.ratingDistribution.size)
        assertEquals(152, info.ratingDistribution[1].votes)
        assertEquals(3, info.listStatuses.size)
        assertEquals(5954, info.listStatuses[0].count)
        assertEquals(1, info.characters.size)
        assertEquals("Maomao", info.characters[0].name)
        assertEquals("Yuuki, Aoi", info.characters[0].voiceActorName)
    }

    @Test
    fun missingEmbeddedJsonAndTableIsAnError() {
        try {
            EpisodeSourcesApi.parseEpisodeSources(
                "<html><body><p>Brak źródeł</p></body></html>",
                titleId = 1,
                episodeId = 26,
                userId = 7
            )
            fail("Expected IOException")
        } catch (_: IOException) {
            // Nierozpoznana lista playerów jest błędem, a nie potwierdzoną pustą listą.
        }
    }

    @Test
    fun embeddedPlayerMarksMachineTranslatedPolishSubtitles() {
        assertEquals("Polski AI", EpisodeSourcesApi.languageFromCode("iPL"))
    }
}
