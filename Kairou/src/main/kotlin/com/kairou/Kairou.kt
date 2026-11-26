package com.kairou

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

@CloudstreamPlugin
class KairouPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Kairou())
    }
}

class Kairou : TmdbProvider() {
    override var mainUrl = "https://kairou.space"
    override var name = "Kairou"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val useMetaLoadResponse = false
    override val providerType = ProviderType.DirectProvider
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val probeClient = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    override val mainPage = mainPageOf(
        "$mainUrl/api/tmdb/trending/movie/week?language=pt-BR&page=%d|movie" to "Filmes em Alta",
        "$mainUrl/api/tmdb/trending/tv/week?language=pt-BR&page=%d|tv" to "Séries em Alta",
        "$mainUrl/api/tmdb/movie/popular?language=pt-BR&page=%d|movie" to "Filmes Populares",
        "$mainUrl/api/tmdb/tv/popular?language=pt-BR&page=%d|tv" to "Séries Populares",
        "$mainUrl/api/tmdb/discover/tv?with_genres=16&with_original_language=ja&sort_by=popularity.desc&language=pt-BR&page=%d|tv" to "Animes",
        "$mainUrl/api/tmdb/discover/tv?with_genres=16&without_original_language=ja&sort_by=popularity.desc&language=pt-BR&page=%d|tv" to "Desenhos e Animações"
    )

