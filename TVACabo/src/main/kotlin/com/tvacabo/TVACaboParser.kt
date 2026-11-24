package com.tvacabo

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

internal data class TvCaboChannel(
    val name: String,
    val url: String,
    val category: String
)

internal data class TvCaboEmbed(
    val provider: String,
    val url: String
)

internal data class TvCaboEvent(
    val id: String,
    val title: String,
    val competition: String,
    val statusBadge: String,
    val isLive: Boolean,
    val timeStr: String,
    val sortTimestamp: Long,
    val posterUrl: String?,
    val embeds: List<TvCaboEmbed>
)

internal object TVACaboParser {

    private val BLOCKED_KEYWORDS = listOf(
        "extra +18", "+18", "aviso.html", "sexy hot", "sexyhot", "playboy", "venus", "sextreme", "adulto", "xxx"
    )
    private val TIME_REGEX = Regex("""\b(\d{2}:\d{2})\b""")
    private val SPORTS_API_REGEX = Regex("""https?://[^\s"'<>\\]+/sports\b""", RegexOption.IGNORE_CASE)
    private val DIRECT_SRC_REGEX = Regex("""(?:const|var|let)\s+(?:src|SRC)\s*=\s*["'](https?://[^"']+)["']""")
    private val M3U8_REGEX = Regex("""https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)

    val CATEGORY_ORDER = listOf(
        "Destaques",
        "Esportes",
        "Abertos",
        "Filmes",
        "Desenhos",
        "Notícias",
        "Variedades",
        "Religiosos",
        "Músicas"
    )

    fun isBlocked(name: String, url: String): Boolean {
        val hay = "$name $url".lowercase()
        return BLOCKED_KEYWORDS.any { hay.contains(it) } ||
            url.contains("esporte.html", ignoreCase = true) ||
            name.equals("JOGOS DO DIA", ignoreCase = true)
    }

    fun normalizeCategory(raw: String): String {
        return when (raw.trim().lowercase()) {
            "destaques" -> "Destaques"
            "noticias", "notícias" -> "Notícias"
            "abertos" -> "Abertos"
            "esportes" -> "Esportes"
            "desenhos" -> "Desenhos"
            "filmes/series", "filmes" -> "Filmes"
            "religiosos" -> "Religiosos"
            "variedades" -> "Variedades"
            "musicas", "músicas" -> "Músicas"
            else -> raw.trim().ifBlank { "Variedades" }
        }
    }

    fun parseChannelsJson(jsonText: String): List<TvCaboChannel> {
        val arr = try {
            JSONArray(jsonText)
        } catch (_: Exception) {
            return emptyList()
        }

        val channels = mutableListOf<TvCaboChannel>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("nome").trim()
            val url = obj.optString("url").trim()
            val cat = normalizeCategory(obj.optString("categoria"))
            if (name.isBlank() || url.isBlank() || isBlocked(name, url)) continue
            channels.add(TvCaboChannel(name = name, url = url, category = cat))
        }
        return channels.distinctBy { "${it.name}|${it.url}" }
    }

    fun extractSportsApiUrl(esporteHtml: String): String {
        return SPORTS_API_REGEX.find(esporteHtml)?.value ?: "https://api.reidoscanais.im/sports"
    }

    fun parseSportsEventsJson(jsonText: String): List<TvCaboEvent> {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return emptyList()
        }
        val arr = root.optJSONArray("data") ?: return emptyList()
        val events = mutableListOf<TvCaboEvent>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            val title = obj.optString("title").trim()
            if (id.isBlank() || title.isBlank()) continue

            val status = obj.optString("status").trim().lowercase()
            if (status == "finished" || status == "ended" || status == "encerrado" || status == "cancelled") {
                continue
            }

            val isLive = status == "live" || status == "ao_vivo" || status == "ao vivo"
            val statusBadge = if (isLive) "AO VIVO" else "EM BREVE"

            val competition = obj.optString("competition").trim()
            val startTime = obj.optString("start_time").trim()
            val timeStr = TIME_REGEX.find(startTime)?.groupValues?.get(1).orEmpty()
            val sortTs = obj.optLong("start_timestamp", Long.MAX_VALUE)
            val poster = obj.optString("poster").trim().ifBlank { null }

            val embeds = mutableListOf<TvCaboEmbed>()
            val embedsArr = obj.optJSONArray("embeds")
            if (embedsArr != null) {
                for (j in 0 until embedsArr.length()) {
                    val em = embedsArr.optJSONObject(j) ?: continue
                    val eUrl = em.optString("embed_url").replace("\\/", "/").trim()
                    val provider = em.optString("provider").trim().ifBlank { "Opção ${j + 1}" }
                    if (eUrl.startsWith("http")) {
                        embeds.add(TvCaboEmbed(provider = provider, url = eUrl))
                    }
                }
            }
            if (embeds.isEmpty()) continue

            events.add(
                TvCaboEvent(
                    id = id,
                    title = title,
                    competition = competition,
                    statusBadge = statusBadge,
                    isLive = isLive,
                    timeStr = timeStr,
                    sortTimestamp = sortTs,
                    posterUrl = poster,
                    embeds = embeds
                )
            )
        }

        return events.sortedWith(compareBy({ !it.isLive }, { it.sortTimestamp }))
    }

    fun unwrapWrapperUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        val queryParamKey = when {
            trimmed.contains("iframe.html?url=") -> "iframe.html?url="
            trimmed.contains("player.html?url=") -> "player.html?url="
            trimmed.contains("embed.html?src=") -> "embed.html?src="
            trimmed.contains("anym3u8player.com") && trimmed.contains("url=") -> "url="
            else -> null
        } ?: return trimmed

        val inner = trimmed.substringAfter(queryParamKey)
        val decoded = if (inner.startsWith("http%3A", ignoreCase = true) || inner.startsWith("https%3A", ignoreCase = true)) {
            try {
                URLDecoder.decode(inner.substringBefore("&"), "UTF-8")
            } catch (_: Exception) {
                inner
            }
        } else {
            inner
        }
        return decoded.trim()
    }

    fun redeCanaisLiveFallback(url: String): String? {
        if (!url.contains("redecanaistv.", ignoreCase = true) || !url.contains("canal=", ignoreCase = true)) {
            return null
        }
        val slug = url.substringAfter("canal=").substringBefore("&").trim()
        if (slug.isBlank()) return null
        return "https://redecanais.ro/canais-de-tv/player3/live.php?etv=$slug"
    }

    fun extractDirectStreamFromHtml(html: String): String? {
        val src = DIRECT_SRC_REGEX.find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()
        if (!src.isNullOrBlank()) return src
        return M3U8_REGEX.find(html)?.value?.replace("\\/", "/")?.trim()
    }
}
