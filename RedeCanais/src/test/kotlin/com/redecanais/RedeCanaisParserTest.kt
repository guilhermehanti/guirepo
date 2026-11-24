package com.redecanais

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedeCanaisParserTest {

    private val baseUrl = "https://redecanais.ro"

    @Test
    fun parseTitleMetadata_extractsTitleYearAndAudio() {
        val parsed = RedeCanaisParser.parseTitleMetadata("101 Dálmatas: A Série (Dublado) - 1997 - 1080p")
        assertEquals("101 Dálmatas: A Série", parsed.title)
        assertEquals(1997, parsed.year)
        assertEquals("Dublado", parsed.audioTag)
    }

    @Test
    fun parseCards_extractsSeriesAndMovieCards() {
        val html = """
            <ul class="row pm-ul-browse-videos list-unstyled" id="pm-grid">
              <li>
                <div class="thumbnail">
                  <div class="pm-video-thumb">
                    <a href="/101-dalmatas-a-serie-lista-de-episodios_4c8a98acd.html" title="101 Dálmatas: A Série (Dublado) - 1997 - 1080p">
                      <img src="/tmdb/t/p/w342/A7gzczpkPO68KAtecmJSWZchZce.jpg" />
                    </a>
                  </div>
                  <div class="caption">
                    <h3><a href="/101-dalmatas-a-serie-lista-de-episodios_4c8a98acd.html" title="101 Dálmatas: A Série (Dublado) - 1997 - 1080p">101 Dálmatas: A Série (Dublado) - 1997 - 1080p</a></h3>
                  </div>
                </div>
              </li>
              <li>
                <div class="thumbnail">
                  <div class="pm-video-thumb">
                    <a href="/101-dalmatas-1996-dublado_e6d84dcb3.html" title="101 Dálmatas (Dublado) - 1996 - 1080p">
                      <img src="/tmdb/t/p/w342/aq0MURghFbEKgHYGWyurtuwGzdx.jpg" />
                    </a>
                  </div>
                  <div class="caption">
                    <h3><a href="/101-dalmatas-1996-dublado_e6d84dcb3.html" title="101 Dálmatas (Dublado) - 1996 - 1080p">101 Dálmatas (Dublado) - 1996 - 1080p</a></h3>
                  </div>
                </div>
              </li>
            </ul>
        """.trimIndent()

        val cards = RedeCanaisParser.parseCards(Jsoup.parse(html), baseUrl)
        assertEquals(2, cards.size)

        assertEquals("101 Dálmatas: A Série", cards[0].title)
        assertEquals("https://redecanais.ro/101-dalmatas-a-serie-lista-de-episodios_4c8a98acd.html", cards[0].url)
        assertEquals("https://redecanais.ro/tmdb/t/p/w342/A7gzczpkPO68KAtecmJSWZchZce.jpg", cards[0].posterUrl)
        assertEquals(1997, cards[0].year)
        assertTrue(cards[0].isSeries)

        assertEquals("101 Dálmatas", cards[1].title)
        assertFalse(cards[1].isSeries)
    }

    @Test
    fun parseSeriesEpisodes_parsesMultipleSeasonsAndAudioOptions() {
        val html = """
            <div itemprop="description">
              <p>
                <span class="rc-backdrop-frame"><img class="rc-backdrop-image" src="/tmdb/t/p/w780/bg.jpg"></span><br>
                <span style="font-size: x-large;"><strong>1&ordf; Temporada</strong></span><br>
                <strong>Epis&oacute;dio 01 - 101 Dálmatas: A Série - 1ª Temporada - Episódio 01 - </strong><strong><a href="/101-dalmatas-s01e01_f23cbf1d3.html">Assistir</a></strong><br>
                <strong>Epis&oacute;dio 02 - S01E02 - Episódio 2 - </strong><strong><a href="/101-dalmatas-s01e02-dub.html">Dublado</a> / <a href="/101-dalmatas-s01e02-leg.html">Legendado</a></strong><br>
              </p>
              <p>
                <span style="font-size: x-large;"><strong>2&ordf; Temporada</strong></span><br>
                <strong>Epis&oacute;dio 01 - S02E01 - Episódio 1 - </strong><strong><a href="/101-dalmatas-s02e01_b92370988.html">Assistir</a></strong><br>
              </p>
            </div>
        """.trimIndent()

        val episodes = RedeCanaisParser.parseSeriesEpisodes(Jsoup.parse(html), baseUrl)
        assertEquals(3, episodes.size)

        assertEquals(1, episodes[0].season)
        assertEquals(1, episodes[0].episode)
        assertEquals(1, episodes[0].links.size)
        assertEquals("https://redecanais.ro/101-dalmatas-s01e01_f23cbf1d3.html", episodes[0].links[0].url)

        assertEquals(1, episodes[1].season)
        assertEquals(2, episodes[1].episode)
        assertEquals(2, episodes[1].links.size)
        assertEquals("Dublado", episodes[1].links[0].label)
        assertEquals("Legendado", episodes[1].links[1].label)

        assertEquals(2, episodes[2].season)
        assertEquals(1, episodes[2].episode)
        assertEquals("https://redecanais.ro/101-dalmatas-s02e01_b92370988.html", episodes[2].links[0].url)
    }

    @Test
    fun extractPlayerIframeAndStreamSource_supportsMp4AndHls() {
        val epHtml = """
            <div id="video-wrapper">
              <iframe name="Player" src="/player3/server.php?categoria=vod&server=R2&subfolder=media&vid=r2-297194" frameborder="0"></iframe>
            </div>
        """.trimIndent()
        val iframe = RedeCanaisParser.extractPlayerIframe(epHtml, baseUrl)
        assertEquals(
            "https://redecanais.ro/player3/server.php?categoria=vod&server=R2&subfolder=media&vid=r2-297194",
            iframe
        )

        val mp4PlayerHtml = """
            <script>
            var SRC = "https://cnn.radiogaucha.fun/101-dalmatas-a-serie/abc.mp4?exp=123&sig=xyz";
            var SOURCE_FORMAT = "file";
            var SOURCE_MIME = "video\/mp4";
            </script>
        """.trimIndent()
        val mp4 = RedeCanaisParser.extractStreamSource(mp4PlayerHtml)
        assertNotNull(mp4)
        assertEquals("https://cnn.radiogaucha.fun/101-dalmatas-a-serie/abc.mp4?exp=123&sig=xyz", mp4!!.url)
        assertFalse(mp4.isM3u8)

        val hlsPlayerHtml = """
            <script>
            var SRC = "https:\/\/cdn.example.com\/stream\/master.m3u8?token=1";
            var SOURCE_FORMAT = "hls";
            var SOURCE_MIME = "application\/x-mpegURL";
            </script>
        """.trimIndent()
        val hls = RedeCanaisParser.extractStreamSource(hlsPlayerHtml)
        assertNotNull(hls)
        assertEquals("https://cdn.example.com/stream/master.m3u8?token=1", hls!!.url)
        assertTrue(hls.isM3u8)
    }
}
