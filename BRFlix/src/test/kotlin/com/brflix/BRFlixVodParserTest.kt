package com.brflix

import org.junit.Assert.assertEquals
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
}
