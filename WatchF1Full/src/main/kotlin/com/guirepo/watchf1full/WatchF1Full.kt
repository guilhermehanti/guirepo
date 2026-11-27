package com.guirepo.watchf1full

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

@CloudstreamPlugin
class WatchF1FullPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(WatchF1Full())
    }
}

class WatchF1Full : MainAPI() {
    override var mainUrl = "https://watchf1full.com"
    override var name = "WatchF1Full"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val usesWebView = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val DEFAULT_POSTER =
            "https://watchf1full.com/uploads/images/2026/08/32365e513609f68d5da404fe22a5a262.png"

        private val BASE_HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        private val VIDARA_HOSTS = setOf("vidara.so", "vidaraa.cc", "oisauh.com")
        private val VIDSONIC_HOSTS = setOf("vidsonic.net")
        private val VIDSONIC_HEX_REGEX = Regex("""const\s+_0x1\s*=\s*['"]([0-9a-fA-F|]+)['"]""")
        private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
        private val DATE_TAIL_REGEX = Regex("""\s*-\s*[A-Za-z]{3,9}\s+\d{1,2},?\s+(19|20)\d{2}\s*$""")

        private data class ParsedReplay(
            val url: String,
            val rawTitle: String,
            val session: String,
            val series: String,
            val gp: String,
            val year: String,
            val dateText: String
        )

        private fun detectSeriesToken(raw: String): String? {
            val cleaned = raw.trim().replace(Regex("""\b(19|20)\d{2}\b"""), "").trim()
            val compact = cleaned.lowercase().replace(Regex("\\s+"), "")
            return when (compact) {
                "f1", "formula1" -> "F1"
                "f2", "formula2" -> "F2"
                "f3", "formula3" -> "F3"
                "f1academy" -> "F1 Academy"
                "motogp" -> "MotoGP"
                "moto2" -> "Moto2"
                "moto3" -> "Moto3"
                "motoe" -> "MotoE"
                "indycar", "indy" -> "IndyCar"
                "indynxt" -> "Indy NXT"
                "nascar", "nascarcup" -> "NASCAR"
                "wec" -> "WEC"
                "wrc" -> "WRC"
                "supercars" -> "Supercars"
                "wsbk", "worldsbk" -> "WorldSBK"
                else -> null
            }
        }

        private fun normalizeGpKey(gp: String): String {
            return gp.lowercase()
                .replace(Regex("""\bgrand\s+prix\b"""), "")
                .replace(Regex("""\bchampionship\b"""), "")
                .replace(Regex("""\bgp\b"""), "")
                .replace(Regex("""\b(19|20)\d{2}\b"""), "")
                .replace(Regex("""[^a-z0-9]+"""), " ")
                .trim()
        }

