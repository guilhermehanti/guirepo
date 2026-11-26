package com.animefire

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

@CloudstreamPlugin
class AnimeFirePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeFire())
    }
}

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.one"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val usesWebView = false
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie
    )

    companion object {
        private const val API_URL = "https://api.animefire.one"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val API_HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept" to "application/json, text/plain, */*",
            "Origin" to "https://animefire.one",
            "Referer" to "https://animefire.one/"
        )
    }

    override val mainPage = mainPageOf(
        "home:new-episodes" to "Novos Episódios",
        "home:new-animes-dubbed" to "Novos Dublados",
        "lancamentos" to "Lançamentos",
        "home:top-10" to "Top 10 da Semana",
        "home:most-liked" to "Mais Curtidos",
        "$API_URL/animes?audio=dublado" to "Animes Dublados",
        "$API_URL/animes/filmes" to "Filmes",
        "$API_URL/animes" to "Catálogo Completo"
    )

    private fun optimizeTmdbImage(url: String?, size: String = "w500"): String? {
        val clean = url?.trim()?.takeIf { it.isNotBlank() && it != "null" } ?: return null
        val full = if (clean.startsWith("http")) clean else fixUrl(clean)
        return full.replace("/t/p/original/", "/t/p/$size/")
    }

    private fun extractTitle(titlesObj: JSONObject?): String {
        if (titlesObj == null) return "Sem título"
        for (key in listOf("BR", "US", "JP")) {
            val v = titlesObj.optString(key).trim()
            if (v.isNotBlank() && v != "null") return v
        }
        val keys = titlesObj.keys()
        while (keys.hasNext()) {
            val v = titlesObj.optString(keys.next()).trim()
            if (v.isNotBlank() && v != "null") return v
        }
        return "Sem título"
    }

    private fun parseAudioFlags(audioStr: String?): Pair<Boolean, Boolean> {
        val lower = audioStr?.lowercase() ?: ""
        val hasDub = lower.contains("dublado")
        val hasSub = lower.contains("legendado")
        return if (!hasDub && !hasSub) (false to true) else (hasDub to hasSub)
    }

    private fun itemToSearchResponse(
        obj: JSONObject,
        isEpisodeCard: Boolean = false,
        isMovieSection: Boolean = false
    ): AnimeSearchResponse? {
        val id = obj.optString("id").trim().takeIf { it.isNotBlank() && it != "null" } ?: return null
        val title = extractTitle(obj.optJSONObject("titles"))
        val poster = optimizeTmdbImage(obj.optString("poster_src"), "w500")
        val (hasDub, hasSub) = parseAudioFlags(obj.optString("audio"))
        val itemUrl = if (isEpisodeCard) "$mainUrl/episode/$id" else "$mainUrl/anime/$id"
        val tvType = if (isMovieSection) TvType.AnimeMovie else TvType.Anime
        val epNum = if (isEpisodeCard) obj.optInt("number", 0).takeIf { it > 0 } else null

        return newAnimeSearchResponse(title, itemUrl, tvType) {
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
            val carouselKey = request.data.removePrefix("home:")
            val json = JSONObject(app.get("$API_URL/home", headers = API_HEADERS).text)
            val carousels = json.optJSONObject("data")?.optJSONArray("carousels") ?: JSONArray()
            val items = mutableListOf<SearchResponse>()
            val isEpisodeCarousel = carouselKey == "new-episodes"
            for (i in 0 until carousels.length()) {
                val c = carousels.optJSONObject(i) ?: continue
                if (c.optString("key") == carouselKey) {
                    val arr = c.optJSONArray("items") ?: break
                    for (j in 0 until arr.length()) {
                        arr.optJSONObject(j)?.let { itemToSearchResponse(it, isEpisodeCard = isEpisodeCarousel) }
                            ?.let { items.add(it) }
                    }
                    break
                }
            }
            return newHomePageResponse(request.name, items.distinctBy { it.url }, false)
        }

        if (request.data == "lancamentos") {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val json = JSONObject(app.get("$API_URL/animes/lancamentos", headers = API_HEADERS).text)
            val arr = json.optJSONObject("data")?.optJSONArray("animes") ?: JSONArray()
            val items = mutableListOf<SearchResponse>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { itemToSearchResponse(it) }?.let { items.add(it) }
            }
            return newHomePageResponse(request.name, items.distinctBy { it.url }, false)
        }

        val sep = if (request.data.contains("?")) "&" else "?"
        val url = "${request.data}${sep}page=$page"
        val json = JSONObject(app.get(url, headers = API_HEADERS).text)
        val arr = json.optJSONArray("data") ?: JSONArray()
        val isMovieSection = request.data.contains("/filmes")
        val items = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            arr.optJSONObject(i)?.let { itemToSearchResponse(it, isMovieSection = isMovieSection) }
                ?.let { items.add(it) }
        }
        val lastPage = json.optJSONObject("meta")?.optInt("last_page", page) ?: page
        return newHomePageResponse(request.name, items.distinctBy { it.url }, page < lastPage && items.isNotEmpty())
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
            val json = JSONObject(
                app.get("$API_URL/animes/pesquisar?q=$encoded", headers = API_HEADERS).text
            )
            val arr = json.optJSONArray("data") ?: continue
            val items = mutableListOf<SearchResponse>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val resp = itemToSearchResponse(obj) ?: continue
                items.add(resp)
            }
            val unique = items.distinctBy { it.url }
            if (unique.isNotEmpty()) return unique
        }
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val animeId = if (url.contains("/episode/")) {
            val epId = url.substringAfter("/episode/").substringBefore("?").substringBefore("/")
            val epJson = JSONObject(app.get("$API_URL/episode/$epId", headers = API_HEADERS).text)
            epJson.optJSONObject("data")?.optJSONObject("anime")?.optString("id")
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?: throw ErrorLoadingException("Anime não encontrado para o episódio $epId")
        } else {
            url.substringAfter("/anime/").substringBefore("?").substringBefore("/")
        }

        val json = JSONObject(app.get("$API_URL/anime/$animeId", headers = API_HEADERS).text)
        val dataObj = json.optJSONObject("data") ?: throw ErrorLoadingException("Anime não encontrado")
        val hero = dataObj.optJSONObject("hero") ?: throw ErrorLoadingException("Dados do anime ausentes")

        val format = dataObj.optString("format").lowercase()
        val isMovie = format == "movie"
        val tvType = if (isMovie) TvType.AnimeMovie else TvType.Anime

        val title = extractTitle(hero.optJSONObject("titles"))
        val synopsis = hero.optString("synopsis").trim().takeIf { it.isNotBlank() && it != "null" }
        val poster = optimizeTmdbImage(hero.optString("poster_src"), "w500")
        val backdrop = optimizeTmdbImage(hero.optString("backdrop_src"), "w1280")
        val publishedAt = hero.optString("published_at")
        val year = Regex("""(\d{4})""").find(publishedAt)?.groupValues?.get(1)?.toIntOrNull()
        val scoreVal = hero.optDouble("score", 0.0).takeIf { it > 0.0 }
        val statusStr = hero.optString("status").lowercase()
        val showStatus = if (statusStr == "airing" || statusStr == "ongoing") {
            ShowStatus.Ongoing
        } else {
            ShowStatus.Completed
        }

        val genres = mutableListOf<String>()
        hero.optJSONArray("genres")?.let { gArr ->
            for (i in 0 until gArr.length()) {
                gArr.optString(i).trim().takeIf { it.isNotBlank() }?.let { genres.add(it) }
            }
        }

        val dubEpisodes = mutableListOf<Episode>()
        val subEpisodes = mutableListOf<Episode>()
        val epArr = dataObj.optJSONArray("episodes") ?: JSONArray()

        for (i in 0 until epArr.length()) {
            val ep = epArr.optJSONObject(i) ?: continue
            val epId = ep.optString("id").trim().takeIf { it.isNotBlank() && it != "null" } ?: continue
            val epNum = ep.optInt("number", i + 1).takeIf { it > 0 } ?: (i + 1)
            val epSeason = if (isMovie || ep.isNull("season")) null else ep.optInt("season", 1).takeIf { it > 0 }
            val rawTitle = ep.optString("title").trim().takeIf { it.isNotBlank() && it != "null" }
            val epName = when {
                isMovie && rawTitle == null -> "Filme"
                rawTitle != null -> rawTitle
                else -> "Episódio $epNum"
            }
            val rawSyn = ep.optString("synopsis").trim()
            val epSyn = rawSyn.takeUnless {
                it.isBlank() || it == "null" || it.equals("Sem sinopse até o momento", ignoreCase = true)
            }
            val epThumb = optimizeTmdbImage(ep.optString("still_src"), "w500") ?: poster
            val (hasDub, hasSub) = parseAudioFlags(ep.optString("audio"))

            if (hasDub) {
                dubEpisodes.add(
                    newEpisode("$API_URL/episode/$epId|audio=dublado") {
                        this.name = epName
                        this.season = epSeason
                        this.episode = epNum
                        this.posterUrl = epThumb
                        this.description = epSyn
                    }
                )
            }
            if (hasSub) {
                subEpisodes.add(
                    newEpisode("$API_URL/episode/$epId|audio=legendado") {
                        this.name = epName
                        this.season = epSeason
                        this.episode = epNum
                        this.posterUrl = epThumb
                        this.description = epSyn
                    }
                )
            }
        }

        val recs = mutableListOf<SearchResponse>()
        dataObj.optJSONArray("recommendations")?.let { rArr ->
            for (i in 0 until rArr.length()) {
                rArr.optJSONObject(i)?.let { itemToSearchResponse(it) }?.let { recs.add(it) }
            }
        }

        return newAnimeLoadResponse(title, "$mainUrl/anime/$animeId", tvType) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.plot = synopsis
            this.tags = genres
            this.showStatus = showStatus
            this.recommendations = recs.distinctBy { it.url }
            scoreVal?.let { this.score = Score.from10(it) }
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
        }
    }

    private fun maxQualityFromArray(arr: JSONArray?): Int {
        if (arr == null) return Qualities.P720.value
        var best = Qualities.Unknown.value
        for (i in 0 until arr.length()) {
            val q = arr.optString(i).lowercase()
            val v = when {
                q.contains("1080") -> Qualities.P1080.value
                q.contains("720") -> Qualities.P720.value
                q.contains("480") -> Qualities.P480.value
                q.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
            if (v > best) best = v
        }
        return if (best > 0) best else Qualities.P720.value
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|audio=")
        val epUrl = parts[0]
        val preferredAudio = parts.getOrNull(1)?.lowercase()

        val json = try {
            JSONObject(app.get(epUrl, headers = API_HEADERS).text)
        } catch (_: Exception) {
            return false
        }

        val streamsArr = json.optJSONObject("data")?.optJSONArray("streams") ?: return false
        data class StreamItem(
            val audio: String,
            val url: String,
            val maxQuality: Int,
            val qualitiesLabel: String
        )

        val onlineStreams = mutableListOf<StreamItem>()
        for (i in 0 until streamsArr.length()) {
            val st = streamsArr.optJSONObject(i) ?: continue
            if (st.optBoolean("is_offline", false)) continue
            val streamUrl = st.optString("url").trim()
            if (streamUrl.isBlank() || streamUrl == "null") continue
            val audio = st.optString("audio").lowercase()
            val qArr = st.optJSONArray("qualities")
            val maxQ = maxQualityFromArray(qArr)
            val qLabel = when (maxQ) {
                Qualities.P1080.value -> "1080p"
                Qualities.P720.value -> "720p"
                Qualities.P480.value -> "480p"
                Qualities.P360.value -> "360p"
                else -> "Auto"
            }
            onlineStreams.add(StreamItem(audio, streamUrl, maxQ, qLabel))
        }

        if (onlineStreams.isEmpty()) return false

        val matching = if (!preferredAudio.isNullOrBlank()) {
            onlineStreams.filter { it.audio == preferredAudio }.ifEmpty { onlineStreams }
        } else {
            onlineStreams
        }

        var found = false
        matching.forEach { st ->
            val audioLabel = when (st.audio) {
                "dublado" -> "Dublado"
                "legendado" -> "Legendado"
                else -> "Auto"
            }
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "AnimeFire • $audioLabel • ${st.qualitiesLabel}",
                    url = st.url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.quality = st.maxQuality
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "User-Agent" to UA,
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/"
                    )
                }
            )
            found = true
        }

        return found
    }
}
