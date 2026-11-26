package com.netcine

import org.jsoup.nodes.Document
import java.net.URI

internal object NetCatalog {
    data class Item(
        val title: String,
        val url: String,
        val poster: String?,
        val series: Boolean,
        val year: Int? = null
    )

    data class Episode(
        val url: String,
        val title: String,
        val season: Int?,
        val number: Int?
    )

    data class Player(
        val url: String,
        val audio: String
    )

    private val THUMB_SUFFIX = Regex("""-\d+x\d+(?=\.[a-zA-Z0-9]+$)""")

    fun upgradePoster(url: String?): String? {
        val clean = url?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("data:") } ?: return null
        return clean.replace(THUMB_SUFFIX, "")
    }

    fun items(doc: Document): List<Item> = doc.select("#box_movies .movie").mapNotNull { row ->
        val link = row.selectFirst("a[href]") ?: return@mapNotNull null
        val title = row.selectFirst("h2")?.text()?.trim().orEmpty()
        if (title.isBlank()) return@mapNotNull null
        val img = row.selectFirst("img[data-src], noscript img, img")
        val rawSrc = img?.attr("data-src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        val resolvedPoster = rawSrc?.let {
            runCatching { URI(doc.baseUri()).resolve(it).toString() }.getOrDefault(it)
        }?.let(::upgradePoster)
        val url = link.absUrl("href").ifBlank { link.attr("href") }
        val year = row.selectFirst(".year")?.text()?.trim()?.toIntOrNull()
        Item(title, url, resolvedPoster, url.contains("/tvshows/"), year)
    }.distinctBy { it.url }

    fun episodes(doc: Document): List<Episode> = doc.select("#cssmenu a[href*=/episode/]").map { link ->
        val parts = Regex("""(\d+)\s*-\s*(\d+)""").find(link.selectFirst(".datex")?.text().orEmpty())
        val rawTitle = link.selectFirst(".datix")?.text()?.trim().orEmpty()
        Episode(
            url = link.absUrl("href").ifBlank { link.attr("href") },
            title = rawTitle,
            season = parts?.groupValues?.get(1)?.toIntOrNull(),
            number = parts?.groupValues?.get(2)?.toIntOrNull()
        )
    }.distinctBy { it.url }

    fun players(doc: Document, base: String): List<Player> {
        val tabLabels = doc.select(".player-menu a[href^=#]").associate { a ->
            a.attr("href").removePrefix("#") to a.text().trim()
        }
        return doc.select("iframe[src*=hlsarchive.php], iframe[data-src*=hlsarchive.php]").mapNotNull { frame ->
            val rawSrc = frame.attr("src").ifBlank { frame.attr("data-src") }.trim()
            if (rawSrc.isBlank()) return@mapNotNull null
            val url = URI("${base.trimEnd('/')}/").resolve(rawSrc).toString()
            val containerId = frame.parents().firstOrNull { it.id().startsWith("play-") }?.id()
            val tabLabel = containerId?.let { tabLabels[it] }.orEmpty()
            val audio = when {
                tabLabel.contains("Dublado", true) || url.contains("DUB", true) -> "Dublado"
                tabLabel.contains("Legendado", true) || url.contains("LEG", true) -> "Legendado"
                tabLabel.isNotBlank() -> tabLabel
                else -> "Áudio original"
            }
            Player(url, audio)
        }.distinctBy { it.url }
    }

    fun extractOnlineHlsUrl(archiveDoc: Document, archiveUrl: String): String? {
        val href = archiveDoc.selectFirst("a[href*=/hls/hls.php], a[href*=hls.php]")?.attr("href")?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { URI(archiveUrl).resolve(href).toString() }.getOrNull()
    }
}
