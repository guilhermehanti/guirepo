package com.brflixtv

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URLDecoder

internal data class BrFlixChannel(
    val id: String,
    val title: String,
    val url: String,
    val category: String,
    val posterUrl: String?
)

internal data class BrFlixEvent(
    val id: String,
    val slug: String,
    val title: String,
    val competition: String,
    val statusBadge: String,
    val isLive: Boolean,
    val timeStr: String,
    val sortKey: String,
    val posterUrl: String?,
    val directEmbedUrl: String?
)

internal data class ResolvedStream(
    val label: String,
    val url: String,
    val referer: String
)

internal data class BrFlixResolveResult(
    val directStreams: List<ResolvedStream>,
    val fallbackEmbeds: List<Pair<String, String>>
)

internal object BRFlixParser {

    private val BLOCKED_KEYWORDS = listOf(
        "sexy hot", "sexyhot", "playboy", "venus", "sextreme", "privê", "prive", "adulto", "+18", "xxx"
    )
    private val TIME_REGEX = Regex("""\b(\d{2}:\d{2})\b""")
    private val DATE_ISO_REGEX = Regex("""(\d{4})-(\d{2})-(\d{2})""")
    private val CONST_SRC_REGEX = Regex("""(?:const|var|let)\s+(?:src|SRC)\s*=\s*["'](https?://[^"']+)["']""")
    private val M3U8_REGEX = Regex("""https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)

    fun isBlocked(text: String): Boolean {
        val lower = text.lowercase()
        return BLOCKED_KEYWORDS.any { lower.contains(it) }
    }

    fun normalizeCategory(raw: String, fallback: String): String {
        val clean = raw.trim().lowercase()
        return when {
            clean.contains("esport") || clean.contains("futebol") -> "Esportes"
            clean.contains("filme") || clean.contains("serie") || clean.contains("série") -> "Filmes e Séries"
            clean.contains("abert") -> "TV Aberta"
            clean.contains("notic") || clean.contains("notíc") -> "Notícias"
            clean.contains("infant") || clean.contains("desenho") -> "Infantil"
            clean.contains("varied") || clean.contains("24h") -> "Variedades"
            fallback.isNotBlank() -> fallback
            else -> "Canais"
        }
    }

    fun parseHtmlChannels(doc: Document, baseUrl: String, defaultCategory: String): List<BrFlixChannel> {
        val cleanBase = baseUrl.trimEnd('/')
        return doc.select("a[href^=/canal/], a[href*=/canal/]").mapNotNull { a ->
            val href = a.attr("href").trim()
            val slug = href.substringAfter("/canal/").substringBefore("?").substringBefore("#").trim('/')
            if (slug.isBlank()) return@mapNotNull null

            val spans = a.select("span").map { it.text().trim() }.filter { it.isNotBlank() }
            val rawName = spans.firstOrNull { !it.equals(defaultCategory, ignoreCase = true) && it.length > 1 }
                ?: a.attr("title").trim().ifBlank { a.text().trim() }
            if (rawName.isBlank() || isBlocked("$rawName $slug")) return@mapNotNull null

            val badgeText = if (spans.size >= 2) spans.last() else ""
            val category = normalizeCategory(badgeText, defaultCategory)

            val imgSrc = a.selectFirst("img")?.attr("src")?.trim().orEmpty()
            val poster = when {
                imgSrc.startsWith("http") -> imgSrc
                imgSrc.startsWith("//") -> "https:$imgSrc"
                imgSrc.startsWith("/") -> "$cleanBase$imgSrc"
                else -> null
            }

            BrFlixChannel(
                id = slug,
                title = rawName,
                url = "$cleanBase/canal/$slug",
                category = category,
                posterUrl = poster
            )
        }.distinctBy { it.id }
    }

    fun parseAgendaEvents(jsonText: String): List<BrFlixEvent> {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return emptyList()
        }
        val arr = root.optJSONArray("data") ?: return emptyList()
        val events = mutableListOf<BrFlixEvent>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id").trim()
            val slug = obj.optString("slug").trim().ifBlank { id.removePrefix("e/") }
            val title = obj.optString("title").trim()
            if (title.isBlank() || slug.isBlank() || isBlocked(title)) continue

            val rawStatus = obj.optString("status").trim().lowercase()
            if (rawStatus == "finished" || rawStatus == "ended" || rawStatus == "encerrado" || rawStatus == "cancelled") {
                continue
            }

            val isLive = rawStatus == "live" || rawStatus == "ao_vivo" || rawStatus == "ao vivo" ||
                obj.optBoolean("is_live", false) || obj.optBoolean("isLive", false)
            val statusBadge = if (isLive) "AO VIVO" else "EM BREVE"

            val competition = obj.optString("competition").trim()
            val startTimeRaw = obj.optString("start_time").ifBlank {
                obj.optString("startTime").ifBlank {
                    obj.optString("time").ifBlank { obj.optString("horario") }
                }
            }.trim()

            val timeStr = TIME_REGEX.find(startTimeRaw)?.groupValues?.get(1).orEmpty()
            val dateKey = DATE_ISO_REGEX.find(startTimeRaw)?.let { m ->
                "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}"
            } ?: "99999999"
            val sortKey = "$dateKey${timeStr.ifBlank { "99:99" }}"

            val poster = obj.optString("poster").ifBlank {
                obj.optString("image").ifBlank {
                    obj.optString("banner").ifBlank {
                        obj.optString("competition_logo").ifBlank { obj.optString("time1") }
                    }
                }
            }.trim().ifBlank { null }

            val directEmbed = obj.optString("directEmbedUrl").ifBlank {
                obj.optString("embed_url")
            }.trim().ifBlank { null }

            events.add(
                BrFlixEvent(
                    id = id.ifBlank { "e/$slug" },
                    slug = slug,
                    title = title,
                    competition = competition,
                    statusBadge = statusBadge,
                    isLive = isLive,
                    timeStr = timeStr,
                    sortKey = sortKey,
                    posterUrl = poster,
                    directEmbedUrl = directEmbed
                )
            )
        }

        return events.sortedWith(compareBy({ !it.isLive }, { it.sortKey }))
    }

    fun unwrapStreamProxyUrl(rawUrl: String, baseUrl: String): String {
        val trimmed = rawUrl.trim().replace("\\/", "/")
        if (trimmed.isBlank()) return ""
        if (trimmed.contains("/api/stream-proxy") && trimmed.contains("url=")) {
            val encoded = trimmed.substringAfter("url=").substringBefore("&")
            val decoded = try {
                URLDecoder.decode(encoded, "UTF-8")
            } catch (_: Exception) {
                encoded
            }
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                return decoded
            }
        }
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "${baseUrl.trimEnd('/')}$trimmed"
            else -> trimmed
        }
    }

    fun parseResolveJson(jsonText: String, baseUrl: String): BrFlixResolveResult {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return BrFlixResolveResult(emptyList(), emptyList())
        }

        val streams = mutableListOf<ResolvedStream>()
        val seenUrls = mutableSetOf<String>()

        fun addStream(raw: String, label: String) {
            val unwrapped = unwrapStreamProxyUrl(raw, baseUrl)
            if (unwrapped.isBlank() || !seenUrls.add(unwrapped)) return
            val ref = if (unwrapped.contains("cloudfront-net.lat") || unwrapped.endsWith(".txt")) {
                "https://w1.ebtv.lat/"
            } else {
                "${baseUrl.trimEnd('/')}/"
            }
            streams.add(ResolvedStream(label = label, url = unwrapped, referer = ref))
        }

        val directArr: JSONArray? = root.optJSONArray("directStreams")
        if (directArr != null) {
            for (i in 0 until directArr.length()) {
                val obj = directArr.optJSONObject(i) ?: continue
                val src = obj.optString("src").ifBlank { obj.optString("proxiedUrl") }
                val label = obj.optString("label").ifBlank {
                    obj.optString("provider").ifBlank { "Sinal ${i + 1}" }
                }
                addStream(src, label)
            }
        }

        val hlsUrl = root.optString("hlsUrl")
        if (hlsUrl.isNotBlank()) {
            addStream(hlsUrl, root.optString("hlsProvider").ifBlank { "BRFlix HD" })
        }

        val fallbacks = mutableListOf<Pair<String, String>>()
        val fbArr = root.optJSONArray("fallbackEmbeds")
        if (fbArr != null) {
            for (i in 0 until fbArr.length()) {
                val obj = fbArr.optJSONObject(i) ?: continue
                val embedUrl = obj.optString("embedUrl").replace("\\/", "/").trim()
                val provider = obj.optString("provider").trim().ifBlank { "Servidor ${i + 1}" }
                if (embedUrl.startsWith("http")) {
                    fallbacks.add(provider to embedUrl)
                }
            }
        }

        return BrFlixResolveResult(directStreams = streams, fallbackEmbeds = fallbacks)
    }

    fun extractStreamFromEmbedHtml(html: String): String? {
        val src = CONST_SRC_REGEX.find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()
        if (!src.isNullOrBlank()) return src
        return M3U8_REGEX.find(html)?.value?.replace("\\/", "/")?.trim()
    }
}