        private fun parseReplayTitle(rawTitle: String, url: String): ParsedReplay {
            val cleaned = rawTitle.replace(Regex("""\.mp4$""", RegexOption.IGNORE_CASE), "").trim()
            val parts = cleaned.split(Regex("""\s+-\s+""")).map { it.trim() }.filter { it.isNotBlank() }

            val year = YEAR_REGEX.find(cleaned)?.value ?: ""
            val hasDateTail = parts.isNotEmpty() && YEAR_REGEX.containsMatchIn(parts.last())
            val dateText = if (hasDateTail) parts.last() else year
            val coreParts = if (hasDateTail && parts.size >= 3) parts.dropLast(1) else parts

            // Format A: "<Session> - <Series [Year]> - <GP>" (e.g. "RACE - F1 - Spain GP - Sep 13, 2026" or "Race - F1 2024 - Brazilian Grand Prix")
            // Format B: "<Series [Year]> - <Session> - <GP>" (e.g. "Moto2 - Race - Thai GP - February 28, 2026")
            if (coreParts.size >= 3) {
                val s0 = detectSeriesToken(coreParts[0])
                val s1 = detectSeriesToken(coreParts[1])
                if (s1 != null) {
                    val session = coreParts[0]
                    val gp = coreParts.subList(2, coreParts.size).joinToString(" - ").trim()
                    return ParsedReplay(url, cleaned, session, s1, gp, year, dateText)
                }
                if (s0 != null) {
                    val session = coreParts[1]
                    val gp = coreParts.subList(2, coreParts.size).joinToString(" - ").trim()
                    return ParsedReplay(url, cleaned, session, s0, gp, year, dateText)
                }
                // Check if any middle segment contains the series token (e.g. "Pre-Season Testing - Day 3 - F1 2025 Bahrain")
                for (i in coreParts.indices) {
                    val si = detectSeriesToken(coreParts[i])
                    if (si != null) {
                        val session = coreParts.take(i).joinToString(" - ").ifBlank { "Full Replay" }
                        val gp = coreParts.drop(i + 1).joinToString(" - ").ifBlank { coreParts.last() }
                        return ParsedReplay(url, cleaned, session, si, gp, year, dateText)
                    }
                }
            } else if (coreParts.size == 2) {
                val s0 = detectSeriesToken(coreParts[0])
                val s1 = detectSeriesToken(coreParts[1])
                if (s0 != null) {
                    return ParsedReplay(url, cleaned, "Full Replay", s0, DATE_TAIL_REGEX.replace(coreParts[1], "").trim(), year, dateText)
                }
                if (s1 != null) {
                    return ParsedReplay(url, cleaned, coreParts[0], s1, coreParts[0], year, dateText)
                }
            }

            // Fallback: detect series token anywhere in title
            val detectedSeries = when {
                Regex("""\bMotoGP\b""", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) -> "MotoGP"
                Regex("""\bMoto2\b""", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) -> "Moto2"
                Regex("""\bMoto3\b""", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) -> "Moto3"
                Regex("""\b(F1|Formula\s*1)\b""", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) -> "F1"
                Regex("""\b(F2|Formula\s*2)\b""", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) -> "F2"
                Regex("""\b(F3|Formula\s*3)\b""", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) -> "F3"
                else -> "Motorsport"
            }
            val fallbackGp = DATE_TAIL_REGEX.replace(cleaned, "").trim()
            return ParsedReplay(url, cleaned, "Full Replay", detectedSeries, fallbackGp, year, dateText)
        }

