package com.noveflix

import org.jsoup.nodes.Document

internal data class NoveCard(
    val title: String,
    val url: String,
    val posterUrl: String?
)

internal data class DooPlayerOption(
    val post: String,
    val type: String,
    val nume: String
)

internal data class NoveChapter(
    val season: Int,
    val episode: Int,
    val name: String,
    val url: String,
    val posterUrl: String?
)

internal data class ReleaseForm(
    val token: String,
    val issued: String
)

internal object NoveFlixParser {

    private val SEASON_NUM_REGEX = Regex("""(\d+)\s*[ªaº°]?\s*Temporada|Temporada\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val CHAPTER_NUM_REGEX = Regex("""(?:Cap[íi]tulo|Epis[óo]dio|Ep\.?)\s*0*(\d+)""", RegexOption.IGNORE_CASE)
    private val STREAM_URL_REGEX = Regex("""https?://[^\s"'<>\\]+/stream\.php\?[^\s"'<>\\]+""", RegexOption.IGNORE_CASE)
    private val DIRECT_MEDIA_REGEX = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
    private val JS_TEMPLATE_URL_REGEX = Regex(
        """originalUrl\s*=\s*`(https?://[^`$]+)\$\{[^}]+\}([^`]*)`""",
        RegexOption.IGNORE_CASE
    )
    private val JS_CONCAT_URL_REGEX = Regex(
        """originalUrl\s*=\s*["'](https?://[^"']+)["']\s*\+""",
        RegexOption.IGNORE_CASE
    )
    private val JS_TOTAL_CHAPTERS_REGEX = Regex("""totalChapters\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val JS_PAD_WIDTH_REGEX = Regex("""slice\s*\(\s*-(\d+)\s*\)|padStart\s*\(\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val JS_LEGENDADOS_REGEX = Regex("""legendados\s*=\s*\[([^\]]*)\]""", RegexOption.IGNORE_CASE)
    private val JS_TEMPORADA_OBJ_REGEX = Regex(
        """\{\s*nome\s*:\s*["']([^"']+)["']\s*,\s*inicio\s*:\s*(\d+)\s*,\s*fim\s*:\s*(\d+)\s*\}""",
        RegexOption.IGNORE_CASE
    )
    private val NFLIX_VIDEO_SLUG_REGEX = Regex("""^(https?://[^/]+)/video/([^/?#]+)""", RegexOption.IGNORE_CASE)

    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    internal fun decodeBase64(input: String): String? {
        val clean = input.trim().replace("-", "+").replace("_", "/").filter { !it.isWhitespace() }
        if (clean.isEmpty()) return null
        val out = ArrayList<Byte>((clean.length * 3) / 4)
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val v = B64_ALPHABET.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer ushr bits) and 0xFF).toByte())
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    fun normalizeChapterUrl(rawUrl: String, fallbackBaseUrl: String): String {
        var resolved = resolveUrl(rawUrl, fallbackBaseUrl)
        if (resolved.contains("redirectnflix.com/?", ignoreCase = true)) {
            val encoded = resolved.substringAfter("redirectnflix.com/?").substringBefore("#").trim()
            val decoded = decodeBase64(encoded)?.trim().orEmpty()
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                resolved = decoded
            }
        }
        val videoMatch = NFLIX_VIDEO_SLUG_REGEX.find(resolved)
        if (videoMatch != null) {
            val host = videoMatch.groupValues[1]
            val slug = videoMatch.groupValues[2]
            return "$host/liberar.php?slug=$slug"
        }
        return resolved
    }

