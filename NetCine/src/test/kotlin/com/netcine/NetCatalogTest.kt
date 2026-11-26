package com.netcine

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class NetCatalogTest {
    @Test fun resolvesRelativePlayerFromSiteRootAndReadsTabLabel() {
        val html = """
            <div id="player-container">
              <div id="play-1"><iframe src="media-player/hlsarchive.php?n=FILMEDUB&amp;p=zorel"></iframe></div>
              <div id="play-2"><iframe src="media-player/hlsarchive.php?n=FILMELEG&amp;p=zorel"></iframe></div>
              <ul class="player-menu">
                <li><a href="#play-1">Dublado (HD) 1</a></li>
                <li><a href="#play-2">Legendado (HD) 2</a></li>
              </ul>
            </div>
            <iframe id="commentsIframe" src=""></iframe>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://maskara.lat/filme/")
        val players = NetCatalog.players(doc, "https://maskara.lat")
        assertEquals(2, players.size)
        assertEquals("https://maskara.lat/media-player/hlsarchive.php?n=FILMEDUB&p=zorel", players[0].url)
        assertEquals("Dublado", players[0].audio)
        assertEquals("Legendado", players[1].audio)
    }

    @Test fun extractsOnlineHlsUrlFromArchivePage() {
        val html = """<a href="https://maskara.lat/media-player/hls/hls.php?n=TEST&amp;p=zorel&amp;apr=abc" class="btn">Assistir Online</a>"""
        val doc = Jsoup.parse(html, "https://maskara.lat/media-player/hlsarchive.php?n=TEST&p=zorel")
        val online = NetCatalog.extractOnlineHlsUrl(doc, "https://maskara.lat/media-player/hlsarchive.php?n=TEST&p=zorel")
        assertEquals("https://maskara.lat/media-player/hls/hls.php?n=TEST&p=zorel&apr=abc", online)
    }

    @Test fun readsSeasonAndEpisodeFromVisibleNumber() {
        val doc = Jsoup.parse("""<div id="cssmenu"><a href="/episode/test-02x03/"><span class="datex">2 - 3</span><span class="datix">Título</span></a></div>""", "https://maskara.lat/")
        val ep = NetCatalog.episodes(doc).single()
        assertEquals(2, ep.season)
        assertEquals(3, ep.number)
        assertEquals("Título", ep.title)
    }

    @Test fun ignoresRelatedMoviesAndUpgradesThumbnailPoster() {
        val doc = Jsoup.parse("""<div id="box_movies"><div class="movie"><h2>Nome</h2><span class="year">2026</span><img src="data:image/svg+xml,a" data-src="https://cdn.bcdn.zip/wp-content/uploads/2026/11/m4SYFvN-120x170.png"><a href="/tvshows/nome/"></a></div></div><div class="movie-r"><h4>Relacionado</h4><a href="/other/"></a></div>""", "https://maskara.lat/")
        val item = NetCatalog.items(doc).single()
        assertTrue(item.series)
        assertEquals(2026, item.year)
        assertEquals("https://cdn.bcdn.zip/wp-content/uploads/2026/11/m4SYFvN.png", item.poster)
    }
}
