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

        return chapters.distinctBy { it.url }
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