    fun resolveUrl(raw: String?, baseUrl: String): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        val cleanBase = baseUrl.trimEnd('/')
        return if (trimmed.startsWith("/")) "$cleanBase$trimmed" else "$cleanBase/$trimmed"
    }

    fun parseArchiveCards(doc: Document, baseUrl: String): List<NoveCard> {
        return doc.select("article.item, .items article").mapNotNull { article ->
            val link = article.selectFirst("h3 a[href], .data a[href], .poster a[href]") ?: return@mapNotNull null
            val href = resolveUrl(link.attr("href"), baseUrl)
            if (href.isBlank()) return@mapNotNull null

            val img = article.selectFirst("img")
            val title = article.selectFirst("h3 a")?.text()?.trim()
                ?.ifBlank { img?.attr("alt")?.trim() }
                ?.ifBlank { link.text().trim() }
                .orEmpty()
            if (title.isBlank()) return@mapNotNull null

            val rawPoster = img?.attr("data-src")
                ?.ifBlank { img.attr("data-lazy-src") }
                ?.ifBlank { img.attr("src") }
            val poster = resolveUrl(rawPoster, baseUrl).ifBlank { null }

            NoveCard(title = title, url = href, posterUrl = poster)
        }.distinctBy { it.url }
    }

    fun parseSearchCards(doc: Document, baseUrl: String): List<NoveCard> {
        return doc.select(".result-item article, .search-page .result-item").mapNotNull { item ->
            val link = item.selectFirst(".title a[href], .thumbnail a[href]") ?: return@mapNotNull null
            val href = resolveUrl(link.attr("href"), baseUrl)
            if (href.isBlank()) return@mapNotNull null

            val img = item.selectFirst("img")
            val title = item.selectFirst(".title a")?.text()?.trim()
                ?.ifBlank { img?.attr("alt")?.trim() }
                ?.ifBlank { link.text().trim() }
                .orEmpty()
            if (title.isBlank()) return@mapNotNull null

            val rawPoster = img?.attr("data-src")
                ?.ifBlank { img.attr("data-lazy-src") }
                ?.ifBlank { img.attr("src") }
            val poster = resolveUrl(rawPoster, baseUrl).ifBlank { null }

            NoveCard(title = title, url = href, posterUrl = poster)
        }.distinctBy { it.url }
    }

    fun hasNextPage(doc: Document, currentPage: Int): Boolean {
        return doc.select(".pagination a[href], .resppages a[href], a.next[href]").any { a ->
            val href = a.attr("href")
            href.contains("/page/${currentPage + 1}") || a.hasClass("next")
        }
    }

    fun parseDooPlayerOptions(doc: Document): List<DooPlayerOption> {
        return doc.select("[data-post][data-type][data-nume]").mapNotNull { el ->
            val post = el.attr("data-post").trim()
            val type = el.attr("data-type").trim()
            val nume = el.attr("data-nume").trim()
            if (post.isBlank() || type.isBlank() || nume.isBlank()) return@mapNotNull null
            if (nume.equals("trailer", ignoreCase = true)) return@mapNotNull null
            DooPlayerOption(post = post, type = type, nume = nume)
        }.distinctBy { "${it.post}:${it.type}:${it.nume}" }
    }

    fun parsePanelChapters(doc: Document, panelBaseUrl: String): List<NoveChapter> {
        val chapters = mutableListOf<NoveChapter>()
        val seasonContainers = doc.select(".season-item")

        if (seasonContainers.isNotEmpty()) {
            seasonContainers.forEachIndexed { idx, seasonEl ->
                val headerText = seasonEl.selectFirst(".season-toggle, .season-title, h2, h3")?.text().orEmpty()
                val seasonNum = SEASON_NUM_REGEX.find(headerText)?.let { m ->
                    (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
                } ?: (idx + 1)

                chapters += parseChapterRows(seasonEl.select("li.chapter-row"), seasonNum, panelBaseUrl)
            }
        }

        if (chapters.isEmpty()) {
            chapters += parseChapterRows(doc.select("li.chapter-row"), 1, panelBaseUrl)
        }

        if (chapters.isEmpty()) {
            chapters += parseJsGeneratedChapters(doc.html(), panelBaseUrl)
        }

        if (chapters.isEmpty()) {
            chapters += parseStaticListLinks(doc, panelBaseUrl)
        }

        return chapters.distinctBy { it.url }
    }

    private fun parseJsGeneratedChapters(html: String, panelBaseUrl: String): List<NoveChapter> {
        val tplMatch = JS_TEMPLATE_URL_REGEX.find(html)
        val urlPrefix: String
        val urlSuffix: String
        if (tplMatch != null) {
            urlPrefix = tplMatch.groupValues[1]
            urlSuffix = tplMatch.groupValues[2]
        } else {
            val concatMatch = JS_CONCAT_URL_REGEX.find(html) ?: return emptyList()
            urlPrefix = concatMatch.groupValues[1]
            urlSuffix = ""
        }

        val padWidth = JS_PAD_WIDTH_REGEX.find(html)?.let { m ->
            (m.groupValues[1].ifBlank { m.groupValues[2] }).toIntOrNull()
        }?.coerceIn(1, 5) ?: 3

        val legendados = JS_LEGENDADOS_REGEX.find(html)?.groupValues?.get(1)
            ?.split(",")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            .orEmpty()

        val chapters = mutableListOf<NoveChapter>()

        fun makeChapter(season: Int, num: Int): NoveChapter {
            val chapterStr = num.toString().padStart(padWidth, '0')
            val label = if (num in legendados) "Capítulo $chapterStr (Legendado)" else "Capítulo $chapterStr"
            val rawUrl = "$urlPrefix$chapterStr$urlSuffix"
            return NoveChapter(
                season = season,
                episode = num,
                name = label,
                url = normalizeChapterUrl(rawUrl, panelBaseUrl),
                posterUrl = null
            )
        }

        val seasonMatches = JS_TEMPORADA_OBJ_REGEX.findAll(html).toList()
        if (seasonMatches.isNotEmpty()) {
            seasonMatches.forEachIndexed { idx, m ->
                val seasonName = m.groupValues[1]
                val inicio = m.groupValues[2].toIntOrNull() ?: return@forEachIndexed
                val fim = m.groupValues[3].toIntOrNull() ?: return@forEachIndexed
                if (fim < inicio || (fim - inicio) > 2000) return@forEachIndexed
                val seasonNum = SEASON_NUM_REGEX.find(seasonName)?.let { sm ->
                    (sm.groupValues[1].ifBlank { sm.groupValues[2] }).toIntOrNull()
                } ?: (idx + 1)
                for (i in inicio..fim) {
                    chapters.add(makeChapter(seasonNum, i))
                }
            }
            return chapters
        }

        val total = JS_TOTAL_CHAPTERS_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: return emptyList()
        if (total <= 0 || total > 3000) return emptyList()
        for (i in 1..total) {
            chapters.add(makeChapter(1, i))
        }
        return chapters
    }

    private fun parseStaticListLinks(doc: Document, panelBaseUrl: String): List<NoveChapter> {
        val anchors = doc.select("#chapter-list a[href], .lista-container a[href], .capitulos a[href]")
        return anchors.mapIndexedNotNull { idx, a ->
            val rawHref = a.attr("href").trim()
            if (rawHref.isBlank() || rawHref.startsWith("#") || rawHref.startsWith("javascript:", ignoreCase = true)) {
                return@mapIndexedNotNull null
            }
            val normalizedUrl = normalizeChapterUrl(rawHref, panelBaseUrl)
            if (normalizedUrl.isBlank()) return@mapIndexedNotNull null
            val text = a.text().trim().ifBlank { "Capítulo ${idx + 1}" }
            val epNum = CHAPTER_NUM_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
            NoveChapter(
                season = 1,
                episode = epNum,
                name = text,
                url = normalizedUrl,
                posterUrl = null
            )
        }
    }

    private fun parseChapterRows(
        rows: List<org.jsoup.nodes.Element>,
        seasonNum: Int,
        panelBaseUrl: String
    ): List<NoveChapter> {
        return rows.mapIndexedNotNull { idx, row ->
            val link = row.selectFirst("a[href]") ?: return@mapIndexedNotNull null
            val href = resolveUrl(link.attr("href"), panelBaseUrl)
            if (href.isBlank()) return@mapIndexedNotNull null

            val chapterLabel = row.selectFirst(".chapter-name")?.text()?.trim().orEmpty()
            val episodeTitle = row.selectFirst(".episode-title")?.text()?.trim().orEmpty()
            val fullName = when {
                chapterLabel.isNotBlank() && episodeTitle.isNotBlank() -> "$chapterLabel - $episodeTitle"
                chapterLabel.isNotBlank() -> chapterLabel
                episodeTitle.isNotBlank() -> episodeTitle
                else -> "Capítulo ${idx + 1}"
            }

            val epNum = row.attr("data-chapter").toIntOrNull()
                ?: CHAPTER_NUM_REGEX.find(fullName)?.groupValues?.get(1)?.toIntOrNull()
                ?: (idx + 1)

            val rawThumb = row.selectFirst("img.chapter-thumb")?.attr("src")
            val thumb = resolveUrl(rawThumb, panelBaseUrl).ifBlank { null }

            NoveChapter(
                season = seasonNum,
                episode = epNum,
                name = fullName,
                url = href,
                posterUrl = thumb
            )
        }
    }

    fun parseReleaseForm(doc: Document): ReleaseForm? {
        val token = doc.selectFirst("input[name=token]")?.attr("value")?.trim().orEmpty()
        val issued = doc.selectFirst("input[name=issued]")?.attr("value")?.trim().orEmpty()
        if (token.isBlank() || issued.isBlank()) return null
        return ReleaseForm(token = token, issued = issued)
    }

    fun extractStreamUrl(html: String): String? {
        val streamPhp = STREAM_URL_REGEX.find(html)?.value?.replace("\\/", "/")?.replace("&amp;", "&")
        if (!streamPhp.isNullOrBlank()) return streamPhp
        return DIRECT_MEDIA_REGEX.find(html)?.value?.replace("\\/", "/")?.replace("&amp;", "&")
    }
}
