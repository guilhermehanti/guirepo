package com.redecanais.tv

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RedeCanaisTvParserTest {

    private val baseUrl = "https://redecanais.ro"

    @Test
    fun parseChannels_parsesListFiltersAdultAndCategorizes() {
        val html = """
            <section class="list-channels overflow-auto">
              <div class="channel">
                <a href="/canais-de-tv/assistir-band-sports-online-24-horas-ao-vivo_e192145ad.html">
                  <div class="number">015</div>
                  <div class="logo"><img src="/canais-de-tv/assets/imgs-videos/Canais/bandsports.jpg" alt="Band Sports"></div>
                  <div class="name"><span class="badge badge-info mb-1 d-none">Assistindo</span><span>Band Sports</span></div>
                </a>
              </div>
              <div class="channel">
                <a href="/canais-de-tv/assistir-cartoon-network-online-24-horas-ao-vivo_ec48ab159.html">
                  <div class="logo"><img src="/canais-de-tv/assets/imgs-videos/Canais/cn.jpg" alt="Cartoon Network"></div>
                  <div class="name"><span class="badge badge-info mb-1 d-none">Assistindo</span><span>Cartoon Network</span></div>
                </a>
              </div>
              <div class="channel">
                <a href="/canais-de-tv/assistir-sexy-hot-ao-vivo.html">
                  <div class="logo"><img src="/canais-de-tv/assets/imgs-videos/Canais/sexyhot.jpg" alt="Sexy Hot"></div>
                  <div class="name"><span>Sexy Hot</span></div>
                </a>
              </div>
            </section>
        """.trimIndent()

        val channels = RedeCanaisTvParser.parseChannels(Jsoup.parse(html), baseUrl)
        assertEquals(2, channels.size)
        assertEquals("Band Sports", channels[0].name)
        assertEquals("Esportes", channels[0].category)
        assertEquals("https://redecanais.ro/canais-de-tv/assets/imgs-videos/Canais/bandsports.jpg", channels[0].posterUrl)
        assertEquals("Cartoon Network", channels[1].name)
        assertEquals("Infantis", channels[1].category)
    }

    @Test
    fun extractLiveIframeAndStream_extractsM3u8() {
        val pageHtml = """
            <iframe src="/canais-de-tv/player3/live.php?etv=bandsports" allowfullscreen></iframe>
        """.trimIndent()
        val iframe = RedeCanaisTvParser.extractLiveIframe(pageHtml, baseUrl)
        assertEquals("https://redecanais.ro/canais-de-tv/player3/live.php?etv=bandsports", iframe)

        val playerHtml = """
            <script>
            var SRC = "https://c904ee7a49338de077316979cdfab07a.sv-nl-02-cloudfront-net.lat/yag/bandsports.m3u8";
            </script>
        """.trimIndent()
        val stream = RedeCanaisTvParser.extractLiveStream(playerHtml)
        assertNotNull(stream)
        assertEquals(
            "https://c904ee7a49338de077316979cdfab07a.sv-nl-02-cloudfront-net.lat/yag/bandsports.m3u8",
            stream
        )
    }
}
