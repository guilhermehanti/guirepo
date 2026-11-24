package com.noveflix

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class NoveFlixParserTest {

    @Test
    fun parseArchiveAndSearchCards_extractsItems() {
        val archiveHtml = """
            <article id="post-featured-62488" class="item movies">
              <div class="poster">
                <img src="https://noveflix.lol/wp-content/uploads/2026/09/cover.jpg" alt="A Nobreza do Amor">
                <a href="https://noveflix.lol/assistir/a-nobreza-do-amor/"><div class="see play3"></div></a>
              </div>
              <div class="data"><h3><a href="https://noveflix.lol/assistir/a-nobreza-do-amor/">A Nobreza do Amor</a></h3></div>
            </article>
        """.trimIndent()

        val cards = NoveFlixParser.parseArchiveCards(Jsoup.parse(archiveHtml), "https://noveflix.lol")
        assertEquals(1, cards.size)
        assertEquals("A Nobreza do Amor", cards[0].title)
        assertEquals("https://noveflix.lol/assistir/a-nobreza-do-amor/", cards[0].url)

        val searchHtml = """
            <div class="result-item">
              <article>
                <div class="image"><div class="thumbnail"><a href="https://noveflix.lol/assistir/ondas-de-amor/"><img src="https://noveflix.lol/img.webp" alt="Ondas de Amor" /></a></div></div>
                <div class="details"><div class="title"><a href="https://noveflix.lol/assistir/ondas-de-amor/">Ondas de Amor</a></div></div>
              </article>
            </div>
        """.trimIndent()
        val searchCards = NoveFlixParser.parseSearchCards(Jsoup.parse(searchHtml), "https://noveflix.lol")
        assertEquals(1, searchCards.size)
        assertEquals("Ondas de Amor", searchCards[0].title)
    }

    @Test
    fun parsePanelChapters_extractsChaptersAndThumbnails() {
        val html = """
            <ul class="chapters">
              <li class="chapter-row" data-episode-id="54878" data-chapter="1">
                <a href="/liberar.php?slug=NOBREZA-001">
                  <span class="chapter-thumb-wrap">
                    <img class="chapter-thumb" src="https://nflixpainel.cc/uploads/thumbs/NOBREZA-001.jpg" alt="">
                  </span>
                  <span class="chapter-info"><span class="chapter-name">Capítulo 001</span></span>
                </a>
              </li>
              <li class="chapter-row" data-episode-id="54879" data-chapter="2">
                <a href="/liberar.php?slug=NOBREZA-002">
                  <span class="chapter-info"><span class="chapter-name">Capítulo 002</span></span>
                </a>
              </li>
            </ul>
        """.trimIndent()

        val chapters = NoveFlixParser.parsePanelChapters(Jsoup.parse(html), "https://nflixpainel.cc")
        assertEquals(2, chapters.size)
        assertEquals(1, chapters[0].season)
        assertEquals(1, chapters[0].episode)
        assertEquals("Capítulo 001", chapters[0].name)
        assertEquals("https://nflixpainel.cc/liberar.php?slug=NOBREZA-001", chapters[0].url)
        assertEquals("https://nflixpainel.cc/uploads/thumbs/NOBREZA-001.jpg", chapters[0].posterUrl)
        assertEquals(2, chapters[1].episode)
    }

    @Test
    fun parseReleaseFormAndExtractStreamUrl_works() {
        val gateHtml = """
            <form method="post" id="form">
              <input type="hidden" name="token" value="abc123">
              <input type="hidden" name="issued" value="1795540213">
              <input type="hidden" name="admin_bypass" value="0">
            </form>
        """.trimIndent()
        val form = NoveFlixParser.parseReleaseForm(Jsoup.parse(gateHtml))
        assertNotNull(form)
        assertEquals("abc123", form!!.token)
        assertEquals("1795540213", form.issued)

        val playerHtml = """
            <script>
            const stream = "https://nflixpainel.cc/stream.php?slug=NOBREZA-001&e=1795561858&t=0aa72fc3";
            </script>
        """.trimIndent()
        assertEquals(
            "https://nflixpainel.cc/stream.php?slug=NOBREZA-001&e=1795561858&t=0aa72fc3",
            NoveFlixParser.extractStreamUrl(playerHtml)
        )
    }

    @Test
    fun parsePanelChapters_parsesJsTotalChaptersPanelLikeBeijoDoVampiro() {
        val html = """
            <div class="lista-container"><ul id="chapter-list"></ul></div>
            <script>
              function loadChapters() {
                let totalChapters = 215;
                let legendados = [2];
                for (let i = 1; i <= totalChapters; i++) {
                  let chapterStr = ("00" + i).slice(-3);
                  let originalUrl = `https://nflixpainel.cc/video/OBV-${'$'}{chapterStr}`;
                }
              }
            </script>
        """.trimIndent()

        val chapters = NoveFlixParser.parsePanelChapters(Jsoup.parse(html), "https://novefx.biz")
        assertEquals(215, chapters.size)
        assertEquals(1, chapters[0].season)
        assertEquals(1, chapters[0].episode)
        assertEquals("Capítulo 001", chapters[0].name)
        assertEquals("https://nflixpainel.cc/liberar.php?slug=OBV-001", chapters[0].url)
        assertEquals("Capítulo 002 (Legendado)", chapters[1].name)
        assertEquals("https://nflixpainel.cc/liberar.php?slug=OBV-215", chapters[214].url)
    }

    @Test
    fun parsePanelChapters_parsesMultiSeasonJsTemporadasPanel() {
        val html = """
            <div class="lista-container"><ul id="chapter-list"></ul></div>
            <script>
              function loadChapters() {
                const temporadas = [
                  { nome: "TEMPORADA 1", inicio: 1, fim: 3 },
                  { nome: "TEMPORADA 2", inicio: 4, fim: 5 }
                ];
                const legendados = [];
                for (let i = temporada.inicio; i <= temporada.fim; i++) {
                  let chapterStr = ("00" + i).slice(-3);
                  let originalUrl = `https://nflixpainel.cc/video/ROSARIO-${'$'}{chapterStr}`;
                }
              }
            </script>
        """.trimIndent()

        val chapters = NoveFlixParser.parsePanelChapters(Jsoup.parse(html), "https://novefx.biz")
        assertEquals(5, chapters.size)
        assertEquals(1, chapters[0].season)
        assertEquals(1, chapters[0].episode)
        assertEquals(2, chapters[3].season)
        assertEquals(4, chapters[3].episode)
        assertEquals("https://nflixpainel.cc/liberar.php?slug=ROSARIO-005", chapters[4].url)
    }
}
