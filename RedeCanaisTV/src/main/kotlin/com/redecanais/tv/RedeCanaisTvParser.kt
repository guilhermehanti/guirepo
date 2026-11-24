package com.redecanais.tv

import org.jsoup.nodes.Document

internal data class LiveChannel(
    val name: String,
    val url: String,
    val posterUrl: String?,
    val category: String
)

internal object RedeCanaisTvParser {

    private val BLOCKED_KEYWORDS = listOf(
        "sexy hot", "sexyhot", "playboy", "venus", "sextreme", "privê", "prive", "adulto", "+18", "xxx"
    )

    private val PLAYER_IFRAME_REGEX = Regex(
        """<iframe[^>]+src=["']([^"']*(?:player\d*/live\.php|player\d*/server\.php)[^"']*)["']""",
        RegexOption.IGNORE_CASE
    )
    private val SRC_VAR_REGEX = Regex("""var\s+SRC\s*=\s*["']([^"']+)["']""")
    private val M3U8_FALLBACK_REGEX = Regex("""https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)

    fun resolveUrl(raw: String?, baseUrl: String): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        val cleanBase = baseUrl.trimEnd('/')
        return if (trimmed.startsWith("/")) "$cleanBase$trimmed" else "$cleanBase/$trimmed"
    }

    fun isBlocked(name: String, url: String): Boolean {
        val haystack = "$name $url".lowercase()
        return BLOCKED_KEYWORDS.any { haystack.contains(it) }
    }

    fun categorize(name: String): String {
        val n = name.lowercase()
        return when {
            listOf(
                "sportv", "espn", "premiere", "combate", "band sports", "bandsports",
                "cazé", "caze", "nosso futebol", "fox sports", "paramount", "tnt sports",
                "ufc", "nba", "nfl", "dazn", "futebol", "esporte", "sport"
            ).any { n.contains(it) } -> "Esportes"

            listOf(
                "globo", "sbt", "record", "band", "redetv", "cultura", "aparecida",
                "canção nova", "cancao nova", "gazeta", "tv brasil", "futura"
            ).any { n.contains(it) } && !n.contains("news") && !n.contains("sports") -> "TV Aberta"

            listOf(
                "hbo", "telecine", "megapix", "tnt", "space", "warner", "sony", "axn",
                "amc", "cinemax", "universal", "studio universal", "syfy", "star channel",
                "fx", "paramount network", "usa", "a&e", "aie", "tcm", "film&arts", "canal brasil"
            ).any { n.contains(it) } -> "Filmes e Séries"

            listOf(
                "cartoon", "disney", "nickelodeon", "nick", "gloob", "discovery kids",
                "tooncast", "adult swim", "boomerang", "cartoonito", "dreamworks", "infantil"
            ).any { n.contains(it) } -> "Infantis"

            listOf(
                "news", "cnn", "jovem pan", "globonews", "bandnews", "record news", "bbc", "bloomberg"
            ).any { n.contains(it) } -> "Notícias"

            listOf(
                "discovery", "history", "national geographic", "natgeo", "animal planet",
                "h2", "tlc", "hgtv", "id ", "investigação", "curta"
            ).any { n.contains(it) } -> "Documentários"

            else -> "Variedades e Outros"
        }
    }

    fun parseChannels(doc: Document, baseUrl: String): List<LiveChannel> {
        return doc.select("section.list-channels .channel a[href], .list-channels .channel a[href]")
            .mapNotNull { a ->
                val href = resolveUrl(a.attr("href"), baseUrl)
                if (href.isBlank() || href.contains("#")) return@mapNotNull null

                val img = a.selectFirst("img")
                val name = a.select(".name span")
                    .lastOrNull { !it.hasClass("badge") }
                    ?.text()?.trim()
                    ?.ifBlank { img?.attr("alt")?.trim() }
                    ?.ifBlank { a.text().trim() }
                    .orEmpty()
                if (name.isBlank() || isBlocked(name, href)) return@mapNotNull null

                val rawLogo = img?.attr("data-src")?.ifBlank { img.attr("src") } ?: img?.attr("src")
                val poster = resolveUrl(rawLogo, baseUrl).ifBlank { null }

                LiveChannel(
                    name = name,
                    url = href,
                    posterUrl = poster,
                    category = categorize(name)
                )
            }
            .distinctBy { it.url }
    }

    fun extractLiveIframe(html: String, baseUrl: String): String? {
        val raw = PLAYER_IFRAME_REGEX.find(html)?.groupValues?.get(1) ?: return null
        return resolveUrl(raw, baseUrl).ifBlank { null }
    }

    fun extractLiveStream(playerHtml: String): String? {
        val src = SRC_VAR_REGEX.find(playerHtml)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.trim()
        if (!src.isNullOrBlank()) return src
        return M3U8_FALLBACK_REGEX.find(playerHtml)?.value?.replace("\\/", "/")?.trim()
    }
}
