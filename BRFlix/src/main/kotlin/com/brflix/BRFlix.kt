package com.brflix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

@CloudstreamPlugin
class BRFlixPlugin : Plugin() {
    private var browserStreams: BRFlixBrowserStream? = null
    override fun load(context: Context) {
        val streams = BRFlixBrowserStream()
        browserStreams = streams
        registerMainAPI(BRFlix(streams))
    }
    override fun beforeUnload() { browserStreams?.close(); browserStreams = null }
}

class BRFlix internal constructor(private val browserStreams: BRFlixBrowserStream) : MainAPI() {
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
        private const val TMDB_FALLBACK_ORIGIN = "https://kairou.space/api/tmdb"
        private const val FENIXFLIX_ORIGIN = "https://fenixflix.fenixhub.online"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "trending/movie/week?language=pt-BR&page=%d|movie" to "Filmes em Alta",
        "trending/tv/week?language=pt-BR&page=%d|tv" to "Séries em Alta",
        "movie/popular?language=pt-BR&page=%d|movie" to "Filmes Populares",
        "tv/popular?language=pt-BR&page=%d|tv" to "Séries Populares",
        "discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc&language=pt-BR&page=%d|tv" to "Animes",
        "discover/tv?with_genres=16&without_original_language=ja&sort_by=popularity.desc&language=pt-BR&page=%d|tv" to "Desenhos"
    )

    private fun defaultHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/html, */*",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to referer
    )

    /**
     * Fetches TMDB JSON from brflix.lat/tmdb-cached, transparently falling back to
     * kairou.space/api/tmdb when brflix.lat returns HTTP 451 (DMCA HTML) or non-JSON.
     */
    private suspend fun fetchTmdbJson(pathWithQuery: String): String {
        val cleanPath = pathWithQuery.trimStart('/')
        try {
            val primary = app.get("$mainUrl/tmdb-cached/$cleanPath", headers = defaultHeaders())
            if (primary.code in 200..299 && BRFlixVodParser.looksLikeJsonObject(primary.text)) {
                return primary.text
            }
        } catch (_: Exception) {
            // Fall through to mirror
        }

        val fallback = app.get(
            "$TMDB_FALLBACK_ORIGIN/$cleanPath",
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json, */*",
                "Referer" to "https://kairou.space/"
            )
        )
        if (fallback.code in 200..299 && BRFlixVodParser.looksLikeJsonObject(fallback.text)) {
            return fallback.text
        }
        throw ErrorLoadingException("Falha ao carregar metadados TMDB ($cleanPath)")
    }

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
        val path = template.format(page)

        val jsonText = fetchTmdbJson(path)
        val (items, hasNext) = BRFlixVodParser.parseTmdbList(jsonText, forcedType)
        return newHomePageResponse(request.name, items.map { it.toSearchResponse() }, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val jsonText = fetchTmdbJson("search/multi?query=$encoded&language=pt-BR&page=1")
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
            val detailJson = fetchTmdbJson("movie/$id?language=pt-BR")
            val obj = JSONObject(detailJson)
            val title = obj.optString("title").ifBlank { obj.optString("original_title") }.trim()
            val imdbId = obj.optString("imdb_id").trim()
            val poster = BRFlixVodParser.tmdbImage(obj.optString("poster_path"), "w500")
            val backdrop = BRFlixVodParser.tmdbImage(obj.optString("backdrop_path"), "w1280")
            val year = obj.optString("release_date").take(4).toIntOrNull()
            val runtime = obj.optInt("runtime", 0).takeIf { it > 0 }
            val plot = obj.optString("overview").trim().ifBlank { null }
            val tags = parseGenres(obj)

            return newMovieLoadResponse(title, clean, TvType.Movie, "movie|$id|$imdbId") {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop ?: poster
                this.year = year
                this.duration = runtime
                this.plot = plot
                this.tags = tags
            }
        }

        val detailJson = fetchTmdbJson("tv/$id?language=pt-BR&append_to_response=external_ids")
        val obj = JSONObject(detailJson)
        val title = obj.optString("name").ifBlank { obj.optString("original_name") }.trim()
        val imdbId = obj.optJSONObject("external_ids")?.optString("imdb_id")?.trim().orEmpty()
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
                    val sJson = fetchTmdbJson("tv/$id/season/$sNum?language=pt-BR")
                    BRFlixVodParser.parseSeasonEpisodes(sJson, sNum)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            for (epList in fetched) {
                for (ep in epList) {
                    allEpisodes.add(
                        newEpisode("tv|$id|${ep.season}|${ep.episode}|$imdbId") {
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

    private suspend fun resolveImdbId(type: String, tmdbId: String, existing: String): String {
        if (existing.startsWith("tt")) return existing
        return try {
            val path = if (type == "movie") "movie/$tmdbId?language=pt-BR" else "tv/$tmdbId/external_ids"
            val obj = JSONObject(fetchTmdbJson(path))
            obj.optString("imdb_id").trim()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Header-only reachability probe using Range: bytes=0-1 without downloading body bytes.
     * Works on hosts that reject HEAD (400/405) as well as hosts that ignore Range.
     */
    private suspend fun isExternalStreamReachable(url: String, headers: Map<String, String>): Boolean {
        return try {
            val probeHeaders = headers.toMutableMap()
            probeHeaders.putIfAbsent("User-Agent", USER_AGENT)
            probeHeaders["Range"] = "bytes=0-1"
            val resp = app.get(url, headers = probeHeaders, timeout = 8L)
            resp.okhttpResponse.use { ok ->
                if (ok.code !in 200..299) return false
                val ct = ok.header("Content-Type").orEmpty().lowercase()
                if (ct.contains("text/html") || ct.contains("text/plain") || ct.contains("application/json")) {
                    return false
                }
                if (ct.contains("matroska") || ct.contains("x-mkv")) {
                    return false
                }
                val cl = ok.header("Content-Length")?.trim()?.toLongOrNull()
                if (ct.contains("mpegurl") && cl != null && cl < 64L) {
                    return false
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val type = parts.getOrNull(0) ?: return false
        val idStr = parts.getOrNull(1) ?: return false
        val tmdbId = idStr.toIntOrNull() ?: return false
        val season = if (type == "tv") parts.getOrNull(2)?.toIntOrNull() ?: 1 else 0
        val episode = if (type == "tv") parts.getOrNull(3)?.toIntOrNull() ?: 1 else 0
        val rawImdb = if (type == "movie") parts.getOrNull(2).orEmpty() else parts.getOrNull(4).orEmpty()
        val imdbId = resolveImdbId(type, idStr, rawImdb)

        if (!isCasting) {
            val contentId = imdbId.takeIf { it.startsWith("tt") } ?: idStr
            listOf("SuperFlix" to "https://superflixapi.baby", "WarezCDN" to "https://warezcdn.sbs").forEach { (provider, origin) ->
                val path = if (type == "movie") "/filme/$contentId" else {
                    val seriesId = if (provider == "SuperFlix") idStr else contentId
                    "/serie/$seriesId/$season/$episode"
                }
                listOf(false, true).forEach { subbed ->
                    callback(newExtractorLink(name, "$provider • ${if (subbed) "Legendado" else "Dublado"}",
                        browserStreams.register(provider, "$origin$path", subbed), ExtractorLinkType.M3U8) {
                        quality = Qualities.Unknown.value
                    })
                }
            }
        }

        val emittedUrls = ConcurrentHashMap.newKeySet<String>()
        val emittedSubs = ConcurrentHashMap.newKeySet<String>()

        suspend fun emitSubtitle(sub: BrFlixSubtitle) {
            if (sub.url.isNotBlank() && emittedSubs.add(sub.url)) {
                subtitleCallback(newSubtitleFile(sub.label, sub.url))
            }
        }

        suspend fun emitStream(stream: BrFlixResolvedStream) {
            if (!emittedUrls.add(stream.url)) return
            val finalHeaders = stream.headers.toMutableMap()
            finalHeaders.putIfAbsent("User-Agent", USER_AGENT)
            val useRangeProxy = !isCasting && !stream.isHls && BRFlixVodParser.needsRangeProxy(stream.url)
            val playbackUrl = if (useRangeProxy) {
                browserStreams.registerDirect(stream.url, finalHeaders)
            } else {
                stream.url
            }
            callback(
                newExtractorLink(
                    source = name,
                    name = stream.label,
                    url = playbackUrl,
                    type = if (stream.isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = if (useRangeProxy) "" else stream.referer
                    this.quality = stream.quality
                    this.headers = if (useRangeProxy) emptyMap() else finalHeaders
                }
            )
        }

        val queryBase = if (type == "movie") {
            "mediaType=movie&tmdbId=$tmdbId" + if (imdbId.startsWith("tt")) "&imdbId=$imdbId" else ""
        } else {
            "mediaType=tv&tmdbId=$tmdbId&season=$season&episode=$episode" +
                if (imdbId.startsWith("tt")) "&imdbId=$imdbId" else ""
        }

        suspend fun fetchAndEmitBackendResolve(endpointPath: String) {
            try {
                val resp = app.get(
                    "$mainUrl$endpointPath",
                    headers = defaultHeaders() + ("X-Brflix-Enc" to "1"),
                    timeout = 10L
                )
                if (resp.code in 200..299 && BRFlixVodParser.looksLikeJsonObject(resp.text)) {
                    val parsed = BRFlixVodParser.parseZeusResponse(resp.text, mainUrl)
                    parsed.subtitles.forEach { emitSubtitle(it) }
                    parsed.streams.take(6).amap { st ->
                        if (isExternalStreamReachable(st.url, st.headers)) {
                            emitStream(st)
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore failing backend resolver
            }
        }

        // Run all BRFlix sources + subtitles in parallel (both Dublado and Legendado!)
        listOf("subtitles", "fenix", "zeus", "best_dub", "best_sub", "clickhost").amap { provider ->
            when (provider) {
                "subtitles" -> {
                    try {
                        val subResp = app.get(
                            "$mainUrl/api/subtitles/list?$queryBase",
                            headers = defaultHeaders(),
                            timeout = 8L
                        )
                        if (subResp.code in 200..299 && BRFlixVodParser.looksLikeJsonObject(subResp.text)) {
                            BRFlixVodParser.parseSubtitlesList(subResp.text, mainUrl).forEach { emitSubtitle(it) }
                        }
                    } catch (_: Exception) {
                        // Ignore subtitle lookup failure
                    }
                }

                "fenix" -> {
                    if (imdbId.startsWith("tt")) {
                        try {
                            val fenixUrl = if (type == "movie") {
                                "$FENIXFLIX_ORIGIN/stream/movie/$imdbId.json"
                            } else {
                                "$FENIXFLIX_ORIGIN/stream/series/$imdbId:$season:$episode.json"
                            }
                            val jsonText = app.get(
                                fenixUrl,
                                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"),
                                timeout = 10L
                            ).text
                            val candidates = BRFlixVodParser.parseFenixFlixStreams(jsonText)
                            candidates.amap { st ->
                                // FenixHub blocks requests with a Referer header (403 Origem bloqueada)
                                if (isExternalStreamReachable(st.url, emptyMap())) {
                                    emitStream(st)
                                }
                            }
                        } catch (_: Exception) {
                            // Ignore FenixFlix errors
                        }
                    }
                }

                "zeus" -> fetchAndEmitBackendResolve("/zeus-resolve?$queryBase")
                "best_dub" -> fetchAndEmitBackendResolve("/best-stream-resolve?$queryBase&hq=0&dubbed=1")
                "best_sub" -> fetchAndEmitBackendResolve("/best-stream-resolve?$queryBase&hq=0&dubbed=0")

                "clickhost" -> {
                    try {
                        val embedUrl = if (type == "movie") {
                            "$EMBED_ORIGIN/embed/filme/$tmdbId"
                        } else {
                            val (mappedS, mappedE) = BRFlixVodParser.mapClickhostTvEpisode(tmdbId, season, episode)
                            "$EMBED_ORIGIN/embed/serie/$tmdbId/$mappedS/$mappedE"
                        }

                        val embedHtml = app.get(
                            embedUrl,
                            headers = defaultHeaders(referer = "$mainUrl/"),
                            timeout = 10L
                        ).text
                        val servers = BRFlixVodParser.parseClickhostServers(embedHtml)

                        servers.amap { server ->
                            try {
                                if (!server.directUrl.isNullOrBlank()) {
                                    val directResolved = BRFlixVodParser.resolveUrl(server.directUrl, EMBED_ORIGIN)
                                    val isExternalHost = !directResolved.startsWith(EMBED_ORIGIN)
                                    val probeHeaders = if (isExternalHost) emptyMap() else mapOf("Referer" to embedUrl)
                                    if (isExternalStreamReachable(directResolved, probeHeaders)) {
                                        emitStream(
                                            BrFlixResolvedStream(
                                                label = "BRFlix • ${server.label}",
                                                url = directResolved,
                                                isHls = server.isHls || directResolved.contains(".m3u8", ignoreCase = true),
                                                quality = Qualities.P1080.value,
                                                referer = if (isExternalHost) "" else embedUrl,
                                                headers = if (isExternalHost) emptyMap() else mapOf(
                                                    "Referer" to embedUrl,
                                                    "Origin" to EMBED_ORIGIN
                                                )
                                            )
                                        )
                                    }
                                    return@amap
                                }

                                val initJson = app.post(
                                    "$EMBED_ORIGIN/embed/stream/${server.id}/init",
                                    headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Accept" to "application/json, */*",
                                        "Referer" to embedUrl,
                                        "Origin" to EMBED_ORIGIN
                                    ),
                                    timeout = 10L
                                ).text
                                val initResult = BRFlixVodParser.parseClickhostInit(initJson, EMBED_ORIGIN)
                                    ?: return@amap

                                // Use HEAD (0 bytes downloaded) so multi-GB MP4 streams (UniTV) never time out or OOM
                                val head = app.head(
                                    initResult.url,
                                    headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Referer" to embedUrl,
                                        "Origin" to EMBED_ORIGIN
                                    ),
                                    timeout = 10L
                                )
                                val verdict = BRFlixVodParser.classifyStreamHead(
                                    statusCode = head.code,
                                    contentType = head.headers["Content-Type"].orEmpty(),
                                    contentLength = head.headers["Content-Length"]?.trim()?.toLongOrNull(),
                                    initIsHls = initResult.isHls,
                                    url = initResult.url
                                )
                                if (verdict == BRFlixVodParser.ProbeVerdict.REJECT) return@amap

                                emitStream(
                                    BrFlixResolvedStream(
                                        label = "BRFlix • ${server.label}",
                                        url = initResult.url,
                                        isHls = verdict == BRFlixVodParser.ProbeVerdict.HLS,
                                        quality = Qualities.P1080.value,
                                        referer = embedUrl,
                                        headers = mapOf(
                                            "User-Agent" to USER_AGENT,
                                            "Referer" to embedUrl,
                                            "Origin" to EMBED_ORIGIN
                                        )
                                    )
                                )
                            } catch (_: Exception) {
                                // Skip failing mirror
                            }
                        }
                    } catch (_: Exception) {
                        // Ignore clickhost errors
                    }
                }
            }
        }

        return emittedUrls.isNotEmpty() || !isCasting
    }
}
