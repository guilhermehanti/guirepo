package com.redecanais

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

internal data class CatalogCard(
    val title: String,
    val rawTitle: String,
    val url: String,
    val posterUrl: String?,
    val year: Int?,
    val audioTag: String?,
    val isSeries: Boolean
)

internal data class EpisodeLink(
    val label: String,
    val url: String
)

internal data class ParsedEpisode(
    val season: Int,
    val episode: Int,
    val name: String,
    val links: List<EpisodeLink>
)

internal data class StreamSource(
    val url: String,
    val isM3u8: Boolean
)

internal data class ParsedTitle(
    val title: String,
    val year: Int?,
    val audioTag: String?
)

internal object RedeCanaisParser {

    private val YEAR_REGEX = Regex("""(?:\s*-\s*|\()((?:19|20)\d{2})\)?""")
    private val QUALITY_REGEX = Regex("""\s*-\s*(?:4K|2160p|1080p|720p|480p|HD|Full\s*HD|CAM|HDCAM)\b""", RegexOption.IGNORE_CASE)
    private val AUDIO_PARENS_REGEX = Regex("""\s*\((Dublado|Legendado|Nacional|Dual\s*[ÁA]udio|Dublado\s*/\s*Legendado)\)""", RegexOption.IGNORE_CASE)
    private val SEASON_HEADER_REGEX = Regex("""(\d+)\s*[ªaº°]?\s*Temporada""", RegexOption.IGNORE_CASE)
    private val EPISODE_NUM_REGEX = Regex("""(?:Epis[óo]dio|Ep\.?)\s*0*(\d+)""", RegexOption.IGNORE_CASE)
    private val SXXEXX_REGEX = Regex("""S\d+E0*(\d+)""", RegexOption.IGNORE_CASE)
    private val PLAYER_IFRAME_NAME_REGEX = Regex(
        """<iframe[^>]+name=["']Player["'][^>]+src=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE
    )
    private val PLAYER_IFRAME_SRC_FIRST_REGEX = Regex(
        """<iframe[^>]+src=["']([^"']*(?:player\d*/server\.php|/embed\.php)[^"']*)["']""",
        RegexOption.IGNORE_CASE
    )
    private val SRC_VAR_REGEX = Regex("""var\s+SRC\s*=\s*["']([^"']+)["']""")
    private val SOURCE_FORMAT_REGEX = Regex("""var\s+SOURCE_FORMAT\s*=\s*["']([^"']+)["']""")
    private val SOURCE_MIME_REGEX = Regex("""var\s+SOURCE_MIME\s*=\s*["']([^"']+)["']""")