    private fun defaultHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to referer
    )

    private fun KairouItem.toSearchResponse(): SearchResponse {
        val itemUrl = "$mainUrl/$mediaType/$id"
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
        val (items, hasNext) = KairouParser.parseTmdbList(jsonText, forcedType)
        return newHomePageResponse(request.name, items.map { it.toSearchResponse() }, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/api/tmdb/search/multi?query=$encoded&language=pt-BR&page=1"
        val jsonText = app.get(url, headers = defaultHeaders()).text
        val (items, _) = KairouParser.parseTmdbList(jsonText, null)
        return items.map { it.toSearchResponse() }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/api/tmdb/search/multi?query=$encoded&language=pt-BR&page=$page"
        val jsonText = app.get(url, headers = defaultHeaders()).text
        val (items, hasNext) = KairouParser.parseTmdbList(jsonText, null)
        return newSearchResponseList(items.map { it.toSearchResponse() }, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val trimmed = url.trim()
        val parsedTarget: Triple<Int, Boolean, String> = if (trimmed.startsWith("{")) {
            val json = try {
                JSONObject(trimmed)
            } catch (_: Exception) {
                throw ErrorLoadingException("Payload TMDB inválido: $url")
            }
            val tmdbId = json.optInt("id", 0).takeIf { it > 0 }
                ?: json.optInt("tmdbID", 0).takeIf { it > 0 }
                ?: throw ErrorLoadingException("ID TMDB inválido: $url")
            val rawType = json.optString("type").lowercase()
            val movie = rawType == "movie" || json.optBoolean("isMovie", false)
            val canonical = "$mainUrl/${if (movie) "movie" else "tv"}/$tmdbId"
            Triple(tmdbId, movie, canonical)
        } else {
            val clean = trimmed.substringBefore("?").trimEnd('/')
            val segment = clean.substringAfterLast("/")
            val tmdbId = segment.substringBefore("-").toIntOrNull()
                ?: throw ErrorLoadingException("ID TMDB inválido: $url")
            val movie = clean.contains("/movie/") || clean.contains("/filme/")
            Triple(tmdbId, movie, clean)
        }
        val (id, isMovie, clean) = parsedTarget

        if (isMovie) {
            val detailJson = app.get("$mainUrl/api/tmdb/movie/$id?language=pt-BR", headers = defaultHeaders()).text
            val obj = JSONObject(detailJson)
            val title = obj.optString("title").ifBlank { obj.optString("original_title") }.trim()
            val origTitle = obj.optString("original_title").ifBlank { title }.trim()
            val poster = KairouParser.tmdbImage(obj.optString("poster_path"), "w500")
            val backdrop = KairouParser.tmdbImage(obj.optString("backdrop_path"), "w1280")
            val year = obj.optString("release_date").take(4).toIntOrNull()
            val runtime = obj.optInt("runtime", 0).takeIf { it > 0 }
            val plot = obj.optString("overview").trim().ifBlank { null }
            val tags = parseGenres(obj)
            val imdbId = obj.optString("imdb_id").trim().takeIf { it.startsWith("tt") }.orEmpty()
            val safeTitle = URLEncoder.encode(origTitle, "UTF-8")

            return newMovieLoadResponse(title, clean, TvType.Movie, "$mainUrl/play|movie|$id|$imdbId|$safeTitle|${year ?: ""}") {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop ?: poster
                this.year = year
                this.duration = runtime
                this.plot = plot
                this.tags = tags
            }
        }

        val detailJson = app.get(
            "$mainUrl/api/tmdb/tv/$id?language=pt-BR&append_to_response=external_ids",
            headers = defaultHeaders()
        ).text
        val obj = JSONObject(detailJson)
        val title = obj.optString("name").ifBlank { obj.optString("original_name") }.trim()
        val origTitle = obj.optString("original_name").ifBlank { title }.trim()
        val poster = KairouParser.tmdbImage(obj.optString("poster_path"), "w500")
        val backdrop = KairouParser.tmdbImage(obj.optString("backdrop_path"), "w1280")
        val year = obj.optString("first_air_date").take(4).toIntOrNull()
        val plot = obj.optString("overview").trim().ifBlank { null }
        val tags = parseGenres(obj)
        val imdbId = obj.optJSONObject("external_ids")?.optString("imdb_id")
            ?.trim()?.takeIf { it.startsWith("tt") }.orEmpty()
        val origLang = obj.optString("original_language").lowercase()
        val hasAnimationGenre = (obj.optJSONArray("genres")?.let { gArr ->
            (0 until gArr.length()).any { gArr.optJSONObject(it)?.optInt("id") == 16 }
        } == true)
        val isAnime = hasAnimationGenre && origLang == "ja"
        val kindFlag = if (isAnime) "anime" else "tv"
        val safeTitle = URLEncoder.encode(origTitle, "UTF-8")

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
                        "$mainUrl/api/tmdb/tv/$id/season/$sNum?language=pt-BR",
                        headers = defaultHeaders()
                    ).text
                    KairouParser.parseSeasonEpisodes(sJson, sNum)
                } catch (_: Exception) {
                    emptyList()
                }
            }
            for (epList in fetched) {
                for (ep in epList) {
                    allEpisodes.add(
                        newEpisode("$mainUrl/play|tv|$id|${ep.season}|${ep.episode}|$imdbId|$kindFlag|$safeTitle|${year ?: ""}") {
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

        val tvType = if (isAnime) TvType.Anime else TvType.TvSeries
        return newTvSeriesLoadResponse(title, clean, tvType, allEpisodes) {
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
        val rawParts = data.split("|")
        // Handle both "$mainUrl/play|movie|..." and legacy "movie|..." (including fixUrl-prefixed ".../movie|...")
        val parts = if (rawParts.firstOrNull()?.contains("/play") == true) {
            rawParts.drop(1)
        } else {
            rawParts
        }
        val type = parts.getOrNull(0)?.substringAfterLast("/") ?: return false
        val id = parts.getOrNull(1) ?: return false

        val extractUrls = mutableListOf<String>()
        val imdbId: String
        val subEndpoint: String?

        if (type == "movie") {
            imdbId = parts.getOrNull(2).orEmpty().trim()
            val encodedTitle = parts.getOrNull(3).orEmpty().trim()
            val year = parts.getOrNull(4).orEmpty().trim()
            val qs = buildString {
                append("$mainUrl/api/extract?tmdbId=$id&type=movie")
                if (imdbId.startsWith("tt")) append("&imdbId=$imdbId")
                if (encodedTitle.isNotBlank()) append("&title=$encodedTitle")
                if (year.isNotBlank()) append("&year=$year")
            }
            extractUrls.add(qs)
            subEndpoint = imdbId.takeIf { it.startsWith("tt") }
                ?.let { "https://opensubtitles-v3.strem.io/subtitles/movie/$it.json" }
        } else {
            val s = parts.getOrNull(2) ?: "1"
            val e = parts.getOrNull(3) ?: "1"
            imdbId = parts.getOrNull(4).orEmpty().trim()
            val isAnime = parts.getOrNull(5) == "anime"
            val encodedTitle = parts.getOrNull(6).orEmpty().trim()
            val year = parts.getOrNull(7).orEmpty().trim()
            val baseExtract = buildString {
                append("$mainUrl/api/extract?tmdbId=$id&type=tv&season=$s&episode=$e")
                if (imdbId.startsWith("tt")) append("&imdbId=$imdbId")
                if (encodedTitle.isNotBlank()) append("&title=$encodedTitle")
                if (year.isNotBlank()) append("&year=$year")
            }
            extractUrls.add(baseExtract)
            if (isAnime) {
                // Unlocks both Aurora PT-BR Dub and Aurora PT-BR Leg on animes
                extractUrls.add("$baseExtract&animeLang=sub")
            }
            subEndpoint = imdbId.takeIf { it.startsWith("tt") }
                ?.let { "https://opensubtitles-v3.strem.io/subtitles/series/$it:$s:$e.json" }
        }

        val results = extractUrls.amap { url ->
            try {
                val jsonText = app.get(url, headers = defaultHeaders(), timeout = 30L).text
                KairouParser.parseExtractResponse(jsonText, mainUrl)
            } catch (_: Exception) {
                KairouExtractResult(emptyList(), emptyList())
            }
        }
        val extracted = KairouParser.mergeExtractResults(results)

        val seenSubUrls = mutableSetOf<String>()
        for (sub in extracted.subtitles) {
            if (seenSubUrls.add(sub.url)) {
                subtitleCallback(newSubtitleFile(sub.label, sub.url))
            }
        }

        if (subEndpoint != null) {
            try {
                val subJson = app.get(subEndpoint, headers = defaultHeaders(), timeout = 10L).text
                val externalSubs = KairouParser.parseOpenSubtitlesJson(subJson)
                for (sub in externalSubs) {
                    if (seenSubUrls.add(sub.url)) {
                        subtitleCallback(newSubtitleFile(sub.label, sub.url))
                    }
                }
            } catch (_: Exception) {
                // External subtitles are best-effort
            }
        }

        // Filter out Vortex/atlantic.bz streams (server-side proxy IP triggers a fake
        // "atlantic.st" anti-VPN slate video) and pre-flight probe remaining sources in
        // parallel so expired tokens (410 Gone on Coruja), rate-limited proxies (429/404
        // on Aether/Nautilus), and HTML error pages never reach ExoPlayer.
        val candidates = extracted.sources.filterNot { src ->
            val prov = src.provider.lowercase()
            prov == "vortex" ||
                src.referer?.contains("atlantic.", ignoreCase = true) == true ||
                src.url.contains("atlantic.", ignoreCase = true)
        }
        val probed = candidates.amap { src ->
            val linkReferer = src.referer ?: "$mainUrl/"
            val ok = probeSourceAlive(src, linkReferer)
            src to ok
        }
        val alive = probed.filter { it.second }.map { it.first }
        // Sort PT-BR Dub first, then PT-BR Leg, then Original.
        val ordered = alive.sortedBy { src ->
            val lbl = src.label.lowercase()
            val prov = src.provider.lowercase()
            when {
                lbl.contains("dub") || (lbl.contains("pt-br") && !lbl.contains("leg")) -> 10
                lbl.contains("leg") && !lbl.contains("original") -> 20
                prov == "aether" || prov == "nautilus" -> 80
                else -> 40
            }
        }

        var emitted = false
        for (src in ordered) {
            val linkType = when {
                src.isDash -> ExtractorLinkType.DASH
                src.isM3u8 -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }
            val linkReferer = src.referer ?: "$mainUrl/"
            val linkHeaders = buildMap {
                put("User-Agent", USER_AGENT)
                put("Referer", linkReferer)
                val origin = runCatching {
                    val u = java.net.URI(linkReferer)
                    "${u.scheme}://${u.authority}"
                }.getOrNull()
                if (!origin.isNullOrBlank() && !linkReferer.startsWith(mainUrl)) {
                    put("Origin", origin)
                }
            }
            callback(
                newExtractorLink(
                    source = name,
                    name = src.label,
                    url = src.url,
                    type = linkType
                ) {
                    this.referer = linkReferer
                    this.quality = src.height
                    this.headers = linkHeaders
                }
            )
            emitted = true
        }

        return emitted
    }

    private fun probeSourceAlive(src: KairouSource, referer: String): Boolean {
        return try {
            val reqBuilder = Request.Builder()
                .url(src.url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
            if (!src.isM3u8 && !src.isDash) {
                reqBuilder.header("Range", "bytes=0-1023")
            }
            val origin = runCatching {
                val u = java.net.URI(referer)
                "${u.scheme}://${u.authority}"
            }.getOrNull()
            if (!origin.isNullOrBlank() && !referer.startsWith(mainUrl)) {
                reqBuilder.header("Origin", origin)
            }
            probeClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (resp.code !in 200..299) return false
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (ct.contains("text/html") || ct.contains("application/json")) return false
                if (src.isM3u8 || src.isDash) {
                    val buf = ByteArray(4096)
                    val read = resp.body?.byteStream()?.read(buf) ?: 0
                    if (read <= 0) return false
                    val head = String(buf, 0, read, Charsets.UTF_8)
                    val trimmedHead = head.trimStart()
                    if (trimmedHead.startsWith("<!") || trimmedHead.startsWith("<html", ignoreCase = true)) return false
                    if (src.isM3u8 && !head.contains("#EXTM3U")) return false
                    if (head.contains("atlantic.st", ignoreCase = true) || head.contains("classroom.best", ignoreCase = true)) return false
                }
                true
            }
        } catch (_: Exception) {
            false
        }
    }
}
