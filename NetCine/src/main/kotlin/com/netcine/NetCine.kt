package com.netcine

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import java.net.URLEncoder

@CloudstreamPlugin
class NetCinePlugin : Plugin() {
    private var browserStream: NetCineBrowserStream? = null

    override fun load(context: Context) {
        val stream = NetCineBrowserStream().also { browserStream = it }
        registerMainAPI(NetCine(stream))
    }

    override fun beforeUnload() {
        runCatching { browserStream?.close() }
        browserStream = null
    }
}

class NetCine internal constructor(
    private val browserStream: NetCineBrowserStream
) : TmdbProvider() {
    override var mainUrl = "https://maskara.lat"
    override var name = "NetCine"
    override var lang = "pt-br"
    override val hasMainPage = true
    override val usesWebView = true
    override val hasDownloadSupport = false
    override val useMetaLoadResponse = false
    override val providerType = ProviderType.DirectProvider
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/category/ultimos-filmes/" to "Últimos Filmes",
        "$mainUrl/tvshows/" to "Últimas Séries",
        "$mainUrl/category/acao/" to "Filmes de Ação",
        "$mainUrl/category/animacao/" to "Animação",
        "$mainUrl/category/comedia/" to "Comédia",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/tvshows/category/acao/" to "Séries de Ação"
    )

    private fun NetCatalog.Item.toSearchResponse(): SearchResponse = if (series) {
        newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = poster
            this.year = this@toSearchResponse.year
        }
    } else {
        newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = poster
            this.year = this@toSearchResponse.year
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url, referer = "$mainUrl/").document
        val next = doc.select("a[href]").any {
            it.absUrl("href").trimEnd('/') == "${request.data}page/${page + 1}".trimEnd('/')
        }
        return newHomePageResponse(
            request.name,
            NetCatalog.items(doc).map { it.toSearchResponse() },
            next
        )
    }

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
            val doc = app.get("$mainUrl/?s=$encoded", referer = "$mainUrl/").document
            val items = NetCatalog.items(doc).map { it.toSearchResponse() }
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    private fun stripDiacritics(input: String): String =
        java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .trim()

    override suspend fun search(query: String, page: Int): SearchResponseList =
        newSearchResponseList(if (page == 1) search(query) else emptyList(), false)

    override suspend fun load(url: String): LoadResponse {
        if (TmdbPtBrBridge.isTmdbInput(url)) {
            val resolved = TmdbPtBrBridge.resolveUrl(url, ::search)
                ?: throw ErrorLoadingException("Não encontrado no NetCine")
            return load(resolved)
        }
        val doc = app.get(url, referer = "$mainUrl/").document
        val title = doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: throw ErrorLoadingException("Título não encontrado")
        val image = doc.selectFirst(".imgs img[data-src], .imgs noscript img, .imgs img")
        val rawPoster = image?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: image?.absUrl("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        val poster = NetCatalog.upgradePoster(rawPoster)
        val plot = doc.selectFirst("#dato-2 p")?.text()?.trim()?.takeIf { it.isNotBlank() }
        val genres = doc.select("#dato-1 a[href*=/category/]").map { it.text().trim() }.filter { it.isNotBlank() }.distinct()
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(doc.selectFirst("#dato-1")?.text().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val ratingVal = Regex("""(\d+(?:\.\d+)?)""").find(doc.selectFirst(".imdb")?.text().orEmpty())
            ?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it in 0.1..10.0 }

        val episodes = NetCatalog.episodes(doc).map { ep ->
            newEpisode(ep.url) {
                this.name = ep.title.ifBlank { ep.number?.let { "Episódio $it" } ?: "Episódio" }
                this.season = ep.season
                this.episode = ep.number
                this.posterUrl = poster
            }
        }

        return if (url.contains("/tvshows/") || episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
                ratingVal?.let { this.score = Score.from10(it) }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
                ratingVal?.let { this.score = Score.from10(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, referer = "$mainUrl/").document
        val players = NetCatalog.players(doc, mainUrl)
        if (players.isEmpty()) return false

        // Prioritize Dublado first, then Legendado.
        val ordered = players.sortedByDescending {
            when {
                it.audio.contains("Dublado", true) -> 2
                it.audio.contains("Legendado", true) -> 1
                else -> 0
            }
        }

        var found = false
        for (player in ordered) {
            try {
                val localPlaylistUrl = browserStream.register(
                    archiveUrl = player.url,
                    pageReferer = data,
                    audioLabel = player.audio
                )
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "NetCine • ${player.audio}",
                        url = localPlaylistUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P1080.value
                        this.referer = ""
                    }
                )
                found = true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Continue with remaining audio options
            }
        }
        return found
    }
}
