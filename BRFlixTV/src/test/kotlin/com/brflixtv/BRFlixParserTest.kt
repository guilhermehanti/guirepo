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

    @Test
    fun parseApiChannels_extractsLogosAndFiltersAdult() {
        val json = """
            {
              "success": true,
              "data": [
                {"id":"sportv","name":"SporTV","category":"Esportes","logo_url":"https://reidosembeds.lat/img/sportv.png","is_active":true},
                {"id":"sexyhot","name":"Sexy Hot","category":"Adulto","logo_url":"https://reidosembeds.lat/img/sexyhot.png","is_active":true},
                {"id":"offline","name":"Canal Off","category":"Variedades","logo_url":"https://reidosembeds.lat/img/off.png","is_active":false}
              ]
            }
        """.trimIndent()

        val channels = BRFlixParser.parseApiChannels(json, "https://brflix.online")
        assertEquals(1, channels.size)
        assertEquals("sportv", channels[0].id)
        assertEquals("https://reidosembeds.lat/img/sportv.png", channels[0].posterUrl)
        assertEquals("Esportes", channels[0].category)
    }

    @Test
    fun parseAgendaEvents_extractsChannelSlugEmbedTargets() {
        val json = """
            {
              "ok": true,
              "data": [
                {
                  "id": "e/al-nassr-x-al-sadd",
                  "slug": "al-nassr-x-al-sadd",
                  "title": "Al-Nassr x Al Sadd",
                  "competition": "Champions da Ásia",
                  "status": "live",
                  "start_time": "2026-11-24T15:15:00-03:00",
                  "directEmbedUrl": "espn4",
                  "play_url": "https://abc.ebtv.lat/espn4",
                  "embeds": [
                    {"name": "ESPN 4", "slug": "espn4", "embed_url": "https://abc.ebtv.lat/espn4"}
                  ]
                }
              ]
            }
        """.trimIndent()

        val events = BRFlixParser.parseAgendaEvents(json)
        assertEquals(1, events.size)
        assertEquals("15:15", events[0].timeStr)
        assertEquals(1, events[0].embedTargets.size)
        assertEquals("espn4", events[0].embedTargets[0].slug)
        assertEquals("ESPN 4", events[0].embedTargets[0].label)
    }

    @Test
    fun parseAgendaEvents_extractsIsoTimeCorrectlyWithoutMatchingSeconds() {
        val json = """
            {
              "ok": true,
              "data": [
                {"id":"e/g1","slug":"g1","title":"Wolves x Derby","status":"live","start_time":"2026-11-24T17:00:00-03:00"},
                {"id":"e/g2","slug":"g2","title":"Valencia x Panathinaikos","status":"upcoming","start_time":"2026-11-24T16:30:00-03:00"}
              ]
            }
        """.trimIndent()

        val events = BRFlixParser.parseAgendaEvents(json)
        assertEquals(2, events.size)
        assertEquals("17:00", events[0].timeStr)
        assertEquals("16:30", events[1].timeStr)
    }

    @Test
    fun parseResolveJson_prefersRealM3u8OverDisguisedErrorTxt() {
        val json = """
            {
              "ok": true,
              "hlsUrl": "/api/stream-proxy?url=https%3A%2F%2Fe8d009afe4d01e7c84830c60fbfe5ec6.sv-nl-02-cloudfront-net.lat%2Fstream%2Fabc%2Ferror%2Etxt",
              "directStreams": [
                {"label":"Sinal 1 (HLS)","src":"/api/stream-proxy?url=https%3A%2F%2Fe8d009afe4d01e7c84830c60fbfe5ec6.sv-nl-02-cloudfront-net.lat%2Fstream%2Fabc%2Ferror.txt"},
                {"label":"Sinal 1 (1080p FHD)","src":"/api/stream-proxy?url=http%3A%2F%2Frfc-infinite.top%2Flive%2F1%2F2%2F318.m3u8"},
                {"label":"Sinal 3 (HD Reserva)","src":"/api/stream-proxy?url=http%3A%2F%2Fapontaaimeupatrao.lat%3A80%2Flive%2F1%2F2%2F231470.m3u8"}
              ]
            }
        """.trimIndent()

        val resolved = BRFlixParser.parseResolveJson(json, "https://brflix.online")
        assertEquals(2, resolved.directStreams.size)
        assertEquals("Sinal 1 (1080p FHD)", resolved.directStreams[0].label)
        assertEquals("http://rfc-infinite.top/live/1/2/318.m3u8", resolved.directStreams[0].url)
        assertEquals("Sinal 3 (HD Reserva)", resolved.directStreams[1].label)
    }
}
