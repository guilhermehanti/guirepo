package com.guirepo.watchmmafull

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest

@CloudstreamPlugin
class WatchMMAFullPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WatchMMAFull())
    }
}

class WatchMMAFull : MainAPI() {
    override var mainUrl = "https://watchmmafull.com"
    override var name = "WatchMMAFull"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val usesWebView = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val BASE_HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        private const val DEFAULT_POSTER = "https://watchmmafull.com/pwa-icon/512?v=2"
        private val EMBED_REGEX = Regex("""https?://watchmmafull\.com/embed/[a-zA-Z0-9]+""")
        private val VIDARA_HOSTS = setOf("vidara.so", "vidaraa.cc", "oisauh.com")
        private val CONTENT_ST_HOSTS = setOf("content.st")
        private val PCFG_REGEX = Regex("""<script[^>]+id=["']pcfg["'][^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
    }

    override val mainPage = mainPageOf(
        "$mainUrl/events" to "Eventos MMA (UFC & Mais)"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) request.data else "${request.data}/$page"
        val doc = app.get(pageUrl, headers = BASE_HEADERS).document

        val items = doc.select("a.wmf-event-card").mapNotNull { card ->
            val href = card.attr("href").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val fullUrl = fixUrl(href)
            val title = card.selectFirst(".wmf-event-info h3")?.text()?.trim()
                ?: card.selectFirst("img")?.attr("alt")?.trim()
                ?: return@mapNotNull null
            val poster = fixUrlNull(card.selectFirst(".wmf-event-poster img")?.attr("src"))
                ?: DEFAULT_POSTER
            newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }

        val hasNext = doc.select(".wmf-events-pages a").any {
            it.text().contains("Next", ignoreCase = true) || it.attr("href").endsWith("/${page + 1}")
        }
        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext && items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val results = mutableListOf<SearchResponse>()
        val seen = HashSet<String>()

        // 1) Check recent events matching query (so searching "UFC 334" or "Prochazka" also returns full event series)
        try {
            val eventsDoc = app.get("$mainUrl/events", headers = BASE_HEADERS).document
            val tokens = q.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
            eventsDoc.select("a.wmf-event-card").forEach { card ->
                val title = card.selectFirst(".wmf-event-info h3")?.text()?.trim() ?: return@forEach
                val lower = title.lowercase()
                if (tokens.isNotEmpty() && tokens.all { lower.contains(it) }) {
                    val href = fixUrl(card.attr("href").trim())
                    if (seen.add(href)) {
                        val poster = fixUrlNull(card.selectFirst(".wmf-event-poster img")?.attr("src"))
                        results.add(
                            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                                this.posterUrl = poster
                            }
                        )
                    }
                }
            }
        } catch (_: Throwable) {}

        // 2) Official site search (individual fights)
        try {
            val doc = app.get("$mainUrl/search/$encoded", headers = BASE_HEADERS).document
            doc.select("li.video-item-compact, article.ft-bout").forEach { item ->
                val linkEl = item.selectFirst("h2.video-title-compact a, h3.ft-compact-title a, a[href$=.html]")
                    ?: return@forEach
                val href = linkEl.attr("href").trim().takeIf { it.endsWith(".html") } ?: return@forEach
                val fullUrl = fixUrl(href)
                if (!seen.add(fullUrl)) return@forEach
                val title = linkEl.text().trim().ifBlank { return@forEach }
                val poster = fixUrlNull(item.selectFirst("img")?.attr("src"))
                results.add(
                    newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                        this.posterUrl = poster
                    }
                )
            }
        } catch (_: Throwable) {}

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = BASE_HEADERS).document

        // Event page -> Series with Main Card (Season 1) and Prelims (Season 2)
        if (url.contains("/event/")) {
            val title = doc.selectFirst("h1.ft-event-title, h1")?.text()?.trim() ?: return null
            val poster = fixUrlNull(doc.selectFirst(".ft-event-poster img")?.attr("src"))
            val metaText = doc.select(".ft-meta span").joinToString(" • ") { it.text().trim() }
            val promotion = doc.selectFirst(".ft-ev-badge")?.text()?.trim()
            val eventInfo = listOfNotNull(promotion, metaText.takeIf { it.isNotBlank() }).joinToString(" — ")
            val usageHint = "📌 Escolha em Temporadas se deseja ver Main Card ou Prelims, e em Episódios selecione qual luta deseja assistir."
            val plot = listOf(eventInfo, usageHint).filter { it.isNotBlank() }.joinToString("\n\n")

            val mainCardBouts = mutableListOf<Episode>()
            val prelimBouts = mutableListOf<Episode>()
            val earlyPrelimBouts = mutableListOf<Episode>()

            var currentSection = "main"
            val container = doc.selectFirst(".ft-event-wrap, .wmf-event-content, main") ?: doc.body()
            container.select("h2.ft-section-title, article.ft-bout").forEach { el ->
                if (el.tagName().equals("h2", ignoreCase = true)) {
                    val heading = el.text().trim().lowercase()
                    currentSection = when {
                        heading.contains("early") -> "early"
                        heading.contains("prelim") -> "prelim"
                        else -> "main"
                    }
                } else if (el.hasClass("ft-bout")) {
                    val fightLink = el.selectFirst("h3.ft-compact-title a, a.ft-compact-watch, a[href$=.html]")
                        ?.attr("href")?.trim()?.takeIf { it.endsWith(".html") }
                        ?: return@forEach
                    val fightUrl = fixUrl(fightLink)
                    val fightTitle = el.selectFirst("h3.ft-compact-title a")?.text()?.trim()
                        ?: el.selectFirst(".ft-compact-title")?.text()?.trim()
                        ?: return@forEach
                    val boutMeta = el.select(".ft-compact-meta").joinToString(" • ") { it.text().trim() }
                    val rawAvatar = fixUrlNull(el.selectFirst(".ft-compact-avatar img")?.attr("src"))
                    val fightPoster = rawAvatar?.takeUnless { it.contains(".avif", ignoreCase = true) }
                        ?: poster
                        ?: DEFAULT_POSTER

                    val targetList = when (currentSection) {
                        "prelim" -> prelimBouts
                        "early" -> earlyPrelimBouts
                        else -> mainCardBouts
                    }
                    val seasonNum = when (currentSection) {
                        "prelim" -> 2
                        "early" -> 3
                        else -> 1
                    }
                    targetList.add(
                        newEpisode(fightUrl) {
                            this.name = fightTitle
                            this.season = seasonNum
                            this.episode = targetList.size + 1
                            this.posterUrl = fightPoster
                            this.description = boutMeta.takeIf { it.isNotBlank() }
                        }
                    )
                }
            }

            // Order: Main Card first (Season 1), then Prelims (Season 2), then Early Prelims (Season 3)
            val allEpisodes = mutableListOf<Episode>()
            val seasonNames = mutableListOf<SeasonData>()
            if (mainCardBouts.isNotEmpty()) {
                allEpisodes.addAll(mainCardBouts)
                seasonNames.add(SeasonData(1, "Main Card"))
            }
            if (prelimBouts.isNotEmpty()) {
                // If an event only has Prelims (rare), keep its season number aligned with SeasonData
                allEpisodes.addAll(prelimBouts)
                seasonNames.add(SeasonData(2, "Prelims"))
            }
            if (earlyPrelimBouts.isNotEmpty()) {
                allEpisodes.addAll(earlyPrelimBouts)
                seasonNames.add(SeasonData(3, "Early Prelims"))
            }

            val finalPoster = poster
                ?: mainCardBouts.firstOrNull()?.posterUrl
                ?: prelimBouts.firstOrNull()?.posterUrl
                ?: DEFAULT_POSTER
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
                this.posterUrl = finalPoster
                this.plot = plot.ifBlank { null }
                if (seasonNames.isNotEmpty()) {
                    this.seasonNames = seasonNames
                }
            }
        }

        // Individual fight page (opened from search) -> Movie
        val title = doc.selectFirst("h1.video-main-title, h1")?.text()?.trim()
            ?: doc.title().substringBefore("|").trim()
        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".ft-compact-avatar img")?.attr("src")
        )
        val desc = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = desc
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val fightHtml = app.get(data, headers = BASE_HEADERS + ("Referer" to "$mainUrl/")).text
        val embedUrls = EMBED_REGEX.findAll(fightHtml).map { it.value }.distinct().toList()
        if (embedUrls.isEmpty()) return@coroutineScope false

        var foundAny = false
        val iframeUrls = embedUrls.map { embedUrl ->
            async {
                try {
                    val embDoc = app.get(embedUrl, headers = BASE_HEADERS + ("Referer" to data)).document
                    embDoc.select("iframe[src]").mapNotNull { it.attr("src").trim().takeIf { s -> s.startsWith("http") } }
                } catch (_: Throwable) {
                    emptyList()
                }
            }
        }.awaitAll().flatten().distinct()

        iframeUrls.mapIndexed { idx, iframeUrl ->
            async {
                try {
                    val host = java.net.URI(iframeUrl).host?.lowercase()?.removePrefix("www.") ?: ""
                    if (VIDARA_HOSTS.any { host == it || host.endsWith(".$it") }) {
                        if (extractVidara(iframeUrl, "WatchMMAFull Vidara #${idx + 1}", callback)) {
                            foundAny = true
                        }
                    } else if (CONTENT_ST_HOSTS.any { host == it || host.endsWith(".$it") }) {
                        if (extractContentSt(iframeUrl, "WatchMMAFull Content.st #${idx + 1}", callback)) {
                            foundAny = true
                        }
                    } else {
                        val loaded = loadExtractor(iframeUrl, data, subtitleCallback, callback)
                        if (loaded) foundAny = true
                    }
                } catch (_: Throwable) {}
            }
        }.awaitAll()

        foundAny
    }

    private suspend fun extractVidara(
        embedUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uri = java.net.URI(embedUrl)
        val origin = "${uri.scheme}://${uri.host}"
        val filecode = uri.path.trim('/').substringAfterLast('/').takeIf { it.isNotBlank() } ?: return false
        val body = JSONObject().put("filecode", filecode).put("device", "desktop").toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val resp = app.post(
            "$origin/api/stream",
            headers = mapOf(
                "User-Agent" to UA,
                "Content-Type" to "application/json",
                "Origin" to origin,
                "Referer" to embedUrl
            ),
            requestBody = body
        ).text

        val json = JSONObject(resp)
        val streamUrl = json.optString("streaming_url").trim()
        if (!streamUrl.startsWith("http")) return false

        M3u8Helper.generateM3u8(
            source = label,
            streamUrl = streamUrl,
            referer = "$origin/",
            headers = mapOf("Origin" to origin, "Referer" to "$origin/", "User-Agent" to UA)
        ).forEach(callback)
        return true
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun extractRawPlanJson(pcfgRaw: String): String {
        val keyIdx = pcfgRaw.indexOf("\"plan\":")
        if (keyIdx < 0) return "null"
        var i = keyIdx + "\"plan\":".length
        while (i < pcfgRaw.length && pcfgRaw[i].isWhitespace()) i++
        if (i >= pcfgRaw.length) return "null"
        if (pcfgRaw.startsWith("null", i)) return "null"
        if (pcfgRaw[i] != '{') return "null"
        var depth = 0
        var inStr = false
        var esc = false
        val start = i
        while (i < pcfgRaw.length) {
            val c = pcfgRaw[i]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return pcfgRaw.substring(start, i + 1)
                    }
                }
            }
            i++
        }
        return "null"
    }

    private suspend fun extractContentSt(
        embedUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uri = java.net.URI(embedUrl)
        val origin = "${uri.scheme}://${uri.host}"
        val pageResp = app.get(embedUrl, headers = BASE_HEADERS + ("Referer" to "$mainUrl/"))
        val pcfgRaw = PCFG_REGEX.find(pageResp.text)?.groupValues?.getOrNull(1) ?: return false
        val cfg = JSONObject(pcfgRaw)

        var streamUrl = cfg.optString("src").trim()
        if (!streamUrl.startsWith("http")) {
            val id = cfg.optString("id").trim().takeIf { it.isNotBlank() } ?: return false
            val planStr = extractRawPlanJson(pcfgRaw).replace("\\u003c", "<").replace("\\u003e", ">").replace("\\u0026", "&")
            val pd = sha256Hex(planStr)
            val bodyJson = JSONObject()
                .put("bc", "")
                .put("pd", pd)
                .put("pk", cfg.optString("pk", ""))
                .put("pe", cfg.optLong("pe", 0L))
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())

            val keyResp = app.post(
                "$origin/x/k/${URLEncoder.encode(id, "UTF-8")}",
                headers = mapOf(
                    "User-Agent" to UA,
                    "Content-Type" to "application/json",
                    "Origin" to origin,
                    "Referer" to embedUrl
                ),
                cookies = pageResp.cookies,
                requestBody = bodyJson
            ).text
            val resJson = JSONObject(keyResp)
            if (resJson.optBoolean("ok", false)) {
                streamUrl = resJson.optString("src").trim()
            }
        }
        if (!streamUrl.startsWith("http")) return false

        M3u8Helper.generateM3u8(
            source = label,
            streamUrl = streamUrl,
            referer = "$origin/",
            headers = mapOf("Origin" to origin, "Referer" to "$origin/", "User-Agent" to UA)
        ).forEach(callback)
        return true
    }
}