        private fun sessionSortWeight(session: String): Int {
            val s = session.lowercase()
            return when {
                s.contains("press conference") -> 5
                s.contains("1st practice") || s.contains("practice 1") || s.contains("practice one") || s.contains("fp1") || s.contains("nr. 1") -> 10
                s.contains("2nd practice") || s.contains("practice 2") || s.contains("practice two") || s.contains("fp2") || s.contains("nr. 2") -> 20
                s.contains("3rd practice") || s.contains("practice 3") || s.contains("practice three") || s.contains("fp3") || s.contains("nr. 3") -> 30
                s.contains("practice") -> 35
                s.contains("sprint") && (s.contains("qual") || s.contains("shootout")) -> 40
                s.contains("qualifying") || s.contains("quali") -> 50
                s.contains("sprint") -> 60
                s.contains("warm up") || s.contains("warm-up") || s.contains("warmup") -> 70
                s.contains("pre-race") || s.contains("pre race") || s.contains("build") || s.contains("grand prix sunday") -> 75
                s.contains("race") -> 80
                s.contains("post-race") || s.contains("post race") || s.contains("chequered") || s.contains("highlights") || s.contains("paddock") -> 90
                else -> 55
            }
        }
    }

    override val mainPage = mainPageOf(
        "latest:all" to "Últimos GPs (Todas as Categorias)",
        "search:f1:F1" to "Fórmula 1 (F1)",
        "search:f2:F2" to "Fórmula 2 (F2)",
        "search:f3:F3" to "Fórmula 3 (F3)",
        "search:motogp:MotoGP" to "MotoGP",
        "search:moto2:Moto2" to "Moto2",
        "search:moto3:Moto3" to "Moto3"
    )

    private fun extractReplaysFromDoc(doc: Document): List<ParsedReplay> {
        val seen = HashSet<String>()
        val list = mutableListOf<ParsedReplay>()
        doc.select("a.rr-video-title, ul.rr-video-list a[href$=.html]").forEach { a ->
            val href = a.attr("href").trim()
            if (!href.endsWith(".html") || href.contains("browse-") || href.contains("contact")) return@forEach
            val fullUrl = fixUrl(href)
            if (!seen.add(fullUrl)) return@forEach
            val title = a.attr("title").trim().ifBlank { a.text().trim() }
            if (title.isBlank()) return@forEach
            list.add(parseReplayTitle(title, fullUrl))
        }
        return list
    }

    private fun buildGpUrl(series: String, gp: String, year: String, sampleUrl: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "$mainUrl/gp?series=${enc(series)}&gp=${enc(gp)}&year=${enc(year)}&sample=${enc(sampleUrl)}"
    }

    private fun groupIntoGpCards(
        replays: List<ParsedReplay>,
        seriesFilter: String? = null
    ): List<SearchResponse> {
        val grouped = LinkedHashMap<String, MutableList<ParsedReplay>>()
        for (r in replays) {
            if (seriesFilter != null && !r.series.equals(seriesFilter, ignoreCase = true)) continue
            // Strictly separate series (F1 != F2 != F3, MotoGP != Moto2 != Moto3) + GP + Year!
            val key = "${r.series.lowercase()}|${normalizeGpKey(r.gp)}|${r.year}"
            grouped.getOrPut(key) { mutableListOf() }.add(r)
        }
        return grouped.values.map { sessions ->
            val first = sessions.first()
            val displayGp = first.gp.ifBlank { first.rawTitle }
            val yearSuffix = if (first.year.isNotBlank() && !displayGp.contains(first.year)) " (${first.year})" else ""
            val cardTitle = "${first.series} • $displayGp$yearSuffix"
            val gpUrl = buildGpUrl(first.series, displayGp, first.year, first.url)
            newTvSeriesSearchResponse(cardTitle, gpUrl, TvType.TvSeries) {
                this.posterUrl = DEFAULT_POSTER
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = coroutineScope {
        val replays = mutableListOf<ParsedReplay>()
        var seriesFilter: String? = null

        when {
            request.data.startsWith("latest:") -> {
                val filter = request.data.removePrefix("latest:")
                if (filter != "all") seriesFilter = filter
                // Fetch 3 pages in parallel so multiple GPs appear cleanly per load
                val basePage = (page - 1) * 3 + 1
                val docs = (basePage until basePage + 3).map { p ->
                    async {
                        try {
                            app.get("$mainUrl/newvideos.html?&page=$p", headers = BASE_HEADERS).document
                        } catch (_: Throwable) { null }
                    }
                }.awaitAll().filterNotNull()
                docs.forEach { replays.addAll(extractReplaysFromDoc(it)) }
            }
            request.data.startsWith("search:") -> {
                val segs = request.data.split(":")
                val term = segs.getOrNull(1) ?: "f1"
                seriesFilter = segs.getOrNull(2)
                // Fetch 3 search pages in parallel (~72 sessions -> ~10-18 distinct GPs per load!)
                val basePage = (page - 1) * 3 + 1
                val docs = (basePage until basePage + 3).map { p ->
                    async {
                        try {
                            val u = if (p == 1) "$mainUrl/search/$term" else "$mainUrl/search/$term/$p"
                            app.get(u, headers = BASE_HEADERS).document
                        } catch (_: Throwable) { null }
                    }
                }.awaitAll().filterNotNull()
                docs.forEach { replays.addAll(extractReplaysFromDoc(it)) }
            }
        }

        val items = groupIntoGpCards(replays, seriesFilter)
        newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = replays.size >= 20
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val doc = app.get("$mainUrl/search/$encoded", headers = BASE_HEADERS).document
        val replays = extractReplaysFromDoc(doc)
        return groupIntoGpCards(replays)
    }

    private fun queryParams(url: String): Map<String, String> {
        val q = URI(url).rawQuery ?: return emptyMap()
        return q.split("&").mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null
            else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }.toMap()
    }

    override suspend fun load(url: String): LoadResponse? {
        // Grouped GP Series
        if (url.contains("/gp?")) {
            val params = queryParams(url)
            val targetSeries = params["series"] ?: return null
            val targetGp = params["gp"] ?: return null
            val targetYear = params["year"] ?: ""
            val sampleUrl = params["sample"] ?: ""
            val targetGpKey = normalizeGpKey(targetGp)

            val collected = LinkedHashMap<String, ParsedReplay>()

            // 1) Search by GP name (strip "GP" to also catch "San Marino" without "GP" suffix)
            val gpSearchTerm = targetGp.replace(Regex("""\bGP\b""", RegexOption.IGNORE_CASE), "").trim().ifBlank { targetGp }
            val searchEncoded = URLEncoder.encode(gpSearchTerm, "UTF-8").replace("+", "%20")
            try {
                val searchDoc = app.get("$mainUrl/search/$searchEncoded", headers = BASE_HEADERS).document
                extractReplaysFromDoc(searchDoc).forEach { r ->
                    if (r.series.equals(targetSeries, ignoreCase = true) &&
                        normalizeGpKey(r.gp) == targetGpKey &&
                        (targetYear.isBlank() || r.year == targetYear)
                    ) {
                        collected[r.url] = r
                    }
                }
            } catch (_: Throwable) {}

            // 2) Also check first 2 pages of latest videos (in case search index lags recent uploads)
            try {
                for (p in 1..2) {
                    val doc = app.get("$mainUrl/newvideos.html?&page=$p", headers = BASE_HEADERS).document
                    extractReplaysFromDoc(doc).forEach { r ->
                        if (r.series.equals(targetSeries, ignoreCase = true) &&
                            normalizeGpKey(r.gp) == targetGpKey &&
                            (targetYear.isBlank() || r.year == targetYear)
                        ) {
                            collected[r.url] = r
                        }
                    }
                }
            } catch (_: Throwable) {}

            // 3) Guarantee at least the sample session is present
            if (collected.isEmpty() && sampleUrl.startsWith("http")) {
                try {
                    val sampleDoc = app.get(sampleUrl, headers = BASE_HEADERS).document
                    val sampleTitle = sampleDoc.selectFirst("h1[itemprop=name], h1")?.text()?.trim() ?: targetGp
                    collected[sampleUrl] = parseReplayTitle(sampleTitle, sampleUrl)
                } catch (_: Throwable) {}
            }

            // Read rich GP metadata from the Race or first session
            val sortedSessions = collected.values.sortedWith(
                compareBy<ParsedReplay> { sessionSortWeight(it.session) }.thenBy { it.rawTitle }
            )
            val infoSourceUrl = sortedSessions.lastOrNull()?.url ?: sampleUrl
            var plotText = "$targetSeries • $targetGp ${targetYear}".trim()
            if (infoSourceUrl.startsWith("http")) {
                try {
                    val infoDoc = app.get(infoSourceUrl, headers = BASE_HEADERS).document
                    val infoPairs = infoDoc.select(".rr-f1-info-item").mapNotNull { item ->
                        val k = item.selectFirst("span")?.text()?.trim()
                        val v = item.selectFirst("strong")?.text()?.trim()
                        if (!k.isNullOrBlank() && !v.isNullOrBlank()) "$k: $v" else null
                    }
                    if (infoPairs.isNotEmpty()) {
                        plotText = infoPairs.joinToString(" • ")
                    }
                } catch (_: Throwable) {}
            }

            val episodes = sortedSessions.mapIndexed { index, r ->
                newEpisode(r.url) {
                    this.name = "${r.session} (${r.dateText.ifBlank { r.series }})"
                    this.season = 1
                    this.episode = index + 1
                    this.posterUrl = DEFAULT_POSTER
                    this.description = r.rawTitle
                }
            }

            val yearSuffix = if (targetYear.isNotBlank() && !targetGp.contains(targetYear)) " ($targetYear)" else ""
            val usageHint = "📌 Escolha em Episódios qual sessão deseja assistir (Treino Livre / Practice, Classificação / Qualifying, Sprint, Corrida / Race, etc.). Ao dar Play, aguarde ~11s enquanto os servidores são desbloqueados."
            val fullPlot = listOf(plotText, usageHint).filter { it.isNotBlank() }.joinToString("\n\n")
            return newTvSeriesLoadResponse("$targetSeries • $targetGp$yearSuffix", url, TvType.TvSeries, episodes) {
                this.posterUrl = DEFAULT_POSTER
                this.plot = fullPlot
                this.year = targetYear.toIntOrNull()
                this.seasonNames = listOf(SeasonData(1, "$targetSeries Sessions"))
            }
        }

        // Direct single replay page fallback
        val doc = app.get(url, headers = BASE_HEADERS).document
        val title = doc.selectFirst("h1[itemprop=name], h1")?.text()?.trim() ?: return null
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = DEFAULT_POSTER
        }
    }

    private data class LkssPending(
        val label: String,
        val lkssUrl: String,
        val actionUrl: String,
        val token: String,
        val adFormData: String,
        val cookies: Map<String, String>
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        val doc = app.get(data, headers = BASE_HEADERS + ("Referer" to "$mainUrl/")).document
        val buttons = doc.select("a.rr-direct-btn[href]").mapNotNull { a ->
            val href = a.attr("href").trim().takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val label = a.selectFirst("strong")?.text()?.trim().orEmpty().ifBlank { "Server" }
            label to href
        }.distinctBy { it.second }

        if (buttons.isEmpty()) return@coroutineScope false

        val lkssButtons = buttons.filter { it.second.contains("lkss.site", ignoreCase = true) }
        val directButtons = buttons.filterNot { it.second.contains("lkss.site", ignoreCase = true) }

        val resolvedUrls = mutableListOf<Pair<String, String>>()
        resolvedUrls.addAll(directButtons)

        // Resolve ALL lkss.site redirectors in parallel with ONE single 10.6s wait!
        if (lkssButtons.isNotEmpty()) {
            val pending = lkssButtons.map { (label, lkUrl) ->
                async {
                    try {
                        val resp = app.get(lkUrl, headers = BASE_HEADERS + ("Referer" to data))
                        val form = resp.document.selectFirst("form#go-link, form[action*='link/go']")
                            ?: return@async null
                        val actionRaw = form.attr("action").trim().ifBlank { "/link/go" }
                        val actionUrl = if (actionRaw.startsWith("http")) actionRaw else "https://lkss.site$actionRaw"
                        val token = form.selectFirst("input[name=_token]")?.attr("value").orEmpty()
                        val adFormData = form.selectFirst("input[name=ad_form_data]")?.attr("value").orEmpty()
                        if (token.isBlank() || adFormData.isBlank()) return@async null
                        LkssPending(label, lkUrl, actionUrl, token, adFormData, resp.cookies)
                    } catch (_: Throwable) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()

            if (pending.isNotEmpty()) {
                delay(10_600L)
                val unlocked = pending.map { p ->
                    async {
                        try {
                            val postResp = app.post(
                                p.actionUrl,
                                headers = mapOf(
                                    "User-Agent" to UA,
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "Referer" to p.lkssUrl,
                                    "Origin" to "https://lkss.site"
                                ),
                                cookies = p.cookies,
                                data = mapOf(
                                    "_token" to p.token,
                                    "ad_form_data" to p.adFormData
                                )
                            ).text
                            val json = JSONObject(postResp)
                            val targetUrl = json.optString("url").trim()
                            if (targetUrl.startsWith("http")) p.label to targetUrl else null
                        } catch (_: Throwable) {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
                resolvedUrls.addAll(unlocked)
            }
        }

        if (resolvedUrls.isEmpty()) return@coroutineScope false

        var foundAny = false
        resolvedUrls.map { (label, targetUrl) ->
            async {
                try {
                    val host = URI(targetUrl).host?.lowercase()?.removePrefix("www.") ?: ""
                    if (VIDARA_HOSTS.any { host == it || host.endsWith(".$it") }) {
                        if (extractVidara(targetUrl, "WatchF1Full $label (Vidara)", callback)) {
                            foundAny = true
                        }
                    } else if (VIDSONIC_HOSTS.any { host == it || host.endsWith(".$it") }) {
                        if (extractVidSonic(targetUrl, "WatchF1Full $label (VidSonic)", callback)) {
                            foundAny = true
                        }
                    } else {
                        val ok = loadExtractor(targetUrl, data, subtitleCallback, callback)
                        if (ok) foundAny = true
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
        val uri = URI(embedUrl)
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

    private suspend fun extractVidSonic(
        embedUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uri = URI(embedUrl)
        val origin = "${uri.scheme}://${uri.host}"
        val html = app.get(embedUrl, headers = BASE_HEADERS + ("Referer" to "$mainUrl/")).text
        val encoded = VIDSONIC_HEX_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return false
        val cleanHex = encoded.replace("|", "")
        if (cleanHex.length < 16 || cleanHex.length % 2 != 0) return false

        val decodedChars = StringBuilder(cleanHex.length / 2)
        var i = 0
        while (i < cleanHex.length) {
            val byteVal = cleanHex.substring(i, i + 2).toIntOrNull(16) ?: return false
            decodedChars.append(byteVal.toChar())
            i += 2
        }
        val streamUrl = decodedChars.reverse().toString().trim()
        if (!streamUrl.startsWith("http")) return false

        val headers = mapOf("Origin" to origin, "Referer" to "$origin/", "User-Agent" to UA)
        if (streamUrl.contains(".m3u8", ignoreCase = true)) {
            M3u8Helper.generateM3u8(
                source = label,
                streamUrl = streamUrl,
                referer = "$origin/",
                headers = headers
            ).forEach(callback)
        } else {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$origin/"
                    this.headers = headers
                    this.quality = Qualities.P1080.value
                }
            )
        }
        return true
    }
}
