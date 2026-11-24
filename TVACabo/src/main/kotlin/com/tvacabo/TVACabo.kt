package com.tvacabo

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

@CloudstreamPlugin
class TVACaboPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(TVACabo())
    }
}

class TVACabo : MainAPI() {
    override var mainUrl = "https://tvacabo.top"
    override var name = "TV a Cabo"
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
        private const val EMBED_SEP = "||"
        private const val LABEL_SEP = "::"

        @Volatile
        private var cachedChannels: List<TvCaboChannel>? = null

        @Volatile
        private var lastChannelsFetchMs: Long = 0L

        @Volatile
        private var cachedEvents: List<TvCaboEvent>? = null

        @Volatile
        private var lastEventsFetchMs: Long = 0L
    }

    private fun defaultHeaders(referer: String = "$mainUrl/"): Map<String, String> = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to referer
    )

    private suspend fun getChannels(): List<TvCaboChannel> {
        val now = System.currentTimeMillis()
        val cached = cachedChannels
        if (cached != null && (now - lastChannelsFetchMs) < CHANNELS_CACHE_MS) {
            return cached
        }

        val jsonText = app.get("$mainUrl/json.php", headers = defaultHeaders()).text
        val parsed = TVACaboParser.parseChannelsJson(jsonText)
        if (parsed.isNotEmpty()) {
            cachedChannels = parsed
            lastChannelsFetchMs = now
        }
        return parsed
    }

    private suspend fun getSportsEvents(): List<TvCaboEvent> {
        val now = System.currentTimeMillis()
        val cached = cachedEvents
        if (cached != null && (now - lastEventsFetchMs) < AGENDA_CACHE_MS) {
            return cached
        }

        val parsed = try {
            val esporteHtml = app.get("$mainUrl/esporte.html", headers = defaultHeaders()).text
            val apiUrl = TVACaboParser.extractSportsApiUrl(esporteHtml)
            val jsonText = app.get(apiUrl, headers = defaultHeaders(referer = "$mainUrl/esporte.html")).text
            TVACaboParser.parseSportsEventsJson(jsonText)
        } catch (_: Exception) {
            emptyList()
        }

        cachedEvents = parsed
        lastEventsFetchMs = now
        return parsed
    }

    private fun TvCaboEvent.toDisplayTitle(): String {
        val prefix = if (timeStr.isNotEmpty()) "[$statusBadge - $timeStr]" else "[$statusBadge]"
        val comp = if (competition.isNotBlank()) " ($competition)" else ""
        return "$prefix $title$comp"
    }

    private fun TvCaboEvent.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(toDisplayTitle(), "$mainUrl/#event/$id", TvType.Live) {
            this.posterUrl = this@toSearchResponse.posterUrl ?: "$mainUrl/favicon.png"
        }
    }

    private fun TvCaboChannel.toSearchResponse(): SearchResponse {
        return newLiveSearchResponse(name, url, TvType.Live) {
            this.posterUrl = "$mainUrl/favicon.png"
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeCategories = mutableListOf<HomePageList>()

        // 1. Agenda Esportiva (Jogos do Dia — Ao Vivo e Em Breve)
        val events = getSportsEvents()
        if (events.isNotEmpty()) {
            homeCategories.add(
                HomePageList(
                    "Agenda (Ao Vivo e Em Breve)",
                    events.map { it.toSearchResponse() },
                    isHorizontalImages = true
                )
            )
        }

        // 2. Canais (1 única carga do json.php, sem requisições extras por canal)
        val channels = getChannels()
        if (channels.isNotEmpty()) {
            homeCategories.add(
                HomePageList("Todos os Canais", channels.map { it.toSearchResponse() }, isHorizontalImages = true)
            )

            val grouped = channels.groupBy { it.category }
            for (cat in TVACaboParser.CATEGORY_ORDER) {
                val items = grouped[cat]?.map { it.toSearchResponse() }.orEmpty()
                if (items.isNotEmpty()) {
                    homeCategories.add(HomePageList(cat, items, isHorizontalImages = true))
                }
            }
        }

        if (homeCategories.isEmpty()) {
            throw ErrorLoadingException("Nenhum canal encontrado no TV a Cabo.")
        }

        return newHomePageResponse(homeCategories, hasNext = false)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim().lowercase()
        if (term.isBlank()) return emptyList()

        val results = mutableListOf<SearchResponse>()
        getSportsEvents()
            .filter { it.title.lowercase().contains(term) || it.competition.lowercase().contains(term) }
            .forEach { results.add(it.toSearchResponse()) }

        getChannels()
            .filter { it.name.lowercase().contains(term) || it.category.lowercase().contains(term) }
            .forEach { results.add(it.toSearchResponse()) }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("#event/")) {
            val eventId = url.substringAfter("#event/")
            val event = cachedEvents?.firstOrNull { it.id == eventId }
                ?: getSportsEvents().firstOrNull { it.id == eventId }
            val title = event?.toDisplayTitle() ?: eventId.replace("-", " ")
            val encoded = event?.embeds?.joinToString(EMBED_SEP) { "${it.provider}$LABEL_SEP${it.url}" }.orEmpty()
            return newMovieLoadResponse(title, url, TvType.Live, encoded) {
                this.posterUrl = event?.posterUrl ?: "$mainUrl/favicon.png"
                this.plot = event?.competition?.takeIf { it.isNotBlank() }
                    ?.let { "$title — $it ao vivo no TV a Cabo" }
                    ?: "Assista $title ao vivo no TV a Cabo"
            }
        }

        val ch = cachedChannels?.firstOrNull { it.url == url }
        val title = ch?.name ?: "Canal Ao Vivo"
        return newMovieLoadResponse(title, url, TvType.Live, "$title$LABEL_SEP$url") {
            this.posterUrl = "$mainUrl/favicon.png"
            this.plot = "Assista $title ao vivo no TV a Cabo"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targets = data.split(EMBED_SEP).mapNotNull { chunk ->
            val trimmed = chunk.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val idx = trimmed.indexOf(LABEL_SEP)
            if (idx > 0 && trimmed.substring(idx + LABEL_SEP.length).startsWith("http")) {
                trimmed.substring(0, idx).trim() to trimmed.substring(idx + LABEL_SEP.length).trim()
            } else {
                name to trimmed
            }
        }

        var foundAny = false
        targets.amap { (label, rawTargetUrl) ->
            if (resolveTarget(rawTargetUrl, "$mainUrl/", label, subtitleCallback, callback)) {
                foundAny = true
            }
        }
        return foundAny
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

    private suspend fun resolveTarget(
        rawUrl: String,
        initialReferer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val unwrapped = TVACaboParser.unwrapWrapperUrl(rawUrl)
        if (unwrapped.isBlank()) return false

        // Direct M3U8 URL
        if (unwrapped.contains(".m3u8", ignoreCase = true) && !unwrapped.contains(".html", ignoreCase = true)) {
            emitHls(label, unwrapped, "$mainUrl/", callback)
            return true
        }

        // Known external platforms
        if (unwrapped.contains("dailymotion.com") || unwrapped.contains("youtube.com") ||
            unwrapped.contains("youtu.be") || unwrapped.contains("twitch.tv")
        ) {
            return loadExtractor(unwrapped, subtitleCallback, callback)
        }

        // Try direct URL first; if rotated redecanaistv.by fails, fall back to redecanais.ro
        val primaryOk = followPlayerChain(unwrapped, initialReferer, label, subtitleCallback, callback)
        if (primaryOk) return true

        val rcFallback = TVACaboParser.redeCanaisLiveFallback(unwrapped)
        if (!rcFallback.isNullOrBlank()) {
            return followPlayerChain(rcFallback, "https://redecanais.ro/canais-de-tv/", label, subtitleCallback, callback)
        }
        return false
    }

    private suspend fun followPlayerChain(
        startUrl: String,
        initialReferer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var currentUrl = startUrl
        var referer = initialReferer

        for (hop in 0..3) {
            try {
                if (currentUrl.contains("dailymotion.com") || currentUrl.contains("youtube.com") ||
                    currentUrl.contains("youtu.be") || currentUrl.contains("twitch.tv")
                ) {
                    return loadExtractor(currentUrl, subtitleCallback, callback)
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
                    var emitted = false
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
                            emitHls("$label - ${s.optString("label", "HD")}", src, currentUrl, callback)
                            emitted = true
                        }
                    }
                    if (emitted) return true
                }

                // Pattern 2: EmbedTV const src = "..." / RedeCanais var SRC = "..." / direct .m3u8
                val direct = TVACaboParser.extractDirectStreamFromHtml(html)
                if (!direct.isNullOrBlank()) {
                    emitHls(label, direct, "$origin/", callback)
                    return true
                }

                // Pattern 3: nested iframe
                val nextIframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1)?.replace("&amp;", "&")
                    ?: break
                if (nextIframe.equals("about:blank", ignoreCase = true)) break
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
