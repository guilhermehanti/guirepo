package com.redecanais.tv

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*

@CloudstreamPlugin
class RedeCanaisTvPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RedeCanaisTV())
    }
}

class RedeCanaisTV : MainAPI() {
    override var mainUrl = "https://redecanais.ro"
    override var name = "RedeCanais TV"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val CACHE_TTL_MS = 15 * 60 * 1000L

        private val CATEGORY_ORDER = listOf(
            "Esportes",
            "TV Aberta",
            "Filmes e Séries",
            "Infantis",
            "Notícias",
            "Documentários",
            "Variedades e Outros"
        )

        @Volatile
        private var cachedChannels: List<LiveChannel>? = null

        @Volatile
        private var lastFetchMs: Long = 0L
    }

    private fun defaultHeaders(referer: String = "$mainUrl/canais-de-tv/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to referer
    )

    private suspend fun getChannels(): List<LiveChannel> {
        val now = System.currentTimeMillis()
        val cached = cachedChannels
        if (cached != null && (now - lastFetchMs) < CACHE_TTL_MS) {
            return cached
        }
        val doc = app.get("$mainUrl/canais-de-tv/", headers = defaultHeaders()).document
        val parsed = RedeCanaisTvParser.parseChannels(doc, mainUrl)
        if (parsed.isNotEmpty()) {
            cachedChannels = parsed
            lastFetchMs = now
        }
        return parsed
    }

    private fun LiveChannel.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(name, url, TvType.Live) {
            this.posterUrl = this@toSearchResponse.posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = getChannels()
        if (channels.isEmpty()) {
            throw ErrorLoadingException("Nenhum canal encontrado no RedeCanais TV.")
        }

        val grouped = channels.groupBy { it.category }
        val homeLists = mutableListOf<HomePageList>()

        for (cat in CATEGORY_ORDER) {
            val items = grouped[cat]?.map { it.toSearchResponse() }.orEmpty()
            if (items.isNotEmpty()) {
                homeLists.add(HomePageList(cat, items, isHorizontalImages = true))
            }
        }

        homeLists.add(
            HomePageList("Todos os Canais", channels.map { it.toSearchResponse() }, isHorizontalImages = true)
        )

        return newHomePageResponse(homeLists, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim().lowercase()
        if (term.isBlank()) return emptyList()
        return getChannels()
            .filter { it.name.lowercase().contains(term) || it.category.lowercase().contains(term) }
            .map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fullUrl = RedeCanaisTvParser.resolveUrl(url, mainUrl)
        val cached = cachedChannels?.firstOrNull { it.url == fullUrl }

        val title = cached?.name ?: run {
            val doc = app.get(fullUrl, headers = defaultHeaders()).document
            doc.selectFirst("h1")?.text()?.trim()
                ?.ifBlank { doc.title().replace(Regex("""\s*[-–|].*$"""), "").trim() }
                ?: "Canal Ao Vivo"
        }

        return newMovieLoadResponse(title, fullUrl, TvType.Live, fullUrl) {
            this.posterUrl = cached?.posterUrl
            this.plot = "Assista $title ao vivo no RedeCanais TV"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val channelUrl = RedeCanaisTvParser.resolveUrl(data, mainUrl)
        if (channelUrl.isBlank()) return false

        val pageHtml = app.get(channelUrl, headers = defaultHeaders()).text
        val iframeUrl = RedeCanaisTvParser.extractLiveIframe(pageHtml, mainUrl) ?: return false
        val playerHtml = app.get(iframeUrl, headers = defaultHeaders(referer = channelUrl)).text
        val streamUrl = RedeCanaisTvParser.extractLiveStream(playerHtml) ?: return false

        val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)
        callback(
            newExtractorLink(
                source = name,
                name = "RedeCanais TV",
                url = streamUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.quality = Qualities.P1080.value
                this.headers = mapOf("User-Agent" to USER_AGENT)
            }
        )
        return true
    }
}
