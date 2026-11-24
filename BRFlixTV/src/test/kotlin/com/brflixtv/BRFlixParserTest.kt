package com.brflixtv

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BRFlixParserTest {

    @Test
    fun parseHtmlChannels_extractsChannelsAndCategories() {
        val html = """
            <div class="grid">
              <a href="/canal/globosp" class="flex">
                <div><span></span><span>Globo SP HD</span></div>
                <span>abertos</span>
              </a>
              <a href="/canal/sportv" class="flex">
                <div><span></span><span>SporTV HD</span></div>
                <span>esportes</span>
              </a>
              <a href="/canal/sexyhot" class="flex">
                <div><span></span><span>Sexy Hot</span></div>
                <span>adulto</span>
              </a>
            </div>
        """.trimIndent()

        val channels = BRFlixParser.parseHtmlChannels(Jsoup.parse(html), "https://brflix.online", "Variedades")
        assertEquals(2, channels.size)
        assertEquals("globosp", channels[0].id)
        assertEquals("Globo SP HD", channels[0].title)
        assertEquals("TV Aberta", channels[0].category)
        assertEquals("Esportes", channels[1].category)
    }

    @Test
    fun parseAgendaEvents_filtersFinishedAndSortsLiveFirst() {
        val json = """
            {
              "ok": true,
              "data": [
                {"id":"e/jogo-encerrado","slug":"jogo-encerrado","title":"Time A x Time B","competition":"Serie A","status":"finished","start_time":"2026-11-24 14:00:00"},
                {"id":"e/jogo-futuro","slug":"jogo-futuro","title":"Flamengo x Palmeiras","competition":"Brasileirão","status":"upcoming","start_time":"2026-11-24 21:30:00","poster":"https://img.example/1.jpg"},
                {"id":"e/jogo-live","slug":"jogo-live","title":"Real Madrid x Barcelona","competition":"Champions","status":"live","start_time":"2026-11-24 16:00:00","poster":"https://img.example/2.jpg"}
              ]
            }
        """.trimIndent()

        val events = BRFlixParser.parseAgendaEvents(json)
        assertEquals(2, events.size)
        assertTrue(events[0].isLive)
        assertEquals("AO VIVO", events[0].statusBadge)
        assertEquals("16:00", events[0].timeStr)
        assertEquals("EM BREVE", events[1].statusBadge)
        assertEquals("21:30", events[1].timeStr)
    }

    @Test
    fun parseResolveJson_unwrapsStreamProxyAndFallbacks() {
        val json = """
            {
              "ok": true,
              "hlsUrl": "/api/stream-proxy?url=https%3A%2F%2Fe8d009afe4d01e7c84830c60fbfe5ec6.sv-nl-02-cloudfront-net.lat%2Fstream%2Fabc%2Ferror%2Etxt",
              "fallbackEmbeds": [
                {"provider":"Servidor 1 HD","embedUrl":"https://embedtv.my/sportv"}
              ]
            }
        """.trimIndent()

        val resolved = BRFlixParser.parseResolveJson(json, "https://brflix.online")
        assertEquals(1, resolved.directStreams.size)
        assertEquals(
            "https://e8d009afe4d01e7c84830c60fbfe5ec6.sv-nl-02-cloudfront-net.lat/stream/abc/error.txt",
            resolved.directStreams[0].url
        )
        assertEquals(1, resolved.fallbackEmbeds.size)
        assertEquals("https://embedtv.my/sportv", resolved.fallbackEmbeds[0].second)
    }
}
