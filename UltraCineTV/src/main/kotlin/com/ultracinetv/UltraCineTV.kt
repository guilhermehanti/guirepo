package com.ultracinetv

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.net.URLEncoder

@CloudstreamPlugin
class UltraCineTVPlugin : Plugin() {
    private var http: AbyssHttp? = null
    private var proxy: AbyssProxy? = null
    override fun load(context: Context) {
        val upstream = AbyssHttp()
        val server = AbyssProxy(upstream::fetch)
        http = upstream; proxy = server
        val api = UltraCineTV(server)
        registerMainAPI(api)
    }
    override fun beforeUnload() { proxy?.close(); http?.close(); proxy = null; http = null }
}

class UltraCineTV internal constructor(private val proxy: AbyssProxy) : MainAPI() {
    override var mainUrl = "https://www.ultracinetv.lat"
    override var name = "UltraCineTV"
    override var lang = "pt-br"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val mainPage = mainPageOf("$mainUrl/filmes/" to "Filmes", "$mainUrl/series/" to "Séries")

    private fun UltraCatalog.Item.response(): SearchResponse = if (series) {
        newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
    } else newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        val next = doc.select("a[href]").any {
            it.absUrl("href").trimEnd('/') == "${request.data}page/${page + 1}".trimEnd('/')
        }
        return newHomePageResponse(request.name, UltraCatalog.items(doc).map { it.response() }, next)
    }

    override suspend fun search(query: String): List<SearchResponse> = UltraCatalog.items(
        app.get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}").document
    ).map { it.response() }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.entry-title")?.text() ?: throw ErrorLoadingException("Título não encontrado")
        val panel = doc.selectFirst("iframe[src*=painel-aso]")?.absUrl("src")
            ?: throw ErrorLoadingException("Player não encontrado")
        val poster = doc.selectFirst("article.single img")?.let { img ->
            val raw = img.attr("data-src").ifBlank { img.attr("src") }.trim()
            when {
                raw.startsWith("//") -> "https:$raw"
                raw.isNotBlank() -> fixUrl(raw)
                else -> null
            }
        }
        val plot = doc.selectFirst(".description")?.text()
        if (url.contains("/serie/")) {
            val base = URI(panel).let { "${it.scheme}://${it.authority}" }
            val rawEpisodes = UltraCatalog.episodes(app.get(panel, referer = url).document, base)
            // Group Dublado + Legendado for the same (season, episode) into ONE episode so both appear in the same player
            val grouped = linkedMapOf<Pair<Int?, Int?>, MutableList<UltraCatalog.Episode>>()
            for (ep in rawEpisodes) {
                grouped.getOrPut(ep.season to ep.number) { mutableListOf() }.add(ep)
            }
            val episodes = grouped.entries.map { (key, list) ->
                val (s, n) = key
                val combinedData = list.distinctBy { it.url }.joinToString("||") { ep ->
                    val audio = ep.audio.trim()
                    if (audio.isNotBlank()) "${ep.url}#audio=$audio" else ep.url
                }
                val audios = list.map { it.audio.trim() }.filter { it.isNotBlank() }.distinct()
                val epLabel = if (audios.size == 1) {
                    "Episódio ${n ?: ""} (${audios.first()})"
                } else {
                    "Episódio ${n ?: ""}"
                }
                newEpisode(combinedData) {
                    season = s
                    episode = n
                    name = epLabel.trim()
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { posterUrl = poster; this.plot = plot }
        }
        return newMovieLoadResponse(title, url, TvType.Movie, panel) { posterUrl = poster; this.plot = plot }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
                                   callback: (ExtractorLink) -> Unit): Boolean {
        val targets = data.split("||").mapNotNull { raw ->
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val idx = trimmed.indexOf("#audio=")
            if (idx > 0) {
                trimmed.substring(0, idx).trim() to trimmed.substring(idx + "#audio=".length).trim()
            } else {
                trimmed to ""
            }
        }

        val results = targets.amap { (targetUrl, audioTag) ->
            var localFound = false
            val doc = try {
                app.get(targetUrl, referer = mainUrl).document
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@amap false
            }

            var btnIdx = 0
            for (button in doc.select("[data-source]")) {
                btnIdx++
                val source = button.attr("data-source")
                val rawSub = button.attr("data-subtitles").trim()
                if (rawSub.startsWith("http")) {
                    subtitleCallback(newSubtitleFile("Português", rawSub))
                }
                val host = runCatching { URI(source).host }.getOrNull().orEmpty()
                val rawBtnText = button.text().replace(Regex("""\s+"""), " ").trim()
                val label = when {
                    audioTag.isNotBlank() && rawBtnText.isNotBlank() && !rawBtnText.contains(audioTag, ignoreCase = true) ->
                        "$audioTag · $rawBtnText"
                    audioTag.isNotBlank() && rawBtnText.isBlank() ->
                        "$audioTag #$btnIdx"
                    rawBtnText.isNotBlank() -> rawBtnText
                    else -> "Player #$btnIdx"
                }
                try {
                    val resolved = when {
                        host.contains("byse") -> ByseResolver().resolve(source, targetUrl, label, subtitleCallback, callback)
                        (host == "abyssplayer.com" || host.endsWith(".abyssplayer.com")) && !isCasting ->
                            AbyssResolver(proxy).resolve(source, targetUrl, label, callback)
                        else -> false
                    }
                    localFound = resolved || localFound
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    android.util.Log.w("UltraCineTV", "$host: ${e.javaClass.simpleName}: ${e.message?.replace(Regex("https?://[^\\s]+"), "[url]")?.take(160)}")
                }
            }
            localFound
        }

        if (results.none { it }) {
            throw ErrorLoadingException("Abyss/Byse não liberaram vídeo; o servidor pode estar indisponível ou ter mudado")
        }
        return true
    }
}
