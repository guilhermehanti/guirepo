package com.brflix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BRFlixVodParserTest {

    @Test
    fun parseClickhostServers_skipsDownAndPrioritizesSpeedNet() {
        val html = """
            <script>
            window.ApiEmbedConfig = {
              streamUrl: null,
              servers: [
                {"down": false, "id": 11544547, "is_hls": false, "label": "FlashTV (8200) · Fonte 1", "url": null},
                {"down": true, "id": 999, "is_hls": false, "label": "Offline", "url": null},
                {"down": false, "id": 10716144, "is_hls": false, "label": "SpeedNET", "url": null}
              ],
              contentName: "Vingadores: Ultimato (2019)"
            };
            </script>
        """.trimIndent()

        val servers = BRFlixVodParser.parseClickhostServers(html)
        assertEquals(2, servers.size)
        assertEquals("SpeedNET", servers[0].label)
        assertEquals(10716144L, servers[0].id)
        assertEquals(11544547L, servers[1].id)
    }

    @Test
    fun parseClickhostInit_resolvesRelativeStreamUrl() {
        val json = """
            {"ok":true,"url":"/embed/stream/10716144?token=abc123","is_hls":false,"is_native":true}
        """.trimIndent()

        val init = BRFlixVodParser.parseClickhostInit(json, "https://embed-api.clickhost.xyz")
        assertNotNull(init)
        assertEquals(
            "https://embed-api.clickhost.xyz/embed/stream/10716144?token=abc123",
            init!!.url
        )
    }

    @Test
    fun parseTmdbList_parsesItemsAndPagination() {
        val json = """
            {
              "page": 1,
              "total_pages": 3,
              "results": [
                {"id": 598, "media_type": "movie", "title": "Cidade de Deus", "poster_path": "/cd.jpg", "release_date": "2002-08-30", "genre_ids": [18]}
              ]
            }
        """.trimIndent()

        val (items, hasNext) = BRFlixVodParser.parseTmdbList(json)
        assertTrue(hasNext)
        assertEquals(1, items.size)
        assertEquals("Cidade de Deus", items[0].title)
        assertEquals(2002, items[0].year)
    }

    @Test
    fun isPlayableHlsManifest_rejectsEmptyMegaVodsManifestAndAcceptsRealHls() {
        val emptyMegaVods = "#EXTM3U\n#EXT-X-TARGETDURATION:10\n"
        val validPlaylist = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\n/embed/segment/abc\n"
        val validMaster = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\nplaylist.m3u8\n"

        assertFalse(BRFlixVodParser.isPlayableHlsManifest(emptyMegaVods))
        assertTrue(BRFlixVodParser.isPlayableHlsManifest(validPlaylist))
        assertTrue(BRFlixVodParser.isPlayableHlsManifest(validMaster))
    }

    @Test
    fun classifyStreamHead_acceptsMp4AndRealHlsAndRejectsMkvStubHlsAnd502() {
        assertEquals(
            BRFlixVodParser.ProbeVerdict.VIDEO,
            BRFlixVodParser.classifyStreamHead(200, "video/mp4", 3_945_430_301L, false, "https://embed-api.clickhost.xyz/embed/stream/1")
        )
        assertEquals(
            BRFlixVodParser.ProbeVerdict.REJECT,
            BRFlixVodParser.classifyStreamHead(200, "video/x-matroska; charset=utf-8", 5_342_536_626L, false, "https://embed-api.clickhost.xyz/embed/stream/2")
        )
        assertEquals(
            BRFlixVodParser.ProbeVerdict.HLS,
            BRFlixVodParser.classifyStreamHead(200, "application/vnd.apple.mpegurl", null, false, "https://embed-api.clickhost.xyz/embed/stream/3")
        )
        assertEquals(
            BRFlixVodParser.ProbeVerdict.REJECT,
            BRFlixVodParser.classifyStreamHead(200, "application/vnd.apple.mpegurl", 33L, false, "https://embed-api.clickhost.xyz/embed/stream/4")
        )
        assertEquals(
            BRFlixVodParser.ProbeVerdict.REJECT,
            BRFlixVodParser.classifyStreamHead(502, "text/html; charset=UTF-8", 6476L, false, "https://embed-api.clickhost.xyz/embed/stream/5")
        )
    }

    @Test
    fun parseFenixFlixStreams_extractsAndPrioritizesDubbed4kAnd1080p() {
        val json = """
            {
              "streams": [
                {
                  "name": "FenixFlix 720p",
                  "title": "A Odisseia\nLegendado\nFenixHub",
                  "url": "https://fenixflixstrream.duckdns.org/stream/1?hash=abc",
                  "behaviorHints": {"filename": "720p"}
                },
                {
                  "name": "FenixFlix 4K",
                  "title": "A Odisseia\nDublado\nFiggs",
                  "url": "https://hub.fenixhub.online/r/hf_xyz",
                  "behaviorHints": {"filename": "4K"}
                }
              ]
            }
        """.trimIndent()

        val streams = BRFlixVodParser.parseFenixFlixStreams(json)
        assertEquals(2, streams.size)
        assertEquals(2160, streams[0].quality)
        assertEquals("", streams[0].referer)
        assertTrue(streams[0].label.contains("4K"))
        assertTrue(streams[0].label.contains("Dublado"))
    }

    @Test
    fun parseZeusResponse_parsesUnencryptedCandidatesAndMapsOnePieceEpisodes() {
        val json = """
            {
              "ok": true,
              "candidates": [
                {
                  "url": "/dynamic-proxy/nsrv.classotaku.app/file/classgg/750/ep01.mp4",
                  "rawUrl": "https://nsrv.classotaku.app/file/classgg/750/ep01.mp4",
                  "type": "mp4",
                  "height": 720,
                  "dubbed": true,
                  "headers": {"User-Agent": "IPTVSmartersPro"}
                }
              ]
            }
        """.trimIndent()

        val res = BRFlixVodParser.parseZeusResponse(json, "https://brflix.lat")
        assertEquals(1, res.streams.size)
        assertEquals("https://nsrv.classotaku.app/file/classgg/750/ep01.mp4", res.streams[0].url)
        assertEquals(720, res.streams[0].quality)
        assertEquals("IPTVSmartersPro", res.streams[0].headers["User-Agent"])

        assertEquals(21 to 10, BRFlixVodParser.mapClickhostTvEpisode(37854, 21, 901))
        assertEquals(1 to 1, BRFlixVodParser.mapClickhostTvEpisode(37854, 1, 1))
        assertFalse(BRFlixVodParser.looksLikeJsonObject("<!DOCTYPE html><html>451</html>"))
        assertTrue(BRFlixVodParser.looksLikeJsonObject("{\"id\": 969681}"))
    }
}
