package com.brflixtv

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

@CloudstreamPlugin
class BRFlixTvPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BRFlixTV())
    }
}

class BRFlixTV : MainAPI() {
    override var mainUrl = "https://brflix.online"
    override var name = "BRFlix TV"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val CHANNELS_CACHE_MS = 60 * 60 * 1000L
        private const val AGENDA_CACHE_MS = 15 * 60 * 1000L

        private val HTML_CATEGORY_PAGES = listOf(
            "/esportes" to "Esportes",
            "/filmes" to "Filmes e Séries",
            "/abertos" to "TV Aberta",
            "/noticias" to "Notícias",
            "/infantil" to "Infantil",
            "/" to "Variedades"
        )

        @Volatile
        private var cachedChannels: List<BrFlixChannel>? = null

        @Volatile
        private var lastChannelsFetchMs: Long = 0L

        @Volatile
        private var cachedEvents: List<BrFlixEvent>? = null

        @Volatile
        private var lastEventsFetchMs: Long = 0L
    }

    private fun defaultHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to referer
    )

    private suspend fun getHtmlChannels(): List<BrFlixChannel> {
        val now = System.currentTimeMillis()
        val cached = cachedChannels
        if (cached != null && (now - lastChannelsFetchMs) < CHANNELS_CACHE_MS) {
            return cached
        }

        val perPage = HTML_CATEGORY_PAGES.amap { (path, label) ->
            try {
                val doc = app.get("$mainUrl$path", headers = defaultHeaders()).document
                BRFlixParser.parseHtmlChannels(doc, mainUrl, label)
            } catch (_: Exception) {
                emptyList()
            }
        }

        val merged = linkedMapOf<String, BrFlixChannel>()
        for (list in perPage) {
            for (ch in list) {
                merged.putIfAbsent(ch.id, ch)
            }
        }

        val finalList = merged.values.toList()
        if (finalList.isNotEmpty()) {
            cachedChannels = finalList
            lastChannelsFetchMs = now
        }
        return finalList
    }

    private suspend fun getAgendaEvents(): List<BrFlixEvent> {
        val now = System.currentTimeMillis()
        val cached = cachedEvents
        if (cached != null && (now - lastEventsFetchMs) < AGENDA_CACHE_MS) {
            return cached
        }

        val parsed = try {
            val jsonText = app.get(
                "$mainUrl/api/live/eventos",
                headers = defaultHeaders(referer = "$mainUrl/futebol") + ("Accept" to "application/json")
            ).text
            BRFlixParser.parseAgendaEvents(jsonText)
        } catch (_: Exception) {
            emptyList()
        }

        cachedEvents = parsed
        lastEventsFetchMs = now
        return parsed
    }

    private fun BrFlixEvent.toDisplayTitle(): String {
        val prefix = if (timeStr.isNotEmpty()) "[$statusBadge - $timeStr]" else "[$statusBadge]"
        val comp = if (competition.isNotBlank()) " ($competition)" else ""
        return "$prefix $title$comp"
    }

    private fun BrFlixEvent.toSearchResponse(): SearchResponse {
        val itemUrl = "$mainUrl/evento/${slug}"
        return newLiveSearchResponse(toDisplayTitle(), itemUrl, TvType.Live) {
            this.posterUrl = this@toSearchResponse.posterUrl
        }
    }

    private fun BrFlixChannel.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(title, url, TvType.Live) {
            this.posterUrl = this@toSearchResponse.posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeCategories = mutableListOf<HomePageList>()

        // 1. Agenda Esportiva (Ao Vivo e Em Breve) — igual ao Rei dos Embeds
        val events = getAgendaEvents()
        if (events.isNotEmpty()) {
            homeCategories.add(
                HomePageList(
                    "Agenda (Ao Vivo e Em Breve)",
                    events.map { it.toSearchResponse() },
                    isHorizontalImages = true
                )
            )
        }

        // 2. Canais montados diretamente do HTML das páginas de categoria do site
        val channels = getHtmlChannels()
        if (channels.isNotEmpty()) {
            homeCategories.add(
                HomePageList("Todos os Canais", channels.map { it.toSearchResponse() }, isHorizontalImages = true)
            )

            val grouped = channels.groupBy { it.category }
            val order = listOf("Esportes", "TV Aberta", "Filmes e Séries", "Notícias", "Infantil", "Variedades", "Canais")
            for (cat in order) {
                val list = grouped[cat]?.map { it.toSearchResponse() }.orEmpty()
                if (list.isNotEmpty()) {
                    homeCategories.add(HomePageList(cat, list, isHorizontalImages = true))
                }
            }
        }

        if (homeCategories.isEmpty()) {
            throw ErrorLoadingException("Nenhum canal ou evento encontrado no BRFlix TV.")
        }

        return newHomePageResponse(homeCategories, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim().lowercase()
        if (term.isBlank()) return emptyList()

        val results = mutableListOf<SearchResponse>()
        getAgendaEvents()
            .filter { it.title.lowercase().contains(term) || it.competition.lowercase().contains(term) }
            .forEach { results.add(it.toSearchResponse()) }

        getHtmlChannels()
            .filter { it.title.lowercase().contains(term) || it.category.lowercase().contains(term) }
            .forEach { results.add(it.toSearchResponse()) }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("/evento/")) {
            val slug = url.substringAfter("/evento/").substringBefore("?").trim('/')
            val event = cachedEvents?.firstOrNull { it.slug == slug }
            val title = event?.toDisplayTitle() ?: slug.replace("-", " ")
            val data = "event::${event?.id ?: "e/$slug"}::e/$slug"
            return newMovieLoadResponse(title, url, TvType.Live, data) {
                this.posterUrl = event?.posterUrl
                this.plot = event?.competition?.takeIf { it.isNotBlank() }
                    ?.let { "$title — $it ao vivo no BRFlix TV" }
                    ?: "Assista $title ao vivo no BRFlix TV"
            }
        }

        val slug = url.substringAfter("/canal/").substringBefore("?").trim('/')
        val ch = cachedChannels?.firstOrNull { it.id == slug }
        val title = ch?.title ?: run {
            try {
                val doc = app.get(url, headers = defaultHeaders()).document
                doc.selectFirst("h1")?.text()?.replace("Assistir", "", ignoreCase = true)
                    ?.replace("Ao Vivo Online em Full HD", "", ignoreCase = true)?.trim()
                    ?.ifBlank { slug } ?: slug
            } catch (_: Exception) {
                slug
            }
        }

        return newMovieLoadResponse(title, url, TvType.Live, "channel::$slug::$slug") {
            this.posterUrl = ch?.posterUrl
            this.plot = "Assista $title ao vivo no BRFlix TV"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("::")
        val channelId = if (parts.size >= 2) parts[1] else data.substringAfterLast("/")
        val directUrl = if (parts.size >= 3) parts[2] else channelId

        val resolveUrl = "$mainUrl/api/resolve/${URLEncoder.encode(channelId, "UTF-8")}?directUrl=${URLEncoder.encode(directUrl, "UTF-8")}"
        val refererPage = if (parts.firstOrNull() == "event") "$mainUrl/futebol" else "$mainUrl/canal/$channelId"

        val resolveJson = try {
            app.get(
                resolveUrl,
                headers = defaultHeaders(referer = refererPage) + ("Accept" to "application/json")
            ).text
        } catch (_: Exception) {
            return false
        }

        val parsed = BRFlixParser.parseResolveJson(resolveJson, mainUrl)
        var emitted = false

        for (stream in parsed.directStreams) {
            emitHls(stream.label, stream.url, stream.referer, callback)
            emitted = true
        }

        if (!emitted && parsed.fallbackEmbeds.isNotEmpty()) {
            parsed.fallbackEmbeds.amap { (provider, embedUrl) ->
                if (resolveFallbackEmbed(embedUrl, refererPage, provider, subtitleCallback, callback)) {
                    emitted = true
                }
            }
        }

        return emitted
    }

    private suspend fun emitHls(
        label: String,
        streamUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = try {
            val u = URI(referer)
            "${u.scheme}://${u.host}"
        } catch (_: Exception) {
            mainUrl
        }
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Origin" to origin,
            "Accept" to "*/*"
        )
        if (streamUrl.endsWith(".txt", ignoreCase = true) || streamUrl.contains("error.txt")) {
            M3u8Helper.generateM3u8(label, streamUrl, referer, headers = headers).forEach(callback)
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = streamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = referer
                    this.quality = Qualities.P1080.value
                    this.headers = headers
                }
            )
        }
    }

    private suspend fun resolveFallbackEmbed(
        startUrl: String,
        initialReferer: String,
        providerName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var currentUrl = startUrl
        var referer = initialReferer

        for (hop in 0..2) {
            try {
                if (currentUrl.contains("youtube.com") || currentUrl.contains("youtu.be") || currentUrl.contains("twitch.tv")) {
                    loadExtractor(currentUrl, subtitleCallback, callback)
                    return true
                }

                val html = app.get(currentUrl, headers = defaultHeaders(referer = referer)).text
                val origin = try {
                    val u = URI(currentUrl)
                    "${u.scheme}://${u.host}"
                } catch (_: Exception) {
                    mainUrl
                }

                // Pattern 1: ReiDosEmbeds var sources = [...]
                val sourcesMatch = Regex("""var\s+sources\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL).find(html)
                if (sourcesMatch != null) {
                    val arr = JSONArray(sourcesMatch.groupValues[1])
                    var found = false
                    for (j in 0 until arr.length()) {
                        val s = arr.optJSONObject(j) ?: continue
                        var src = s.optString("src").replace("\\/", "/")
                        if (src.isBlank()) {
                            val refPath = s.optString("ref").replace("\\/", "/")
                            if (refPath.isNotBlank()) {
                                val refUrl = if (refPath.startsWith("http")) refPath else "$origin$refPath"
                                val resp = app.post(
                                    refUrl,
                                    headers = mapOf(
                                        "User-Agent" to USER_AGENT,
                                        "Accept" to "application/json, text/plain, */*",
                                        "Referer" to currentUrl,
                                        "Origin" to origin
                                    )
                                ).text
                                src = JSONObject(resp).optString("src").replace("\\/", "/")
                            }
                        }
                        if (src.isNotBlank()) {
                            emitHls("$providerName - ${s.optString("label", "HD")}", src, currentUrl, callback)
                            found = true
                        }
                    }
                    if (found) return true
                }

                // Pattern 2: EmbedTV const src = "..." or direct .m3u8
                val direct = BRFlixParser.extractStreamFromEmbedHtml(html)
                if (!direct.isNullOrBlank()) {
                    emitHls(providerName, direct, "$origin/", callback)
                    return true
                }

                // Pattern 3: nested iframe
                val nextIframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
                    ?: break
                referer = currentUrl
                currentUrl = when {
                    nextIframe.startsWith("http") -> nextIframe
                    nextIframe.startsWith("//") -> "https:$nextIframe"
                    nextIframe.startsWith("/") -> "$origin$nextIframe"
                    else -> "$origin/$nextIframe"
                }
            } catch (_: Exception) {
                break
            }
        }
        return false
    }
}
