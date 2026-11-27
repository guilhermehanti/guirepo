package com.guirepo.footballia

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import java.net.URLEncoder

@CloudstreamPlugin
class FootballiaPlugin : Plugin() {
    override fun load(context: Context) {
        FootballiaAuth.init(context.applicationContext)
        registerMainAPI(Footballia())

        openSettings = { ctx ->
            Handler(Looper.getMainLooper()).post {
                showLoginDialog(ctx)
            }
        }
    }

    private fun showLoginDialog(ctx: Context) {
        val (savedEmail, savedPass) = FootballiaAuth.getCredentials()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * ctx.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val info = TextView(ctx).apply {
            text = "O Footballia exige uma conta gratuita (footballia.net) para assistir aos jogos completos.\nSeus dados ficam salvos apenas neste aparelho."
            textSize = 13f
        }
        container.addView(info)

        val emailInput = EditText(ctx).apply {
            hint = "E-mail da sua conta Footballia"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setText(savedEmail)
            isSingleLine = true
        }
        container.addView(emailInput)

        val passInput = EditText(ctx).apply {
            hint = "Senha da sua conta Footballia"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(savedPass)
            isSingleLine = true
        }
        container.addView(passInput)

        AlertDialog.Builder(ctx)
            .setTitle("Login Footballia")
            .setView(container)
            .setPositiveButton("Salvar") { _, _ ->
                val email = emailInput.text?.toString()?.trim().orEmpty()
                val pass = passInput.text?.toString().orEmpty()
                FootballiaAuth.saveCredentials(email, pass)
                Toast.makeText(
                    ctx,
                    if (email.isNotBlank() && pass.isNotBlank()) "Credenciais do Footballia salvas!"
                    else "Credenciais limpas.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton("Limpar") { _, _ ->
                FootballiaAuth.clearAll()
                Toast.makeText(ctx, "Login do Footballia removido.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

object FootballiaAuth {
    private const val PREFS = "guirepo_footballia_prefs"
    private const val KEY_EMAIL = "email"
    private const val KEY_PASS = "password"
    private const val KEY_SESSION = "session_cookie"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedCookies: Map<String, String> = emptyMap()

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        val saved = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SESSION, null)
        if (!saved.isNullOrBlank()) {
            cachedCookies = parseCookieString(saved)
        }
    }

    fun getCredentials(): Pair<String, String> {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return "" to ""
        return (prefs.getString(KEY_EMAIL, "") ?: "") to (prefs.getString(KEY_PASS, "") ?: "")
    }

    fun saveCredentials(email: String, password: String) {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putString(KEY_EMAIL, email)
            .putString(KEY_PASS, password)
            .remove(KEY_SESSION)
            .apply()
        cachedCookies = emptyMap()
    }

    fun clearAll() {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        prefs.edit().clear().apply()
        cachedCookies = emptyMap()
    }

    fun getSessionCookies(): Map<String, String> = cachedCookies

    fun saveSessionCookies(cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        cachedCookies = cookies
        val encoded = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_SESSION, encoded)
            ?.apply()
    }

    private fun parseCookieString(raw: String): Map<String, String> {
        return raw.split(";").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null
            else part.substring(0, idx).trim() to part.substring(idx + 1).trim()
        }.toMap()
    }
}

class Footballia : MainAPI() {
    override var mainUrl = "https://footballia.net"
    override var name = "Footballia"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val usesWebView = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val BASE_HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        private val NEW_VIDEO_B64_REGEX = Regex(
            """new\s+Video\s*\(\s*["']([^"']+)["']\s*\)""",
            RegexOption.IGNORE_CASE
        )
        private val VIDEO_MP4_REGEX = Regex(
            """https?://videos\d*\.footballia\.net/uploads/video/source_file/[^"'\s\\<>]+?\.(?:mp4|m3u8)(?:\?[^"'\s\\<>]*)?""",
            RegexOption.IGNORE_CASE
        )
        private val GENERIC_VIDEO_REGEX = Regex(
            """["'](https?://[^"'\s\\]+?\.(?:mp4|m3u8)(?:\?[^"'\s\\]*)?)["']""",
            RegexOption.IGNORE_CASE
        )
    }

