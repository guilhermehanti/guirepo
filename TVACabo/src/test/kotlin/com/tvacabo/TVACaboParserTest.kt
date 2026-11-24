package com.tvacabo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TVACaboParserTest {

    @Test
    fun parseChannelsJson_filtersBlockedAndNormalizesCategories() {
        val json = """
            [
              {"nome":"JOGOS DO DIA","url":"https://tvacabo.top/esporte.html","categoria":"Destaques"},
              {"nome":"EXTRA +18","url":"http://tvacabo.c1.biz/aviso.html","categoria":"Destaques"},
              {"nome":"CNN BRASIL","url":"https://playanym3u8.com/embed.html?src=https://cdn.example/live.m3u8","categoria":"Noticias"},
              {"nome":"HBO","url":"https://tvacabo.top/iframe.html?url=https://w1.ebtv.lat/hbo","categoria":"Filmes/Series"}
            ]
        """.trimIndent()

        val channels = TVACaboParser.parseChannelsJson(json)
        assertEquals(2, channels.size)
        assertEquals("Notícias", channels[0].category)
        assertEquals("Filmes", channels[1].category)
    }

    @Test
    fun parseSportsEventsJson_filtersFinishedAndSortsLiveFirst() {
        val json = """
            {
              "success": true,
              "data": [
                {"id":"old","title":"Old Game","competition":"A","start_time":"2026-11-23 19:00:00","start_timestamp":100,"status":"finished","embeds":[{"provider":"P1","embed_url":"https://esportesembed.cc/old"}]},
                {"id":"later","title":"Flamengo x Vasco","competition":"Brasileirão","start_time":"2026-11-24 21:30:00","start_timestamp":300,"status":"upcoming","embeds":[{"provider":"Premiere","embed_url":"https://esportesembed.cc/fla"}]},
                {"id":"now","title":"Brasil x Argentina","competition":"Eliminatórias","start_time":"2026-11-24 15:00:00","start_timestamp":200,"status":"live","embeds":[{"provider":"SporTV","embed_url":"https://esportesembed.cc/bra"}]}
              ]
            }
        """.trimIndent()

        val events = TVACaboParser.parseSportsEventsJson(json)
        assertEquals(2, events.size)
        assertTrue(events[0].isLive)
        assertEquals("AO VIVO", events[0].statusBadge)
        assertEquals("15:00", events[0].timeStr)
        assertEquals("EM BREVE", events[1].statusBadge)
    }

    @Test
    fun unwrapWrapperUrlAndRedeCanaisFallback_works() {
        assertEquals(
            "https://sbtnews.maissbt.com/index.m3u8",
            TVACaboParser.unwrapWrapperUrl("https://tvacabo.top/player.html?url=https://sbtnews.maissbt.com/index.m3u8")
        )
        assertEquals(
            "https://w1.ebtv.lat/globonews",
            TVACaboParser.unwrapWrapperUrl("https://tvacabo.top/iframe.html?url=https://w1.ebtv.lat/globonews")
        )
        assertEquals(
            "https://redecanais.ro/canais-de-tv/player3/live.php?etv=cnnbrasil",
            TVACaboParser.redeCanaisLiveFallback("https://redecanaistv.by/player3/ch.php?canal=cnnbrasil")
        )
    }

    @Test
    fun extractDataCStreams_decodesReversedBase64HlsUrls() {
        val html = """
            <div id="player" data-c="=0XXiM2YuQWZi1WZzVGdy9GczVmL3d3diwiIjNmLzlWYuF2YkJnL3d3diwiIjNmLkVmYtV2clRncvB3clJCLiM2YuMXah5WYjRmciwiI0NnLtVXY6RWZi1WZiwiInJ3buQWZi1WZzVGdy9GczVmIsIyZy9mLzlWYuF2YkJnIbpjIhJCLiQmb11Gdy9GZtEWazNXdy9mYtgXLsFmblNnchJiOiQnIsICZuVXb0J3bk1SYpN3c1J3bi1CetwWYuV2cyFmOjRmciojIzJCLdJCO1NTbugXZk5WavQmb11Gdy9GZtEWazNXdy9mYtgXLsFmblNnch9yN3EzMiR2NxMWYzAjYihTMvYDM3MzN1UTO3EzLvVXalBFU6NGMC5EURVVR3ZnUhpUSzR2T35WTxVTODh3bzZUZwUFUtgzXI9SZyV3YlN3LlZXas9SZ2lGbucWbpR3chZmLkJXYnNXYv8iOzBHd0hmIsICO1NTbugXZk5WavQmb11Gdy9GZtEWazNXdy9mYtgXLsFmblNnch9yN3EzMiR2NxMWYzAjYihTMvYDM3MzN1UTO3EzLvVXalBFU6NGMC5EURVVR3ZnUhpUSzR2T35WTxVTODh3bzZUZwUFUtgzXI9SZyV3YlN3LlZXas9CdzVmYu0WYlJHdzFWakVWbuMXYsRXYv8iOzBHd0hmIbpjI1Jye"></div>
        """.trimIndent()

        val urls = TVACaboParser.extractDataCStreams(html)
        assertEquals(2, urls.size)
        assertTrue(urls[0].startsWith("https://atlas.mediastream.best/live/secure/"))
        assertTrue(urls[0].endsWith("/index.m3u8"))
        assertTrue(urls[1].startsWith("https://asgard.fastimg.live/live/secure/"))
    }

    @Test
    fun extractClearKeyDash_convertsHexKeysToBase64Url() {
        val html = """
            <script>
              window.url    = "https://live-pv-ta.amazon.fastly-edge.com/cenc.mpd";
              window.ck_id  = "6f8c882c6744246cc84282b0e0effab5";
              window.ck_key = "dd9ab472acead0eb65f951b02754e14b";
            </script>
        """.trimIndent()

        val ck = TVACaboParser.extractClearKeyDash(html)
        assertTrue(ck != null)
        assertEquals("https://live-pv-ta.amazon.fastly-edge.com/cenc.mpd", ck!!.url)
        assertEquals("b4yILGdEJGzIQoKw4O_6tQ", ck.kidBase64Url)
        assertEquals("3Zq0cqzq0Otl-VGwJ1ThSw", ck.keyBase64Url)
    }
}
