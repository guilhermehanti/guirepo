package com.brflix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLEncoder

@CloudstreamPlugin
class BRFlixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BRFlix())
    }
}

class BRFlix : MainAPI() {
    override var mainUrl = "https://brflix.lat"
    override var name = "BRFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    companion object {
        private const val EMBED_ORIGIN = "https://embed-api.clickhost.xyz"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/tmdb-cached/trending/movie/week?language=pt-BR&page=%d|movie" to "Filmes em Alta",
        "$mainUrl/tmdb-cached/trending/tv/week?language=pt-BR&page=%d|tv" to "Séries em Alta",
        "$mainUrl/tmdb-cached/movie/popular?language=pt-BR&page=%d|movie" to "Filmes Populares",
        "$mainUrl/tmdb-cached/tv/popular?language=pt-BR&page=%d|tv" to "Séries Populares",
        "$mainUrl/tmdb-cached/discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc&language=pt-BR&page=%d|tv" to "Animes",
        "$mainUrl/tmdb-cached/discover/tv?with_genres=16&without_original_language=ja&sort_by=popularity.desc&language=pt-BR&page=%d|tv" to "Desenhos"
    )

    private fun defaultHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/html, */*",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to referer
    )

    private fun BrFlixVodItem.toSearchResponse(): SearchResponse {
        val path = if (mediaType == "movie") "filme" else "serie"
        val itemUrl = "$mainUrl/$path/$id"
        return if (mediaType == "movie") {
            newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                this.posterUrl = this@toSearchResponse.posterUrl
                this.year = this@toSearchResponse.year
            }
        } else {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            newTvSeriesSearchResponse(title, itemUrl, type) {
                this.posterUrl = this@toSearchResponse.posterUrl
                this.year = this@toSearchResponse.year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val parts = request.data.split("|")
        val template = parts[0]
        val forcedType = parts.getOrNull(1)
        val url = template.format(page)

        val jsonText = app.get(url, headers = defaultHeaders()).text
        val (items, hasNext) = BRFlixVodParser.parseTmdbList(jsonText, forcedType)
        return newHomePageResponse(request.name, items.map { it.toSearchResponse() }, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/tmdb-cached/search/multi?query=$encoded&language=pt-BR&page=1"
        val jsonText = app.get(url, headers = defaultHeaders()).text
        val (items, _) = BRFlixVodParser.parseTmdbList(jsonText, null)
        return items.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val clean = url.substringBefore("?").trimEnd('/')
        val segment = clean.substringAfterLast("/")
        val id = segment.substringBefore("-").toIntOrNull()
            ?: throw ErrorLoadingException("ID TMDB inválido: $url")
        val isMovie = clean.contains("/filme/") || clean.contains("/movie/")

        if (isMovie) {
            val detailJson = app.get("$mainUrl/tmdb-cached/movie/$id?language=pt-BR", headers = defaultHeaders()).text
            val obj = JSONObject(detailJson)
            val title = obj.optString("title").ifBlank { obj.optString("original_title") }.trim()
            val poster = BRFlixVodParser.tmdbImage(obj.optString("poster_path"), "w500")
            val backdrop = BRFlixVodParser.tmdbImage(obj.optString("backdrop_path"), "w1280")
            val year = obj.optString("release_date").take(4).toIntOrNull()
            val runtime = obj.optInt("runtime", 0).takeIf { it > 0 }
            val plot = obj.optString("overview").trim().ifBlank { null }
            val tags = parseGenres(obj)

            return newMovieLoadResponse(title, clean, TvType.Movie, "movie|$id") {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop ?: poster
                this.year = year
                this.duration = runtime
                this.plot = plot
                this.tags = tags
            }
        }

        val detailJson = app.get("$mainUrl/tmdb-cached/tv/$id?language=pt-BR", headers = defaultHeaders()).text
        val obj = JSONObject(detailJson)
        val title = obj.optString("name").ifBlank { obj.optString("original_name") }.trim()
        val poster = BRFlixVodParser.tmdbImage(obj.optString("poster_path"), "w500")
        val backdrop = BRFlixVodParser.tmdbImage(obj.optString("backdrop_path"), "w1280")
        val year = obj.optString("first_air_date").take(4).toIntOrNull()
        val plot = obj.optString("overview").trim().ifBlank { null }
        val tags = parseGenres(obj)

        val seasonNumbers = mutableListOf<Int>()
        val seasonsArr = obj.optJSONArray("seasons")
        if (seasonsArr != null) {
            for (i in 0 until seasonsArr.length()) {
                val s = seasonsArr.optJSONObject(i) ?: continue
                val num = s.optInt("season_number", 0)
                if (num > 0) seasonNumbers.add(num)
            }
        }
        if (seasonNumbers.isEmpty()) seasonNumbers.add(1)

        val allEpisodes = mutableListOf<Episode>()
        val seasonChunks = seasonNumbers.take(25).chunked(5)
        for (chunk in seasonChunks) {
            val fetched = chunk.amap { sNum ->
                try {
                    val sJson = app.get(
                        "$mainUrl/tmdb-cached/tv/$id/season/$sNum?language=pt-BR",
                        headers = defaultHeaders()
                    ).text
                    BRFlixVodParser.parseSeasonEpisodes(sJson, sNum)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            for (epList in fetched) {
                for (ep in epList) {
                    allEpisodes.add(
                        newEpisode("tv|$id|${ep.season}|${ep.episode}") {
                            this.name = ep.name
                            this.season = ep.season
                            this.episode = ep.episode
                            this.description = ep.overview
                            this.posterUrl = ep.posterUrl ?: backdrop ?: poster
                        }
                    )
                }
            }
        }

        return newTvSeriesLoadResponse(title, clean, TvType.TvSeries, allEpisodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop ?: poster
            this.year = year
            this.plot = plot
            this.tags = tags
        }
    }

    private fun parseGenres(obj: JSONObject): List<String> {
        val arr = obj.optJSONArray("genres") ?: return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val g = arr.optJSONObject(i)?.optString("name")?.trim().orEmpty()
            if (g.isNotBlank()) result.add(g)
        }
        return result
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val type = parts.getOrNull(0) ?: return false
        val id = parts.getOrNull(1) ?: return false

        val embedUrl = if (type == "movie") {
            "$EMBED_ORIGIN/embed/filme/$id"
        } else {
            val s = parts.getOrNull(2) ?: "1"
            val e = parts.getOrNull(3) ?: "1"
            "$EMBED_ORIGIN/embed/serie/$id/$s/$e"
        }

        val embedHtml = try {
            app.get(embedUrl, headers = defaultHeaders(referer = "$mainUrl/")).text
        } catch (_: Exception) {
            return false
        }

        val servers = BRFlixVodParser.parseClickhostServers(embedHtml)
        if (servers.isEmpty()) return false

        var emitted = false

        servers.amap { server ->
            try {
                val initResult = if (!server.directUrl.isNullOrBlank()) {
                    ClickhostInitResult(
                        url = BRFlixVodParser.resolveUrl(server.directUrl, EMBED_ORIGIN),
                        isHls = server.isHls
                    )
                } else {
                    val initJson = app.post(
                        "$EMBED_ORIGIN/embed/stream/${server.id}/init",
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Accept" to "application/json, */*",
                            "Referer" to embedUrl,
                            "Origin" to EMBED_ORIGIN
                        )
                    ).text
                    BRFlixVodParser.parseClickhostInit(initJson, EMBED_ORIGIN)
                } ?: return@amap

                // Use HEAD (0 bytes downloaded!) so multi-GB MP4/MKV streams (UniTV, Manual) never time out or OOM
                val head = app.head(
                    initResult.url,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to embedUrl,
                        "Origin" to EMBED_ORIGIN
                    ),
                    timeout = 12L
                )

                val contentType = head.headers["Content-Type"].orEmpty()
                val contentLength = head.headers["Content-Length"]?.trim()?.toLongOrNull()
                val verdict = BRFlixVodParser.classifyStreamHead(
                    statusCode = head.code,
                    contentType = contentType,
                    contentLength = contentLength,
                    initIsHls = initResult.isHls,
                    url = initResult.url
                )
                if (verdict == BRFlixVodParser.ProbeVerdict.REJECT) return@amap

                val isHls = verdict == BRFlixVodParser.ProbeVerdict.HLS

                callback(
                    newExtractorLink(
                        source = name,
                        name = "BRFlix • ${server.label}",
                        url = initResult.url,
                        type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = embedUrl
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to embedUrl,
                            "Origin" to EMBED_ORIGIN
                        )
                    }
                )
                emitted = true
            } catch (_: Exception) {
                // Skip failing mirror
            }
        }

        return emitted
    }
}