    override val mainPage = mainPageOf(
        "home:latest" to "Últimos Jogos Adicionados",
        "$mainUrl/competitions/world-cup" to "Copa do Mundo",
        "$mainUrl/competitions/champions-league" to "Champions League",
        "$mainUrl/competitions/copa-libertadores" to "Copa Libertadores",
        "$mainUrl/competitions/campeonato-brasileiro" to "Campeonato Brasileiro",
        "$mainUrl/competitions/copa-do-brasil" to "Copa do Brasil",
        "$mainUrl/competitions/campeonato-paulista" to "Campeonato Paulista",
        "$mainUrl/competitions/premier-league" to "Premier League",
        "$mainUrl/competitions/liga-1-division" to "La Liga",
        "$mainUrl/competitions/serie-a" to "Serie A (Itália)",
        "$mainUrl/competitions/bundesliga" to "Bundesliga"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "home:latest") {
            if (page > 1) return newHomePageResponse(request.name, emptyList(), false)
            val doc = app.get(mainUrl, headers = BASE_HEADERS).document
            val seen = HashSet<String>()
            val items = mutableListOf<SearchResponse>()

            doc.select("a[href^=/matches/]").forEach { a ->
                val href = a.attr("href").trim()
                if (!href.startsWith("/matches/")) return@forEach
                val fullUrl = fixUrl(href)
                if (!seen.add(fullUrl)) return@forEach
                val rawText = a.text().replace(Regex("\\s+"), " ").trim()
                if (rawText.length < 4) return@forEach
                val img = fixUrlNull(a.selectFirst("img")?.attr("src"))
                    ?: fixUrlNull(a.parent()?.selectFirst("img")?.attr("src"))
                items.add(
                    newMovieSearchResponse(rawText, fullUrl, TvType.Movie) {
                        this.posterUrl = img
                    }
                )
            }
            return newHomePageResponse(
                list = HomePageList(request.name, items, isHorizontalImages = true),
                hasNext = false
            )
        }

