package com.redecanais

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

@CloudstreamPlugin
class RedeCanaisPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RedeCanais())
    }
}

class RedeCanais : MainAPI() {
    override var mainUrl = "https://redecanais.ro"
    override var name = "RedeCanais"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Movie
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val LINK_SEPARATOR = "||"
        private const val LABEL_SEPARATOR = "::"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/browse-desenhos-videos-%d-date.html" to "Desenhos",
        "$mainUrl/browse-series-videos-%d-date.html" to "Séries",
        "$mainUrl/browse-animes-videos-%d-date.html" to "Animes",
        "$mainUrl/browse-filmes-lancamentos-videos-%d-date.html" to "Filmes Lançamentos",
        "$mainUrl/browse-filmes-videos-%d-date.html" to "Filmes Dublados",
        "$mainUrl/search.php?keywords=legendado&page=%d" to "Filmes Legendados"
    )

    private fun defaultHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to referer
    )

    private fun CatalogCard.toSearchResponse(sectionHint: String = ""): SearchResponse {
        val tvType = when {
            isSeries && sectionHint.contains("Desenho", ignoreCase = true) -> TvType.Cartoon
            isSeries && sectionHint.contains("Anime", ignoreCase = true) -> TvType.Anime
            isSeries -> TvType.TvSeries
            else -> TvType.Movie
        }

        val displayTitle = if (!audioTag.isNullOrBlank() && !title.contains(audioTag, ignoreCase = true)) {
            "$title ($audioTag)"
        } else {
            title
        }

        val imgHeaders = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT)
        return if (tvType == TvType.Movie) {
            newMovieSearchResponse(displayTitle, url, TvType.Movie) {
                this.posterUrl = this@toSearchResponse.posterUrl
                this.posterHeaders = imgHeaders
                this.year = this@toSearchResponse.year
            }
        } else {
            newTvSeriesSearchResponse(displayTitle, url, tvType) {
                this.posterUrl = this@toSearchResponse.posterUrl
                this.posterHeaders = imgHeaders
                this.year = this@toSearchResponse.year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.format(page)
        val doc = try {
            app.get(url, headers = defaultHeaders()).document
        } catch (e: Exception) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            throw e
        }

        val items = RedeCanaisParser.parseCards(doc, mainUrl)
            .map { it.toSearchResponse(request.name) }
        val hasNext = items.isNotEmpty() && RedeCanaisParser.hasNextPage(doc, page)
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/search.php?keywords=$encoded"
        val doc = app.get(url, headers = defaultHeaders()).document
        return RedeCanaisParser.parseCards(doc, mainUrl)
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fullUrl = RedeCanaisParser.resolveUrl(url, mainUrl)
        val doc = app.get(fullUrl, headers = defaultHeaders()).document

        val rawTitle = doc.selectFirst("h1")?.text()?.trim()
            ?.ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() }
            ?: doc.title().trim()
        val meta = RedeCanaisParser.parseTitleMetadata(rawTitle)

        val posterUrl = RedeCanaisParser.resolvePosterUrl(
            doc.selectFirst("meta[property=og:image]")?.attr("content"),
            mainUrl
        )

        val backdropUrl = RedeCanaisParser.resolvePosterUrl(
            doc.selectFirst("img.rc-backdrop-image")?.attr("src"),
            mainUrl
        ) ?: posterUrl

        val imgHeaders = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT)

        val synopsis = RedeCanaisParser.cleanSynopsis(
            doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        )

        val parsedEpisodes = RedeCanaisParser.parseSeriesEpisodes(doc, mainUrl)
        val isSeries = fullUrl.contains("lista-de-episodios", ignoreCase = true) || parsedEpisodes.isNotEmpty()

        return if (isSeries) {
            val episodes = parsedEpisodes.map { ep ->
                val encodedData = ep.links.joinToString(LINK_SEPARATOR) { link ->
                    "${link.label}$LABEL_SEPARATOR${link.url}"
                }
                newEpisode(encodedData) {
                    this.name = ep.name
                    this.season = ep.season
                    this.episode = ep.episode
                    this.posterUrl = backdropUrl
                }
            }

            newTvSeriesLoadResponse(meta.title, fullUrl, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.posterHeaders = imgHeaders
                this.backgroundPosterUrl = backdropUrl
                this.year = meta.year
                this.plot = synopsis
            }
        } else {
            val label = meta.audioTag ?: "RedeCanais"
            newMovieLoadResponse(meta.title, fullUrl, TvType.Movie, "$label$LABEL_SEPARATOR$fullUrl") {
                this.posterUrl = posterUrl
                this.posterHeaders = imgHeaders
                this.backgroundPosterUrl = backdropUrl
                this.year = meta.year
                this.plot = synopsis
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val entries = data.split(LINK_SEPARATOR)
            .mapNotNull { chunk ->
                val trimmed = chunk.trim()
                if (trimmed.isBlank()) return@mapNotNull null
                val idx = trimmed.indexOf(LABEL_SEPARATOR)
                if (idx > 0 && trimmed.substring(idx + LABEL_SEPARATOR.length).startsWith("http")) {
                    trimmed.substring(0, idx).trim() to trimmed.substring(idx + LABEL_SEPARATOR.length).trim()
                } else {
                    "RedeCanais" to RedeCanaisParser.resolveUrl(trimmed, mainUrl)
                }
            }
            .distinctBy { it.second }

        var foundAny = false

        for ((rawLabel, pageUrl) in entries) {
            try {
                val pageHtml = app.get(pageUrl, headers = defaultHeaders()).text
                val iframeUrl = RedeCanaisParser.extractPlayerIframe(pageHtml, mainUrl) ?: continue
                val playerHtml = app.get(iframeUrl, headers = defaultHeaders(referer = pageUrl)).text
                val stream = RedeCanaisParser.extractStreamSource(playerHtml) ?: continue

                val displayName = when {
                    rawLabel.equals("Assistir", ignoreCase = true) || rawLabel.equals("RedeCanais", ignoreCase = true) ->
                        "RedeCanais"
                    else -> "RedeCanais • $rawLabel"
                }

                callback(
                    newExtractorLink(
                        source = name,
                        name = displayName,
                        url = stream.url,
                        type = if (stream.isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf("User-Agent" to USER_AGENT)
                    }
                )
                foundAny = true
            } catch (_: Exception) {
                // Try remaining audio/mirror entries if one fails
            }
        }

        return foundAny
    }
}
