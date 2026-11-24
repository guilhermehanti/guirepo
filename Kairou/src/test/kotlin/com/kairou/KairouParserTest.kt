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
        assertEquals(1080, res.sources[1].height)
        assertEquals(1, res.subtitles.size)
        assertEquals("https://kairou.space/api/sub/pt.vtt", res.subtitles[0].url)
    }
}