    fun resolveUrl(raw: String?, baseUrl: String): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        val cleanBase = baseUrl.trimEnd('/')
        return if (trimmed.startsWith("/")) "$cleanBase$trimmed" else "$cleanBase/$trimmed"
    }

    fun resolvePosterUrl(raw: String?, baseUrl: String): String? {
        val resolved = resolveUrl(raw, baseUrl)
        if (resolved.isBlank()) return null
        val tmdbIdx = resolved.indexOf("/tmdb/t/p/", ignoreCase = true)
        if (tmdbIdx >= 0) {
            val tmdbPath = resolved.substring(tmdbIdx + "/tmdb".length)
            return "https://image.tmdb.org$tmdbPath"
        }
        return resolved
    }

    fun parseTitleMetadata(rawTitle: String): ParsedTitle {
        val normalized = rawTitle.replace(Regex("""\s+"""), " ").trim()
        val year = YEAR_REGEX.find(normalized)?.groupValues?.get(1)?.toIntOrNull()
        val audioTag = AUDIO_PARENS_REGEX.find(normalized)?.groupValues?.get(1)?.trim()

        var title = normalized
            .replace(QUALITY_REGEX, "")
            .replace(AUDIO_PARENS_REGEX, "")
            .replace(YEAR_REGEX, "")
            .replace(Regex("""\s*-\s*Lista\s+de\s+Epis[óo]dios.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*$"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (title.isBlank()) title = normalized
        return ParsedTitle(title = title, year = year, audioTag = audioTag)
    }

    fun parseCards(doc: Document, baseUrl: String): List<CatalogCard> {
        val cards = doc.select("ul#pm-grid > li, ul.pm-ul-browse-videos > li")
        return cards.mapNotNull { li ->
            val link = li.selectFirst("h3 a[href]")
                ?: li.selectFirst(".pm-video-thumb a[href]:not(.pm-watch-later-add)")
                ?: return@mapNotNull null

            val href = resolveUrl(link.attr("href"), baseUrl)
            if (href.isBlank() || href.contains("#modal-")) return@mapNotNull null

            val rawTitle = link.attr("title").ifBlank { link.text() }.trim()
            if (rawTitle.isBlank()) return@mapNotNull null

            val img = li.selectFirst("img")
            val rawPoster = img?.attr("data-echo")
                ?.ifBlank { img.attr("data-src") }
                ?.ifBlank { img.attr("src") }
            val posterUrl = resolvePosterUrl(rawPoster, baseUrl)

            val parsed = parseTitleMetadata(rawTitle)
            val isSeries = href.contains("lista-de-episodios", ignoreCase = true)

            CatalogCard(
                title = parsed.title,
                rawTitle = rawTitle,
                url = href,
                posterUrl = posterUrl,
                year = parsed.year,
                audioTag = parsed.audioTag,
                isSeries = isSeries
            )
        }.distinctBy { it.url }
    }

    fun hasNextPage(doc: Document, currentPage: Int): Boolean {
        val nextLinks = doc.select("ul.pagination li:not(.disabled) a[href]")
        return nextLinks.any { a ->
            val href = a.attr("href")
            val text = a.text().trim()
            href.contains("-videos-${currentPage + 1}-") ||
                href.contains("page=${currentPage + 1}") ||
                text == "»" ||
                text.equals("Próxima", ignoreCase = true)
        }
    }

    fun parseSeriesEpisodes(doc: Document, baseUrl: String): List<ParsedEpisode> {
        val container = doc.selectFirst("div[itemprop=description]") ?: return emptyList()
        val rawHtml = container.html()
        val segments = rawHtml.split(Regex("""<br\s*/?>|</p>|<p[^>]*>|</div>|<div[^>]*>""", RegexOption.IGNORE_CASE))

        var currentSeason = 1
        val episodeCounters = mutableMapOf<Int, Int>()
        val results = mutableListOf<ParsedEpisode>()

        for (segment in segments) {
            val trimmed = segment.trim()
            if (trimmed.isBlank()) continue

            val frag = Jsoup.parseBodyFragment(trimmed)
            val text = frag.text().replace(Regex("""\s+"""), " ").trim()
            if (text.isBlank()) continue

            val anchors = frag.select("a[href]")
            if (anchors.isEmpty()) {
                val seasonMatch = SEASON_HEADER_REGEX.find(text)
                if (seasonMatch != null) {
                    currentSeason = seasonMatch.groupValues[1].toIntOrNull() ?: currentSeason
                }
                continue
            }

            val links = anchors.mapNotNull { a ->
                val href = resolveUrl(a.attr("href"), baseUrl)
                if (href.isBlank() || href.contains("#")) return@mapNotNull null
                val label = a.text().trim().ifBlank { "Assistir" }
                EpisodeLink(label = label, url = href)
            }.distinctBy { it.url }

            if (links.isEmpty()) continue

            // Remove anchor text to isolate the episode label/title
            val labelFrag = frag.clone()
            labelFrag.select("a").remove()
            val cleanLine = labelFrag.text()
                .replace(Regex("""\s+"""), " ")
                .replace(Regex("""\s*[-–|/:]+\s*$"""), "")
                .trim()

            val epNum = EPISODE_NUM_REGEX.find(cleanLine)?.groupValues?.get(1)?.toIntOrNull()
                ?: SXXEXX_REGEX.find(cleanLine)?.groupValues?.get(1)?.toIntOrNull()
                ?: SXXEXX_REGEX.find(links.first().url)?.groupValues?.get(1)?.toIntOrNull()
                ?: ((episodeCounters[currentSeason] ?: 0) + 1)

            episodeCounters[currentSeason] = maxOf(episodeCounters[currentSeason] ?: 0, epNum)

            val epName = cleanEpisodeName(cleanLine, epNum)
            results.add(
                ParsedEpisode(
                    season = currentSeason,
                    episode = epNum,
                    name = epName,
                    links = links
                )
            )
        }

        return results
    }

    private fun cleanEpisodeName(rawLine: String, epNum: Int): String {
        var cleaned = rawLine
            .replace(Regex("""^Epis[óo]dio\s*0*\d+\s*[-–:]?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^S\d+E\d+\s*[-–:]?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*[-–|]+\s*$"""), "")
            .trim()
        if (cleaned.isBlank() || cleaned.equals("Episódio $epNum", ignoreCase = true)) {
            cleaned = "Episódio $epNum"
        }
        return cleaned
    }

    fun extractPlayerIframe(html: String, baseUrl: String): String? {
        val raw = PLAYER_IFRAME_NAME_REGEX.find(html)?.groupValues?.get(1)
            ?: PLAYER_IFRAME_SRC_FIRST_REGEX.find(html)?.groupValues?.get(1)
            ?: return null
        val resolved = resolveUrl(raw, baseUrl)
        return resolved.ifBlank { null }
    }

    fun extractStreamSource(playerHtml: String): StreamSource? {
        val rawSrc = SRC_VAR_REGEX.find(playerHtml)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.trim()
            ?: return null
        if (rawSrc.isBlank()) return null

        val format = SOURCE_FORMAT_REGEX.find(playerHtml)?.groupValues?.get(1)?.trim().orEmpty()
        val mime = SOURCE_MIME_REGEX.find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/")?.trim().orEmpty()

        val isM3u8 = rawSrc.contains(".m3u8", ignoreCase = true) ||
            format.equals("hls", ignoreCase = true) ||
            mime.contains("mpegurl", ignoreCase = true)

        return StreamSource(url = rawSrc, isM3u8 = isM3u8)
    }

    fun isCloudflareChallenge(html: String, statusCode: Int = 200): Boolean {
        if (statusCode == 403 || statusCode == 503) {
            if (html.contains("cloudflare", ignoreCase = true) ||
                html.contains("cf-challenge", ignoreCase = true) ||
                html.contains("cf-browser-verification", ignoreCase = true) ||
                html.contains("Attention Required", ignoreCase = true) ||
                html.contains("Just a moment", ignoreCase = true) ||
                html.contains("turnstile", ignoreCase = true)
            ) {
                return true
            }
        }
        return html.contains("cf-turnstile", ignoreCase = true) ||
            html.contains("/cdn-cgi/challenge-platform/", ignoreCase = true) ||
            html.contains("Attention Required! | Cloudflare", ignoreCase = true) ||
            html.contains("<title>Just a moment...</title>", ignoreCase = true)
    }

    fun cleanSynopsis(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return null
        return text
            .replace(
                Regex("""^(?:Filme|S[ée]rie|Desenho|Anime)\s+.+?no\s+RedeCanais(?:\s+Oficial)?\.\s*""", RegexOption.IGNORE_CASE),
                ""
            )
            .trim()
            .ifBlank { text }
    }
}
