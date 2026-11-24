package com.kairou

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLEncoder

@CloudstreamPlugin
class KairouPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Kairou())
    }
}

class Kairou : MainAPI() {
    override var mainUrl = "https://kairou.space"
    override var name = "Kairou"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
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

    override suspend fun load(url: String): LoadResponse {
        val clean = url.substringBefore("?").trimEnd('/')
        val id = clean.substringAfterLast("/").toIntOrNull()
            ?: throw ErrorLoadingException("ID TMDB inválido: $url")
        val isMovie = clean.contains("/movie/")

        if (isMovie) {
            val detailJson = app.get("$mainUrl/api/tmdb/movie/$id?language=pt-BR", headers = defaultHeaders()).text
            val obj = JSONObject(detailJson)
            val title = obj.optString("title").ifBlank { obj.optString("original_title") }.trim()
            val poster = KairouParser.tmdbImage(obj.optString("poster_path"), "w500")
            val backdrop = KairouParser.tmdbImage(obj.optString("backdrop_path"), "w1280")
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

        val detailJson = app.get("$mainUrl/api/tmdb/tv/$id?language=pt-BR", headers = defaultHeaders()).text
        val obj = JSONObject(detailJson)
        val title = obj.optString("name").ifBlank { obj.optString("original_name") }.trim()
        val poster = KairouParser.tmdbImage(obj.optString("poster_path"), "w500")
        val backdrop = KairouParser.tmdbImage(obj.optString("backdrop_path"), "w1280")
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

        val extractUrl = if (type == "movie") {
            "$mainUrl/api/extract?tmdbId=$id&type=movie"
        } else {
            val s = parts.getOrNull(2) ?: "1"
            val e = parts.getOrNull(3) ?: "1"
            "$mainUrl/api/extract?tmdbId=$id&type=tv&season=$s&episode=$e"
        }

        val jsonText = app.get(extractUrl, headers = defaultHeaders(), timeout = 30L).text
        val extracted = KairouParser.parseExtractResponse(jsonText, mainUrl)

        for (sub in extracted.subtitles) {
            subtitleCallback(newSubtitleFile(sub.label, sub.url))
        }

        var emitted = false
        for (src in extracted.sources) {
            callback(
                newExtractorLink(
                    source = name,
                    name = src.label,
                    url = src.url,
                    type = if (src.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = src.height
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                }
            )
            emitted = true
        }

        return emitted
    }
}
