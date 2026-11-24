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

internal data class ClearKeyStream(
    val url: String,
    val kidBase64Url: String,
    val keyBase64Url: String
)

internal object TVACaboParser {

    private val BLOCKED_KEYWORDS = listOf(
        "extra +18", "+18", "aviso.html", "sexy hot", "sexyhot", "playboy", "venus", "sextreme", "adulto", "xxx"
    )
    private val TIME_REGEX = Regex("""\b(\d{2}:\d{2})\b""")
    private val SPORTS_API_REGEX = Regex("""https?://[^\s"'<>\\]+/sports\b""", RegexOption.IGNORE_CASE)
    private val DIRECT_SRC_REGEX = Regex("""(?:const|var|let)\s+(?:src|SRC)\s*=\s*["'](https?://[^"']+)["']""")
    private val M3U8_REGEX = Regex("""https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
    private val DATA_C_REGEX = Regex("""data-c=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    private val OBF_ARRAY_REGEX = Regex("""var\s+\w+\s*=\s*\[([\s\S]*?)\]\s*;""")
    private val OBF_ITEM_REGEX = Regex(""""([A-Za-z0-9+/=]{8,})"""")
    private val OBF_OFFSET_REGEX = Regex("""fromCharCode\s*\([\s\S]*?-\s*(\d+)\s*\)""")
    private val WINDOW_URL_REGEX = Regex("""window\.url\s*=\s*["'](https?://[^"']+)["']""")
    private val WINDOW_CK_ID_REGEX = Regex("""window\.ck_id\s*=\s*["']([^"']+)["']""")
    private val WINDOW_CK_KEY_REGEX = Regex("""window\.ck_key\s*=\s*["']([^"']+)["']""")

    private const val B64_STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val B64_URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

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

    internal fun decodeBase64Bytes(input: String): ByteArray? {
        val clean = input.trim().replace("-", "+").replace("_", "/").filter { !it.isWhitespace() }
        if (clean.isEmpty()) return null
        val out = ArrayList<Byte>((clean.length * 3) / 4)
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') break
            val v = B64_STD.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.add(((buffer ushr bits) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }

    internal fun encodeBase64UrlNoPad(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size * 4 + 2) / 3)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val rem = bytes.size - i
            sb.append(B64_URL[(b0 ushr 2) and 0x3F])
            sb.append(B64_URL[((b0 and 0x03) shl 4) or ((b1 ushr 4) and 0x0F)])
            if (rem > 1) sb.append(B64_URL[((b1 and 0x0F) shl 2) or ((b2 ushr 6) and 0x03)])
            if (rem > 2) sb.append(B64_URL[b2 and 0x3F])
            i += 3
        }
        return sb.toString()
    }

    fun hexToBase64Url(hex: String): String? {
        val clean = hex.trim().replace("-", "")
        if (clean.length != 32 || clean.any { it.digitToIntOrNull(16) == null }) return null
        val bytes = ByteArray(16)
        for (i in 0 until 16) {
            bytes[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return encodeBase64UrlNoPad(bytes)
    }

    fun decodeObfuscatedCharArray(html: String): String? {
        if (!html.contains("fromCharCode") || !html.contains("atob")) return null
        val offset = OBF_OFFSET_REGEX.find(html)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val arrayBody = OBF_ARRAY_REGEX.find(html)?.groupValues?.get(1) ?: return null
        val items = OBF_ITEM_REGEX.findAll(arrayBody).map { it.groupValues[1] }.toList()
        if (items.size < 20) return null

        val sb = StringBuilder(items.size)
        for (item in items) {
            val bytes = decodeBase64Bytes(item) ?: return null
            val digits = String(bytes, Charsets.UTF_8).filter { it.isDigit() }
            val num = digits.toLongOrNull() ?: return null
            val chCode = (num - offset).toInt()
            if (chCode < 0 || chCode > 0xFFFF) return null
            sb.append(chCode.toChar())
        }
        return sb.toString()
    }

    fun extractClearKeyDash(html: String): ClearKeyStream? {
        val url = WINDOW_URL_REGEX.find(html)?.groupValues?.get(1)?.replace("\\/", "/")?.trim()
            ?: return null
        val rawId = WINDOW_CK_ID_REGEX.find(html)?.groupValues?.get(1)?.trim() ?: return null
        val rawKey = WINDOW_CK_KEY_REGEX.find(html)?.groupValues?.get(1)?.trim() ?: return null
        val kid = hexToBase64Url(rawId) ?: rawId
        val key = hexToBase64Url(rawKey) ?: rawKey
        if (kid.isBlank() || key.isBlank()) return null
        return ClearKeyStream(url = url, kidBase64Url = kid, keyBase64Url = key)
    }

    fun extractDataCStreams(html: String): List<String> {
        val raw = DATA_C_REGEX.find(html)?.groupValues?.get(1)?.trim() ?: return emptyList()
        val decodedBytes = decodeBase64Bytes(raw.reversed()) ?: return emptyList()
        val jsonText = String(decodedBytes, Charsets.UTF_8)
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return emptyList()
        }
        val arr = root.optJSONArray("u") ?: return emptyList()
        val urls = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val u = arr.optString(i).replace("\\/", "/").trim()
            if (u.startsWith("http://") || u.startsWith("https://")) {
                urls.add(u)
            }
        }
        return urls.distinct()
    }
}
