package com.animesfhd

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

@CloudstreamPlugin
class AnimesFHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimesFHD())
    }
}

class AnimesFHD : MainAPI() {
    override var mainUrl = "https://animesfhd.net"
    override var name = "AnimesFHD"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val usesWebView = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val DEFAULT_HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept" to "application/json, text/plain, */*"
        )
    }

    override val mainPage = mainPageOf(
        "home:recent" to "Novos Episódios",
        "home:dubbed" to "Novos Dublados",
        "home:top" to "Em Alta",
        "home:liked" to "Mais Curtidos",
        "$mainUrl/api/series?audio=dublado" to "Animes Dublados",
        "$mainUrl/api/series?audio=legendado" to "Animes Legendados",
        "$mainUrl/api/series?sort=popular" to "Populares",
        "$mainUrl/api/series" to "Catálogo Completo"
    )

    private fun optimizeTmdbImage(url: String?, size: String = "w500"): String? {
        val clean = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val full = if (clean.startsWith("http")) clean else fixUrl(clean)
        return full.replace("/t/p/original/", "/t/p/$size/")
    }

    private fun parseAudios(obj: JSONObject): Pair<Boolean, Boolean> {
        var hasDub = false
        var hasSub = false
        val audiosArr = obj.optJSONArray("audios")
        if (audiosArr != null) {
            for (i in 0 until audiosArr.length()) {
                when (audiosArr.optString(i).lowercase()) {
                    "dublado" -> hasDub = true
                    "legendado" -> hasSub = true
                }
            }
        }
        val category = obj.optString("category").lowercase()
        val audio = obj.optString("audio").lowercase()
        if (category == "dublado" || audio == "dublado") hasDub = true
        if (category == "legendado" || audio == "legendado") hasSub = true
        if (!hasDub && !hasSub) hasSub = true
        return hasDub to hasSub
    }

    private fun seriesObjToSearchResponse(obj: JSONObject): AnimeSearchResponse? {
        val seriesId = obj.optLong("series_id", 0L).takeIf { it > 0L }
            ?: obj.optLong("id", 0L).takeIf { it > 0L }
            ?: return null
        val title = obj.optString("series_title").takeIf { it.isNotBlank() }
            ?: obj.optString("title").takeIf { it.isNotBlank() }
            ?: return null
        val poster = optimizeTmdbImage(obj.optString("cover_image"), "w500")
        val category = obj.optString("category").lowercase()
        val tvType = if (category == "filme") TvType.AnimeMovie else TvType.Anime
        val (hasDub, hasSub) = parseAudios(obj)
        val epNum = obj.optInt("episode_number", 0).takeIf { it > 0 }

        return newAnimeSearchResponse(title, "$mainUrl/serie/$seriesId", tvType) {
            this.posterUrl = poster
            if (epNum != null) {
                if (hasDub) addDubStatus(DubStatus.Dubbed, epNum)
                if (hasSub) addDubStatus(DubStatus.Subbed, epNum)
            } else {
                addDubStatus(hasDub, hasSub)
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data.startsWith("home:")) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val json = JSONObject(
                app.get("$mainUrl/api/home", headers = DEFAULT_HEADERS, referer = "$mainUrl/").text
            )
            val arr: JSONArray? = when (request.data) {
                "home:recent" -> json.optJSONArray("recent")
                "home:dubbed" -> json.optJSONArray("dubbed")
                "home:top" -> json.optJSONObject("top")?.optJSONArray("weekly")
                    ?: json.optJSONArray("releasing")
                "home:liked" -> json.optJSONArray("liked")
                else -> null
            }
            val items = mutableListOf<SearchResponse>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    arr.optJSONObject(i)?.let { seriesObjToSearchResponse(it) }?.let { items.add(it) }
                }
            }
            return newHomePageResponse(request.name, items.distinctBy { it.url }, false)
        }

        val sep = if (request.data.contains("?")) "&" else "?"
        val url = "${request.data}${sep}page=$page"
        val json = JSONObject(
            app.get(url, headers = DEFAULT_HEADERS, referer = "$mainUrl/animes").text
        )
        val arr = json.optJSONArray("series") ?: JSONArray()
        val items = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { seriesObjToSearchResponse(it) }?.let { items.add(it) }
        }
        val totalPages = json.optInt("pages", page)
        return newHomePageResponse(request.name, items.distinctBy { it.url }, page < totalPages && items.isNotEmpty())
    }

    private fun stripDiacritics(input: String): String =
        java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .trim()

    override suspend fun search(query: String): List<SearchResponse> {
        val raw = query.trim()
        if (raw.length < 2) return emptyList()
        val variants = buildList {
            add(raw)
            val base = raw.substringBefore(":").substringBefore(" - ").trim()
            if (base.length >= 2 && base != raw) add(base)
            val asciiRaw = stripDiacritics(raw)
            if (asciiRaw.length >= 2 && asciiRaw !in this) add(asciiRaw)
            val asciiBase = stripDiacritics(base)
            if (asciiBase.length >= 2 && asciiBase !in this) add(asciiBase)
        }
        for (term in variants) {
            val encoded = URLEncoder.encode(term, "UTF-8")
            val text = app.get(
                "$mainUrl/api/series?q=$encoded",
                headers = DEFAULT_HEADERS,
                referer = "$mainUrl/animes"
            ).text
            val json = JSONObject(text)
            val arr = json.optJSONArray("series") ?: continue
            val items = mutableListOf<SearchResponse>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val resp = seriesObjToSearchResponse(obj) ?: continue
                items.add(resp)
            }
            val unique = items.distinctBy { it.url }
            if (unique.isNotEmpty()) return unique
        }
        return emptyList()
    }

    private suspend fun fetchAllEpisodesForSeries(
        seriesId: Long,
        seasonNumber: Int?,
        fallbackPoster: String?,
        isMovie: Boolean,
        preloadedFirstPage: JSONObject? = null
    ): List<Episode> {
        val firstJson = preloadedFirstPage ?: try {
            JSONObject(
                app.get(
                    "$mainUrl/api/series/$seriesId?page=1",
                    headers = DEFAULT_HEADERS,
                    referer = "$mainUrl/serie/$seriesId"
                ).text
            )
        } catch (_: Exception) {
            return emptyList()
        }

        val results = mutableListOf<Episode>()

        fun parseEps(json: JSONObject): List<Episode> {
            val arr = json.optJSONArray("episodes") ?: return emptyList()
            val list = mutableListOf<Episode>()
            for (i in 0 until arr.length()) {
                val ep = arr.optJSONObject(i) ?: continue
                val epId = ep.optLong("id", 0L).takeIf { it > 0L } ?: continue
                val epNum = ep.optInt("episode_number", i + 1).takeIf { it > 0 } ?: (i + 1)
                val rawTitle = ep.optString("title").trim()
                val epName = when {
                    isMovie && rawTitle.isBlank() -> "Filme"
                    rawTitle.isNotBlank() && !rawTitle.equals("Episódio $epNum", ignoreCase = true) -> rawTitle
                    else -> "Episódio $epNum"
                }
                val rawDesc = ep.optString("description").trim()
                val epDesc = rawDesc.takeUnless {
                    it.isBlank() || it.equals("Sem sinopse até o momento", ignoreCase = true)
                }
                val epThumb = optimizeTmdbImage(ep.optString("thumbnail_url"), "w500") ?: fallbackPoster

                list.add(
                    newEpisode("$mainUrl/api/episodes/$epId|epId=$epId") {
                        this.name = epName
                        this.season = if (!isMovie) seasonNumber else null
                        this.episode = epNum
                        this.posterUrl = epThumb
                        this.description = epDesc
                    }
                )
            }
            return list
        }

        results.addAll(parseEps(firstJson))
        val totalPages = firstJson.optInt("pages", 1).coerceAtMost(20)
        if (totalPages > 1) {
            val extraPages = (2..totalPages).toList().chunked(4).flatMap { batch ->
                batch.amap { p ->
                    try {
                        val pJson = JSONObject(
                            app.get(
                                "$mainUrl/api/series/$seriesId?page=$p",
                                headers = DEFAULT_HEADERS,
                                referer = "$mainUrl/serie/$seriesId"
                            ).text
                        )
                        parseEps(pJson)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }.flatten()
            }
            results.addAll(extraPages)
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val seriesId = Regex("""/serie/(\d+)""").find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw ErrorLoadingException("ID da série inválido: $url")

        val json = JSONObject(
            app.get(
                "$mainUrl/api/series/$seriesId?page=1",
                headers = DEFAULT_HEADERS,
                referer = "$mainUrl/serie/$seriesId"
            ).text
        )
        val series = json.optJSONObject("series") ?: throw ErrorLoadingException("Série não encontrada")
        val title = series.optString("title").ifBlank { "Sem título" }
        val synopsis = series.optString("description").trim().takeIf { it.isNotBlank() }
        val poster = optimizeTmdbImage(series.optString("cover_image"), "w500")
        val backdrop = optimizeTmdbImage(series.optString("backdrop_image"), "w1280")
        val year = series.optInt("year", 0).takeIf { it > 1900 }
        val rating = series.optDouble("tmdb_rating", 0.0).takeIf { it > 0.0 }
        val genres = series.optString("genre")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val statusStr = series.optString("status").lowercase()
        val showStatus = if (statusStr == "ongoing" || statusStr == "airing") {
            ShowStatus.Ongoing
        } else {
            ShowStatus.Completed
        }
        val category = series.optString("category").lowercase()
        val isMovie = category == "filme"
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        val dubEpisodes = mutableListOf<Episode>()
        val subEpisodes = mutableListOf<Episode>()

        val versionsArr = json.optJSONArray("versions")
        if (versionsArr != null && versionsArr.length() > 0) {
            data class VersionSpec(val id: Long, val season: Int?, val audio: String)

            val specs = mutableListOf<VersionSpec>()
            val seen = mutableSetOf<Long>()
            for (i in 0 until versionsArr.length()) {
                val v = versionsArr.optJSONObject(i) ?: continue
                val vid = v.optLong("series_id", 0L).takeIf { it > 0L } ?: continue
                if (!seen.add(vid)) continue
                val seasonNum = v.optInt("season", 1).takeIf { it > 0 } ?: 1
                val audio = v.optString("audio").lowercase()
                specs.add(VersionSpec(vid, seasonNum, audio))
            }

            val loadedByVersion = specs.chunked(4).flatMap { batch ->
                batch.amap { spec ->
                    val preloaded = if (spec.id == seriesId) json else null
                    val eps = fetchAllEpisodesForSeries(
                        seriesId = spec.id,
                        seasonNumber = spec.season,
                        fallbackPoster = poster,
                        isMovie = isMovie,
                        preloadedFirstPage = preloaded
                    )
                    spec.audio to eps
                }
            }

            loadedByVersion.forEach { (audio, eps) ->
                if (audio == "dublado") dubEpisodes.addAll(eps) else subEpisodes.addAll(eps)
            }
        } else {
            val eps = fetchAllEpisodesForSeries(
                seriesId = seriesId,
                seasonNumber = if (isMovie) null else 1,
                fallbackPoster = poster,
                isMovie = isMovie,
                preloadedFirstPage = json
            )
            if (category == "dublado") dubEpisodes.addAll(eps) else subEpisodes.addAll(eps)
        }

        val sortedDub = dubEpisodes.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))
        val sortedSub = subEpisodes.distinctBy { it.data }.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 0 }))

        val recs = mutableListOf<SearchResponse>()
        val relatedArr = json.optJSONArray("related")
        if (relatedArr != null) {
            for (i in 0 until relatedArr.length()) {
                relatedArr.optJSONObject(i)?.let { seriesObjToSearchResponse(it) }?.let { recs.add(it) }
            }
        }

        return newAnimeLoadResponse(title, "$mainUrl/serie/$seriesId", tvType) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.plot = synopsis
            this.tags = genres
            this.showStatus = showStatus
            this.recommendations = recs.distinctBy { it.url }
            rating?.let { this.score = Score.from10(it) }
            if (sortedDub.isNotEmpty()) addEpisodes(DubStatus.Dubbed, sortedDub)
            if (sortedSub.isNotEmpty()) addEpisodes(DubStatus.Subbed, sortedSub)
        }
    }

    private fun serverPriority(kind: String): Int = when (kind.lowercase()) {
        "s4" -> 100  // TopAnimes HLS (~2.56 Mbps H.264)
        "s13" -> 95  // Homura MP4 (~2.06 Mbps H.264)
        "s1" -> 90   // AnimeFire HLS (1080p AV1 + 720p H.264)
        "s5" -> 80   // Dattebayo MP4
        "s6" -> 70   // HLS próprio
        "s3" -> 60   // AniTube
        "s8" -> 55   // AnimeQ
        "s14" -> 50  // Otakulogia
        else -> 40
    }

    private fun serverLabel(kind: String, fallbackLabel: String, audio: String): String {
        val audioTag = when (audio.lowercase()) {
            "dublado" -> "Dublado"
            "legendado" -> "Legendado"
            else -> ""
        }
        val srv = when (kind.lowercase()) {
            "s4" -> "Servidor FHD (HLS)"
            "s13" -> "Servidor Homura (MP4)"
            "s1" -> "Servidor Auto (AV1/H.264)"
            "s5" -> "Servidor R2 (MP4)"
            "s6" -> "Servidor Principal (HLS)"
            "s3" -> "Servidor AniTube"
            "s8" -> "Servidor AnimeQ"
            "s14" -> "Servidor Otakulogia"
            else -> fallbackLabel.ifBlank { "Servidor $kind" }
        }
        return if (audioTag.isNotBlank()) "$srv • $audioTag" else srv
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epId = Regex("""epId=(\d+)""").find(data)?.groupValues?.get(1)
            ?: Regex("""/episodes/(\d+)""").find(data)?.groupValues?.get(1)
            ?: return false

        val epReferer = "$mainUrl/episod/$epId"
        val json = try {
            JSONObject(
                app.get(
                    "$mainUrl/api/episodes/$epId",
                    headers = DEFAULT_HEADERS,
                    referer = epReferer
                ).text
            )
        } catch (_: Exception) {
            return false
        }

        val epObj = json.optJSONObject("episode")
        val subUrl = epObj?.optString("subtitle_url")?.trim()
        if (!subUrl.isNullOrBlank()) {
            subtitleCallback.invoke(
                newSubtitleFile("Português", fixUrl(subUrl))
            )
        }

        val sourcesArr = json.optJSONArray("sources") ?: return false
        data class RawSource(
            val label: String,
            val url: String,
            val kind: String,
            val audio: String,
            val hasAss: Boolean
        )

        val sources = mutableListOf<RawSource>()
        for (i in 0 until sourcesArr.length()) {
            val s = sourcesArr.optJSONObject(i) ?: continue
            val rawUrl = s.optString("url").trim()
            if (rawUrl.isBlank()) continue
            val kind = s.optString("media_kind").trim()
            // s2/s11 require client-side Emby/Jellyfin session tokens; skip if client=1
            if (rawUrl.contains("client=1")) continue
            sources.add(
                RawSource(
                    label = s.optString("label").trim(),
                    url = if (rawUrl.startsWith("http")) rawUrl else "$mainUrl$rawUrl",
                    kind = kind,
                    audio = s.optString("audio").trim(),
                    hasAss = s.optBoolean("has_ass_subtitles", false)
                )
            )
        }

        if (sources.any { it.hasAss } && subUrl.isNullOrBlank()) {
            subtitleCallback.invoke(
                newSubtitleFile("Português (ASS)", "$mainUrl/api/subtitle?episode_id=$epId")
            )
        }

        val ordered = sources.sortedByDescending { serverPriority(it.kind) }
        var found = false

        ordered.forEach { src ->
            val isHls = src.url.contains(".m3u8", ignoreCase = true) ||
                src.kind.lowercase() in setOf("s1", "s4", "s6")
            val linkType = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            val linkQuality = when (src.kind.lowercase()) {
                "s4", "s13", "s1", "s5" -> Qualities.P1080.value
                else -> Qualities.P720.value
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = serverLabel(src.kind, src.label, src.audio),
                    url = src.url,
                    type = linkType
                ) {
                    this.quality = linkQuality
                    this.referer = epReferer
                    this.headers = mapOf(
                        "User-Agent" to UA,
                        "Referer" to epReferer,
                        "Origin" to mainUrl
                    )
                }
            )
            found = true
        }

        return found
    }
}
