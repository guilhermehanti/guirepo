package com.kairou

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KairouParserTest {

    @Test
    fun parseTmdbList_parsesMoviesAndTv() {
        val json = """
            {
              "page": 1,
              "total_pages": 5,
              "results": [
                {"id": 299534, "media_type": "movie", "title": "Vingadores: Ultimato", "poster_path": "/p1.jpg", "release_date": "2019-04-24", "vote_average": 8.3, "genre_ids": [28]},
                {"id": 66732, "media_type": "tv", "name": "Stranger Things", "poster_path": "/p2.jpg", "first_air_date": "2016-07-15", "original_language": "en", "genre_ids": [18]}
              ]
            }
        """.trimIndent()

        val (items, hasNext) = KairouParser.parseTmdbList(json)
        assertTrue(hasNext)
        assertEquals(2, items.size)
        assertEquals("movie", items[0].mediaType)
        assertEquals(2019, items[0].year)
        assertEquals("https://image.tmdb.org/t/p/w500/p1.jpg", items[0].posterUrl)
        assertEquals("tv", items[1].mediaType)
        assertEquals("Stranger Things", items[1].title)
    }

    @Test
    fun parseExtractResponse_parses4kMp4HlsAndSubtitles() {
        val json = """
            {
              "ok": true,
              "sources": [
                {
                  "provider": "aurora",
                  "serverLabel": "Aurora (4K) · PT-BR Dub",
                  "url": "/api/proxy?url=https%3A%2F%2Fzeus-addon.shardweb.app%2Fplay%2Fmedia%2Fv1.abc",
                  "kind": "mp4",
                  "resolution": "4K",
                  "height": 2160,
                  "subtitles": [{"label": "Português", "url": "/api/sub/pt.vtt"}]
                },
                {
                  "provider": "iara",
                  "serverLabel": "Iara · PT-BR Dub",
                  "url": "/api/proxy?url=https%3A%2F%2Fvid.example%2Fplaylist.m3u8",
                  "kind": "hls",
                  "height": 1080
                }
              ]
            }
        """.trimIndent()

        val res = KairouParser.parseExtractResponse(json, "https://kairou.space")
        assertEquals(2, res.sources.size)
        assertFalse(res.sources[0].isM3u8)
        assertEquals(2160, res.sources[0].height)
        assertTrue(res.sources[0].url.startsWith("https://kairou.space/api/proxy?url="))

        assertTrue(res.sources[1].isM3u8)
        assertFalse(res.sources[1].isDash)
        assertEquals(1080, res.sources[1].height)
        assertEquals(1, res.subtitles.size)
        assertEquals("https://kairou.space/api/sub/pt.vtt", res.subtitles[0].url)
    }

    @Test
    fun parseExtractResponse_detectsZephyrDashKind() {
        val json = """
            {
              "ok": true,
              "sources": [
                {
                  "provider": "zephyr",
                  "serverLabel": "Zephyr (480p)",
                  "url": "/api/proxy?url=https%3A%2F%2Fproxy.yenime.net%2Fmanifest.mpd%3Furl%3Dfoo",
                  "kind": "dash",
                  "resolution": "480p",
                  "height": 480
                }
              ]
            }
        """.trimIndent()

        val res = KairouParser.parseExtractResponse(json, "https://kairou.space")
        assertEquals(1, res.sources.size)
        assertTrue(res.sources[0].isDash)
        assertFalse(res.sources[0].isM3u8)
        assertEquals(480, res.sources[0].height)
    }

    @Test
    fun mergeExtractResults_deduplicatesAndKeepsBothDubAndLeg() {
        val r1 = KairouExtractResult(
            sources = listOf(
                KairouSource("Aurora (720p) · PT-BR Dub", "https://kairou.space/dub.mp4", false, false, 720),
                KairouSource("Zephyr (480p)", "https://kairou.space/z.mpd", false, true, 480)
            ),
            subtitles = emptyList()
        )
        val r2 = KairouExtractResult(
            sources = listOf(
                KairouSource("Aurora (720p) · PT-BR Dub", "https://kairou.space/dub.mp4", false, false, 720),
                KairouSource("Aurora (720p) · PT-BR Leg", "https://kairou.space/leg.mp4", false, false, 720)
            ),
            subtitles = listOf(KairouSubtitle("Português", "https://kairou.space/pt.vtt"))
        )
        val merged = KairouParser.mergeExtractResults(listOf(r1, r2))
        assertEquals(3, merged.sources.size)
        assertEquals("Aurora (720p) · PT-BR Leg", merged.sources[2].label)
        assertEquals(1, merged.subtitles.size)
    }

    @Test
    fun parseOpenSubtitlesJson_prioritizesBrazilianPortuguese() {
        val json = """
            {
              "subtitles": [
                {"lang": "eng", "url": "https://subs5.strem.io/en.srt"},
                {"lang": "fre", "url": "https://subs5.strem.io/fr.srt"},
                {"lang": "pob", "url": "https://subs5.strem.io/ptbr.srt"},
                {"lang": "por", "url": "https://subs5.strem.io/pt.srt"}
              ]
            }
        """.trimIndent()
        val subs = KairouParser.parseOpenSubtitlesJson(json)
        assertEquals(3, subs.size)
        assertEquals("Português (Brasil)", subs[0].label)
        assertEquals("https://subs5.strem.io/ptbr.srt", subs[0].url)
        assertEquals("Português", subs[1].label)
        assertEquals("Inglês", subs[2].label)
    }

    @Test
    fun parseExtractResponse_unwrapsSolsticeProxyAndTagsOriginal() {
        val json = """
            {
              "ok": true,
              "sources": [
                {
                  "provider": "solstice",
                  "serverLabel": "Solstice (4K)",
                  "url": "/api/proxy?url=https%3A%2F%2Froute.transcode.cfd%2Fm3u8-proxy.m3u8%3Fdata%3Dabc123&referer=https%3A%2F%2Fatlantic.bz%2F",
                  "kind": "hls",
                  "height": 2160,
                  "audioTracks": [{"label": "Original", "language": "en"}]
                }
              ]
            }
        """.trimIndent()
        val res = KairouParser.parseExtractResponse(json, "https://kairou.space")
        assertEquals(1, res.sources.size)
        assertEquals("Solstice (4K) · Legendado (Original)", res.sources[0].label)
        assertEquals("https://route.transcode.cfd/m3u8-proxy.m3u8?data=abc123", res.sources[0].url)
        assertEquals("https://atlantic.bz/", res.sources[0].referer)
        assertTrue(res.sources[0].isM3u8)
        assertEquals(2160, res.sources[0].height)
    }
}