        val pageUrl = if (page <= 1) request.data else "${request.data}?page=$page"
        val doc = app.get(pageUrl, headers = BASE_HEADERS).document
        val items = parseMatchTableRows(doc)
        val hasNext = doc.select(".pagination a[rel=next], .pagination li.next a").isNotEmpty()
        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = hasNext && items.isNotEmpty()
        )
    }

    private fun parseMatchTableRows(doc: org.jsoup.nodes.Document): List<SearchResponse> {
        val seen = HashSet<String>()
        val items = mutableListOf<SearchResponse>()
        doc.select("table.table-search tbody tr").forEach { row ->
            val link = row.selectFirst("td.match a[href^=/matches/]") ?: return@forEach
            val fullUrl = fixUrl(link.attr("href").trim())
            if (!seen.add(fullUrl)) return@forEach

            val teamNames = link.select("span[itemprop=name]").map { it.text().trim() }.filter { it.isNotBlank() }
            val matchup = if (teamNames.size >= 2) "${teamNames[0]} vs ${teamNames[1]}"
            else link.text().replace(Regex("\\s+"), " ").trim()

            val season = row.selectFirst("td.season")?.text()?.trim().orEmpty()
            val stage = row.selectFirst("td.stage")?.text()?.trim().orEmpty()
            val date = row.selectFirst("td.playing_date")?.text()?.trim().orEmpty()
            val suffix = listOf(season, stage.takeIf { it.isNotBlank() && !season.contains(it) })
                .filterNotNull()
                .filter { it.isNotBlank() }
                .joinToString(" • ")

            val title = if (suffix.isNotBlank()) "$matchup ($suffix)" else matchup
            val logo = fixUrlNull(link.selectFirst("img")?.attr("src"))
                ?.replace("/small_", "/medium_")

            items.add(
                newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                    this.posterUrl = logo
                    this.year = Regex("""\b(19|20)\d{2}\b""").find(date.ifBlank { season })?.value?.toIntOrNull()
                }
            )
        }
        return items
    }

    // Search strictly by Team (as requested)
    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(q, "UTF-8")

        val jsonText = app.get(
            "$mainUrl/search_by_team?name=$encoded",
            headers = BASE_HEADERS + mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Referer" to "$mainUrl/"
            )
        ).text.trim()

        if (!jsonText.startsWith("[")) return emptyList()

        val arr = JSONArray(jsonText)
        val results = mutableListOf<SearchResponse>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val slug = obj.optString("slug").trim().takeIf { it.isNotBlank() } ?: continue
            val teamName = obj.optString("name").trim().ifBlank { obj.optString("value").trim() }
            if (teamName.isBlank()) continue
            val logo = fixUrlNull(
                obj.optString("logo_medium").takeIf { it.isNotBlank() }
                    ?: obj.optString("logo_thumb").takeIf { it.isNotBlank() }
            )
            results.add(
                newTvSeriesSearchResponse(teamName, "$mainUrl/teams/$slug", TvType.TvSeries) {
                    this.posterUrl = logo
                }
            )
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        // Team page -> Series containing all matches of that team as episodes
        if (url.contains("/teams/")) {
            val firstDoc = app.get(url, headers = BASE_HEADERS).document
            val rawTeamTitle = firstDoc.selectFirst("h1, .team-name")?.text()?.trim()
                ?: url.substringAfterLast("/").replace("-", " ").replaceFirstChar { it.uppercase() }
            val teamTitle = rawTeamTitle.replace(Regex("""\s+full\s+matches$""", RegexOption.IGNORE_CASE), "").trim()
                .ifBlank { rawTeamTitle }

            // Find THIS team's own logo (not the first opponent in the match table!):
            // 1) Exact/partial match on img[alt] == teamTitle
            // 2) Fallback: logo URL that appears most frequently across all rows (the team itself appears in every row!)
            val allTableLogos = firstDoc.select("table.table-search tbody tr td.match img[src*='/uploads/team/logo/']")
            val matchedByAlt = allTableLogos.firstOrNull { img ->
                val alt = img.attr("alt").trim()
                alt.equals(teamTitle, ignoreCase = true) ||
                    alt.contains(teamTitle, ignoreCase = true) ||
                    teamTitle.contains(alt, ignoreCase = true)
            }?.attr("src")
            val mostFrequentLogo = allTableLogos
                .mapNotNull { it.attr("src").trim().takeIf { s -> s.isNotBlank() } }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key
            val teamLogo = fixUrlNull(matchedByAlt ?: mostFrequentLogo)
                ?.replace("/small_", "/medium_")

            val episodes = mutableListOf<Episode>()
            val seen = HashSet<String>()

            fun collectRows(doc: org.jsoup.nodes.Document) {
                doc.select("table.table-search tbody tr").forEach { row ->
                    val link = row.selectFirst("td.match a[href^=/matches/]") ?: return@forEach
                    val matchUrl = fixUrl(link.attr("href").trim())
                    if (!seen.add(matchUrl)) return@forEach

                    val teamNames = link.select("span[itemprop=name]").map { it.text().trim() }.filter { it.isNotBlank() }
                    val matchup = if (teamNames.size >= 2) "${teamNames[0]} vs ${teamNames[1]}"
                    else link.text().replace(Regex("\\s+"), " ").trim()

                    val date = row.selectFirst("td.playing_date")?.text()?.trim().orEmpty()
                    val comp = row.selectFirst("td.competition")?.text()?.trim().orEmpty()
                    val stage = row.selectFirst("td.stage")?.text()?.trim().orEmpty()
                    val season = row.selectFirst("td.season")?.text()?.trim().orEmpty()
                    val subtitle = listOf(comp, season, stage, date).filter { it.isNotBlank() }.joinToString(" • ")

                    val epLogo = fixUrlNull(link.selectFirst("img")?.attr("src"))
                        ?.replace("/small_", "/medium_") ?: teamLogo

                    episodes.add(
                        newEpisode(matchUrl) {
                            this.name = if (season.isNotBlank()) "$matchup ($season)" else matchup
                            this.season = 1
                            this.episode = episodes.size + 1
                            this.posterUrl = epLogo
                            this.description = subtitle.takeIf { it.isNotBlank() }
                        }
                    )
                }
            }

            collectRows(firstDoc)
            // Fetch up to 2 extra pages (total ~120 matches) so browsing stays fast
            var hasNext = firstDoc.select(".pagination a[rel=next], .pagination li.next a").isNotEmpty()
            var p = 2
            while (hasNext && p <= 3) {
                try {
                    val nextDoc = app.get("$url?page=$p", headers = BASE_HEADERS).document
                    val before = episodes.size
                    collectRows(nextDoc)
                    hasNext = episodes.size > before &&
                        nextDoc.select(".pagination a[rel=next], .pagination li.next a").isNotEmpty()
                } catch (_: Throwable) {
                    break
                }
                p++
            }

            val loginHint = if (FootballiaAuth.getCredentials().first.isBlank()) {
                "\n\n⚙️ Para assistir: abra Extensões → GuiRepo → Footballia → Configurações (⚙️) e informe seu e-mail e senha gratuitos do footballia.net."
            } else ""

            return newTvSeriesLoadResponse(teamTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = teamLogo
                this.plot = "Partidas históricas completas de $teamTitle no Footballia.${loginHint}"
                this.seasonNames = listOf(SeasonData(1, "Partidas"))
            }
        }

        // Single Match page -> Movie
        val doc = app.get(url, headers = BASE_HEADERS, cookies = FootballiaAuth.getSessionCookies()).document
        val title = doc.selectFirst("h1, .match-title")?.text()?.replace(Regex("\\s+"), " ")?.trim()
            ?: doc.title().substringBefore("|").trim()
        val logo = fixUrlNull(doc.selectFirst("img[src*='/uploads/team/logo/']")?.attr("src"))
            ?.replace("/small_", "/medium_")
        val comp = doc.selectFirst(".competition, .match-competition, h2")?.text()?.trim().orEmpty()
        val loginHint = if (FootballiaAuth.getCredentials().first.isBlank()) {
            "⚙️ Para assistir no Footballia, configure sua conta gratuita em Extensões → GuiRepo → Footballia → Configurações (⚙️)."
        } else {
            "Jogo completo no Footballia."
        }
        val plot = listOf(comp, loginHint).filter { it.isNotBlank() }.joinToString("\n\n")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = logo
            this.plot = plot
        }
    }

    private suspend fun ensureLoggedIn(forceRefresh: Boolean = false): Map<String, String>? {
        val current = FootballiaAuth.getSessionCookies()
        if (!forceRefresh && current.isNotEmpty()) return current

        val (email, password) = FootballiaAuth.getCredentials()
        if (email.isBlank() || password.isBlank()) {
            showToast(
                "Footballia: configure seu e-mail e senha gratuitos em Extensões → GuiRepo → Footballia → ⚙️",
                Toast.LENGTH_LONG
            )
            return null
        }

        val signInUrl = "$mainUrl/users/sign_in?locale=en"
        val getResp = app.get(signInUrl, headers = BASE_HEADERS)
        val token = getResp.document.selectFirst("form#new_user input[name=authenticity_token]")
            ?.attr("value")?.trim()
            ?: getResp.document.selectFirst("meta[name=csrf-token]")?.attr("content")?.trim()
            ?: return null

        val postResp = app.post(
            signInUrl,
            headers = BASE_HEADERS + mapOf(
                "Origin" to mainUrl,
                "Referer" to signInUrl
            ),
            cookies = getResp.cookies,
            data = mapOf(
                "utf8" to "✓",
                "authenticity_token" to token,
                "user[email]" to email,
                "user[password]" to password,
                "user[remember_me]" to "1",
                "commit" to "Sign in"
            ),
            allowRedirects = false
        )

        val mergedCookies = getResp.cookies.toMutableMap().apply { putAll(postResp.cookies) }
        // Rails Devise redirects (302) to / on successful sign-in, or returns 200 on invalid credentials
        if (postResp.code !in 300..399) {
            showToast("Footballia: login inválido. Verifique e-mail e senha em ⚙️.", Toast.LENGTH_LONG)
            return null
        }

        FootballiaAuth.saveSessionCookies(mergedCookies)
        return mergedCookies
    }

    private fun extractVideoUrls(html: String): List<String> {
        val decodedFromNewVideo = NEW_VIDEO_B64_REGEX.findAll(html).mapNotNull { match ->
            try {
                val cleanB64 = match.groupValues[1]
                    .replace("\\n", "")
                    .replace("\\r", "")
                    .replace(Regex("\\s+"), "")
                val decoded = String(Base64.decode(cleanB64, Base64.DEFAULT), Charsets.UTF_8).trim()
                decoded.takeIf { it.startsWith("http", ignoreCase = true) }
            } catch (_: Throwable) {
                null
            }
        }.distinct().toList()
        if (decodedFromNewVideo.isNotEmpty()) return decodedFromNewVideo

        val unescaped = html.replace("\\/", "/")
        val primary = VIDEO_MP4_REGEX.findAll(unescaped).map { it.value }.distinct().toList()
        if (primary.isNotEmpty()) return primary
        return GENERIC_VIDEO_REGEX.findAll(unescaped)
            .map { it.groupValues[1] }
            .filter { it.contains("footballia", ignoreCase = true) }
            .distinct()
            .toList()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var cookies = ensureLoggedIn(forceRefresh = false) ?: return false
        var html = app.get(data, headers = BASE_HEADERS + ("Referer" to "$mainUrl/"), cookies = cookies).text
        var videoUrls = extractVideoUrls(html)

        // Session cookie expired? Re-login once and retry
        if (videoUrls.isEmpty()) {
            cookies = ensureLoggedIn(forceRefresh = true) ?: return false
            html = app.get(data, headers = BASE_HEADERS + ("Referer" to "$mainUrl/"), cookies = cookies).text
            videoUrls = extractVideoUrls(html)
        }

        if (videoUrls.isEmpty()) return false

        videoUrls.forEachIndexed { idx, videoUrl ->
            val partLabel = if (videoUrls.size > 1) "Footballia (Parte ${idx + 1})" else "Footballia (Jogo Completo)"
            val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = partLabel,
                    url = videoUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }
}
