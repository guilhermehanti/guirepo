package com.noveflix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

@CloudstreamPlugin
class NoveFlixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NoveFlix())
    }
}

class NoveFlix : MainAPI() {
    override var mainUrl = "https://noveflix.lol"
    override var name = "NoveFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/categoria/novelas/" to "Novelas",
        "$mainUrl/categoria/turca/" to "Novelas Turcas",
        "$mainUrl/categoria/mexicana/" to "Novelas Mexicanas",
        "$mainUrl/categoria/programas/" to "Programas"
    )

    private fun defaultHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to referer
    )

    private fun originOf(url: String): String {
        return try {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            mainUrl
        }
    }

    private fun NoveCard.toSearchResponse(): SearchResponse {
        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = this@toSearchResponse.posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page > 1) "$base/page/$page/" else "$base/"

        val doc = try {
            app.get(url, headers = defaultHeaders()).document
        } catch (e: Exception) {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            throw e
        }

        val items = NoveFlixParser.parseArchiveCards(doc, mainUrl).map { it.toSearchResponse() }
        val hasNext = items.isNotEmpty() && NoveFlixParser.hasNextPage(doc, page)
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val doc = app.get("$mainUrl/?s=$encoded", headers = defaultHeaders()).document
        return NoveFlixParser.parseSearchCards(doc, mainUrl).map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fullUrl = NoveFlixParser.resolveUrl(url, mainUrl)
        val doc = app.get(fullUrl, headers = defaultHeaders()).document

        val title = doc.selectFirst(".sheader .data h1, h1")?.text()?.trim()
            ?.ifBlank { doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim() }
            ?: doc.title().trim()

        val poster = NoveFlixParser.resolveUrl(
            doc.selectFirst(".sheader .poster img")?.attr("src")
                ?.ifBlank { doc.selectFirst("meta[property=og:image]")?.attr("content") },
            mainUrl
        ).ifBlank { null }

        val plot = doc.selectFirst(".wp-content p, meta[name=description]")?.let { el ->
            if (el.tagName() == "meta") el.attr("content") else el.text()
        }?.trim()?.ifBlank { null }

        val episodes = mutableListOf<Episode>()
        val options = NoveFlixParser.parseDooPlayerOptions(doc)

        for (opt in options) {
            try {
                val ajaxResp = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to "doo_player_ajax",
                        "post" to opt.post,
                        "nume" to opt.nume,
                        "type" to opt.type
                    ),
                    headers = defaultHeaders(referer = fullUrl) + ("X-Requested-With" to "XMLHttpRequest")
                ).text

                val embedUrl = JSONObject(ajaxResp).optString("embed_url").replace("\\/", "/").trim()
                if (embedUrl.isBlank()) continue

                val panelOrigin = originOf(embedUrl)
                val panelDoc = app.get(embedUrl, headers = defaultHeaders(referer = fullUrl)).document
                val chapters = NoveFlixParser.parsePanelChapters(panelDoc, panelOrigin)

                if (chapters.isNotEmpty()) {
                    chapters.forEach { ch ->
                        episodes.add(
                            newEpisode(ch.url) {
                                this.name = ch.name
                                this.season = ch.season
                                this.episode = ch.episode
                                this.posterUrl = ch.posterUrl ?: poster
                            }
                        )
                    }
                    break
                }
            } catch (_: Exception) {
                // Try next DooPlay option if available
            }
        }

        return newTvSeriesLoadResponse(title, fullUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val chapterUrl = NoveFlixParser.normalizeChapterUrl(data.trim(), mainUrl)
        if (chapterUrl.isBlank()) return false
        val panelOrigin = originOf(chapterUrl)

        val getResp = app.get(chapterUrl, headers = defaultHeaders(referer = "$panelOrigin/"))
        val getHtml = getResp.text
        val resolvedPageUrl = getResp.url.ifBlank { chapterUrl }
        val resolvedOrigin = originOf(resolvedPageUrl)

        var streamUrl = NoveFlixParser.extractStreamUrl(getHtml)

        if (streamUrl.isNullOrBlank()) {
            val form = NoveFlixParser.parseReleaseForm(Jsoup.parse(getHtml))
            if (form != null) {
                val postResp = app.post(
                    resolvedPageUrl,
                    data = mapOf(
                        "token" to form.token,
                        "issued" to form.issued,
                        "admin_bypass" to "0"
                    ),
                    headers = defaultHeaders(referer = resolvedPageUrl) + ("Origin" to resolvedOrigin),
                    cookies = getResp.cookies
                )
                streamUrl = NoveFlixParser.extractStreamUrl(postResp.text)
            }
        }

        if (streamUrl.isNullOrBlank()) return false

        val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)
        callback(
            newExtractorLink(
                source = name,
                name = "NoveFlix",
                url = streamUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$resolvedOrigin/"
                this.quality = Qualities.P720.value
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )
        return true
    }
}
