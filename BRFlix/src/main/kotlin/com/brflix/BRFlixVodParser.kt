package com.brflix

import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class BrFlixVodItem(
    val id: Int,
    val mediaType: String, // "movie" or "tv"
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: Int?,
    val isAnime: Boolean
)

internal data class BrFlixEpisode(
    val season: Int,
    val episode: Int,
    val name: String,
    val overview: String?,
    val posterUrl: String?
)

internal data class ClickhostServer(
    val id: Long,
    val label: String,
    val isHls: Boolean,
    val directUrl: String?
)

internal data class ClickhostInitResult(
    val url: String,
    val isHls: Boolean
)

internal data class BrFlixResolvedStream(
    val label: String,
    val url: String,
    val isHls: Boolean,
    val quality: Int,
    val referer: String,
    val headers: Map<String, String> = emptyMap()
)

internal data class BrFlixSubtitle(
    val label: String,
    val url: String
)

internal data class BrFlixZeusResult(
    val streams: List<BrFlixResolvedStream>,
    val subtitles: List<BrFlixSubtitle>
)

internal object BRFlixVodParser {

    private const val TMDB_IMG_BASE = "https://image.tmdb.org/t/p"
    private const val ZEUS_ENC_KEY_HEX = "4f9c2d1e8a7b6c5d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d"
    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val SERVERS_REGEX = Regex("""servers:\s*(\[[\s\S]*?\])\s*,""", RegexOption.IGNORE_CASE)

    enum class ProbeVerdict {
        HLS,
        VIDEO,
        REJECT
    }

    fun resolveUrl(raw: String?, baseUrl: String): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isBlank()) return ""
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:$trimmed"
        val cleanBase = baseUrl.trimEnd('/')
        return if (trimmed.startsWith("/")) "$cleanBase$trimmed" else "$cleanBase/$trimmed"
    }

    fun tmdbImage(path: String?, size: String = "w500"): String? {
        val clean = path?.trim().orEmpty()
        if (clean.isBlank() || clean == "null") return null
        return if (clean.startsWith("http")) clean else "$TMDB_IMG_BASE/$size$clean"
    }

    fun looksLikeJsonObject(text: String?): Boolean {
        val trimmed = text?.trimStart().orEmpty()
        return trimmed.startsWith("{")
    }

    fun mapClickhostTvEpisode(tmdbId: Int, season: Int, episode: Int): Pair<Int, Int> {
        if (tmdbId == 37854) {
            return when {
                season == 21 && episode > 250 -> 21 to (episode - 891)
                season == 22 && episode > 100 -> 22 to (episode - 1088)
                season == 23 && episode > 100 -> 23 to (episode - 1155)
                else -> season to episode
            }
        }
        return season to episode
    }

    fun parseTmdbList(jsonText: String, forcedMediaType: String? = null): Pair<List<BrFlixVodItem>, Boolean> {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return emptyList<BrFlixVodItem>() to false
        }

        val page = root.optInt("page", 1)
        val totalPages = root.optInt("total_pages", 1)
        val arr = root.optJSONArray("results") ?: return emptyList<BrFlixVodItem>() to false
        val items = mutableListOf<BrFlixVodItem>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val mediaType = forcedMediaType ?: obj.optString("media_type").trim().lowercase().ifBlank {
                if (obj.has("title")) "movie" else "tv"
            }
            if (mediaType != "movie" && mediaType != "tv") continue

            val id = obj.optInt("id", 0)
            if (id <= 0) continue

            val title = obj.optString("title").ifBlank {
                obj.optString("name").ifBlank {
                    obj.optString("original_title").ifBlank { obj.optString("original_name") }
                }
            }.trim()
            if (title.isBlank()) continue

            val date = obj.optString("release_date").ifBlank { obj.optString("first_air_date") }
            val year = date.take(4).toIntOrNull()
            val origLang = obj.optString("original_language").lowercase()
            val genreIds = obj.optJSONArray("genre_ids")
            var hasAnimation = false
            if (genreIds != null) {
                for (g in 0 until genreIds.length()) {
                    if (genreIds.optInt(g) == 16) hasAnimation = true
                }
            }

            items.add(
                BrFlixVodItem(
                    id = id,
                    mediaType = mediaType,
                    title = title,
                    posterUrl = tmdbImage(obj.optString("poster_path"), "w500"),
                    backdropUrl = tmdbImage(obj.optString("backdrop_path"), "w1280"),
                    year = year,
                    isAnime = hasAnimation && origLang == "ja"
                )
            )
        }

        return items.distinctBy { "${it.mediaType}:${it.id}" } to (page < totalPages)
    }

    fun parseSeasonEpisodes(jsonText: String, seasonNumber: Int): List<BrFlixEpisode> {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return emptyList()
        }
        val arr = root.optJSONArray("episodes") ?: return emptyList()
        val list = mutableListOf<BrFlixEpisode>()

        for (i in 0 until arr.length()) {
            val ep = arr.optJSONObject(i) ?: continue
            val num = ep.optInt("episode_number", i + 1)
            val name = ep.optString("name").trim().ifBlank { "Episódio $num" }
            val overview = ep.optString("overview").trim().ifBlank { null }
            val still = tmdbImage(ep.optString("still_path"), "w500")
            list.add(
                BrFlixEpisode(
                    season = seasonNumber,
                    episode = num,
                    name = name,
                    overview = overview,
                    posterUrl = still
                )
            )
        }
        return list
    }

    fun parseClickhostServers(embedHtml: String): List<ClickhostServer> {
        val rawArray = SERVERS_REGEX.find(embedHtml)?.groupValues?.get(1) ?: return emptyList()
        val arr = try {
            JSONArray(rawArray)
        } catch (_: Exception) {
            return emptyList()
        }

        val servers = mutableListOf<ClickhostServer>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optBoolean("down", false)) continue
            val id = obj.optLong("id", 0L)
            val direct = obj.optString("url").takeIf { it.isNotBlank() && it != "null" }
            if (id <= 0L && direct == null) continue
            val label = obj.optString("label").trim().ifBlank { "BRFlix ${i + 1}" }
            servers.add(
                ClickhostServer(
                    id = id,
                    label = label,
                    isHls = obj.optBoolean("is_hls", false),
                    directUrl = direct
                )
            )
        }

        // Prioritize reliable direct/VOD servers first (SpeedNET, UniTV, FênixHub, Manual)
        return servers.sortedBy {
            val lbl = it.label.lowercase()
            when {
                lbl.contains("speednet") -> 0
                lbl.contains("unitv") -> 1
                lbl.contains("fênix") || lbl.contains("fenix") -> 2
                lbl.contains("manual") -> 3
                else -> 4
            }
        }
    }

    fun parseClickhostInit(jsonText: String, embedOrigin: String): ClickhostInitResult? {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return null
        }
        if (!root.optBoolean("ok", true)) return null
        val rawUrl = root.optString("url").ifBlank { root.optString("streamUrl") }.trim()
        val fullUrl = resolveUrl(rawUrl, embedOrigin)
        if (fullUrl.isBlank()) return null
        val isHls = root.optBoolean("is_hls", false) || fullUrl.contains(".m3u8", ignoreCase = true)
        return ClickhostInitResult(url = fullUrl, isHls = isHls)
    }

    fun classifyStreamHead(
        statusCode: Int,
        contentType: String,
        contentLength: Long?,
        initIsHls: Boolean,
        url: String
    ): ProbeVerdict {
        if (statusCode == 405 || statusCode == 501) {
            return if (initIsHls || url.contains(".m3u8", ignoreCase = true)) ProbeVerdict.HLS else ProbeVerdict.VIDEO
        }
        if (statusCode !in 200..299) return ProbeVerdict.REJECT

        val ct = contentType.lowercase()
        if (ct.contains("text/html") || ct.contains("text/plain") || ct.contains("application/json")) {
            return ProbeVerdict.REJECT
        }
        // Reject unseekable Matroska streams (e.g. Clickhost Manual MKV that ignores Range requests)
        if (ct.contains("matroska") || ct.contains("x-mkv")) {
            return ProbeVerdict.REJECT
        }

        if (ct.startsWith("video/") || ct.contains("octet-stream")) {
            return ProbeVerdict.VIDEO
        }

        val isHls = initIsHls || ct.contains("mpegurl") || url.contains(".m3u8", ignoreCase = true)
        if (isHls) {
            // Reject empty/stub HLS playlists such as MegaVODS's 33-byte "#EXTM3U\n#EXT-X-TARGETDURATION:10\n"
            if (contentLength != null && contentLength < 64L) return ProbeVerdict.REJECT
            return ProbeVerdict.HLS
        }

        return ProbeVerdict.VIDEO
    }

    fun isPlayableHlsManifest(manifestText: String): Boolean {
        val trimmed = manifestText.trim()
        if (!trimmed.startsWith("#EXTM3U", ignoreCase = true)) return false
        return trimmed.contains("#EXTINF:", ignoreCase = true) ||
            trimmed.contains("#EXT-X-STREAM-INF", ignoreCase = true) ||
            trimmed.contains("#EXT-X-MEDIA:", ignoreCase = true)
    }

    internal fun decodeBase64Bytes(input: String): ByteArray? {
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
        return out.toByteArray()
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun decryptZeusPayload(packed: String): String? {
        val parts = packed.trim().split(":")
        if (parts.size != 3) return null
        val iv = decodeBase64Bytes(parts[0]) ?: return null
        val tag = decodeBase64Bytes(parts[1]) ?: return null
        val cipherBytes = decodeBase64Bytes(parts[2]) ?: return null
        return try {
            val combined = ByteArray(cipherBytes.size + tag.size)
            System.arraycopy(cipherBytes, 0, combined, 0, cipherBytes.size)
            System.arraycopy(tag, 0, combined, cipherBytes.size, tag.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(hexToBytes(ZEUS_ENC_KEY_HEX), "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
            String(cipher.doFinal(combined), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun parseZeusResponse(jsonText: String, baseUrl: String): BrFlixZeusResult {
        val outer = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return BrFlixZeusResult(emptyList(), emptyList())
        }

        val root = if (outer.optBoolean("enc", false) && outer.has("payload")) {
            val decrypted = decryptZeusPayload(outer.optString("payload")) ?: return BrFlixZeusResult(emptyList(), emptyList())
            try {
                JSONObject(decrypted)
            } catch (_: Exception) {
                return BrFlixZeusResult(emptyList(), emptyList())
            }
        } else {
            outer
        }

        if (!root.optBoolean("ok", true)) return BrFlixZeusResult(emptyList(), emptyList())

        val streams = mutableListOf<BrFlixResolvedStream>()
        val subtitles = mutableListOf<BrFlixSubtitle>()
        val seenUrls = mutableSetOf<String>()
        val seenSubs = mutableSetOf<String>()

        fun addCandidate(obj: JSONObject, idx: Int) {
            val rawDirect = obj.optString("rawUrl").trim()
            val proxied = resolveUrl(obj.optString("url"), baseUrl)
            val chosenUrl = when {
                rawDirect.startsWith("http://") || rawDirect.startsWith("https://") -> rawDirect
                proxied.startsWith("http://") || proxied.startsWith("https://") -> proxied
                else -> ""
            }
            if (chosenUrl.isBlank() || !seenUrls.add(chosenUrl)) return

            val type = obj.optString("type").trim().lowercase()
            val isHls = type == "hls" || chosenUrl.contains(".m3u8", ignoreCase = true)
            val quality = obj.optInt("height", 0).takeIf { it > 0 }
                ?: obj.optInt("quality", 0).takeIf { it > 0 }
                ?: 1080
            val dubbed = obj.optBoolean("dubbed", false)
            val audioLabel = if (dubbed) "Dublado" else "Legendado"
            val label = "BRFlix • Ultra HD (${quality}p $audioLabel)"

            val hdrs = mutableMapOf<String, String>()
            val hdrObj = obj.optJSONObject("headers")
            if (hdrObj != null) {
                val keys = hdrObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = hdrObj.optString(k).trim()
                    if (k.isNotBlank() && v.isNotBlank()) hdrs[k] = v
                }
            }
            val ref = hdrs["Referer"] ?: hdrs["referer"] ?: if (chosenUrl.startsWith(baseUrl)) "$baseUrl/" else ""

            streams.add(
                BrFlixResolvedStream(
                    label = if (idx == 0) label else "$label #${idx + 1}",
                    url = chosenUrl,
                    isHls = isHls,
                    quality = quality,
                    referer = ref,
                    headers = hdrs
                )
            )

            val subArr = obj.optJSONArray("subtitles")
            if (subArr != null) {
                for (s in 0 until subArr.length()) {
                    val sub = subArr.optJSONObject(s) ?: continue
                    val subUrl = resolveUrl(sub.optString("url"), baseUrl)
                    if (subUrl.isBlank() || !seenSubs.add(subUrl)) continue
                    val subLabel = sub.optString("label").ifBlank { sub.optString("lang", "Português") }.trim()
                    subtitles.add(BrFlixSubtitle(label = subLabel, url = subUrl))
                }
            }
        }

        val candidates = root.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            for (i in 0 until candidates.length()) {
                val c = candidates.optJSONObject(i) ?: continue
                addCandidate(c, i)
            }
        } else {
            addCandidate(root, 0)
        }

        return BrFlixZeusResult(streams = streams, subtitles = subtitles)
    }

    fun parseFenixFlixStreams(jsonText: String): List<BrFlixResolvedStream> {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return emptyList()
        }
        val arr = root.optJSONArray("streams") ?: return emptyList()
        val streams = mutableListOf<BrFlixResolvedStream>()
        val seenUrls = mutableSetOf<String>()

        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val url = obj.optString("url").trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            if (!seenUrls.add(url)) continue

            val rawName = obj.optString("name").trim()
            val rawTitle = obj.optString("title").trim()
            val filename = obj.optJSONObject("behaviorHints")?.optString("filename").orEmpty()
            val metaText = "$rawName $rawTitle $filename"

            val quality = inferQuality(metaText)
            val isHls = url.contains(".m3u8", ignoreCase = true)

            val titleLines = rawTitle.lines().map { it.trim() }.filter { it.isNotBlank() }
            val audioTag = titleLines.firstOrNull {
                it.contains("Dublado", ignoreCase = true) ||
                    it.contains("Dual", ignoreCase = true) ||
                    it.contains("Legendado", ignoreCase = true)
            }?.replace(Regex("""[^\p{L}\p{N}\s/+-]"""), "")?.trim().orEmpty()

            val hostTag = titleLines.lastOrNull()
                ?.takeIf { titleLines.size >= 2 && it != audioTag }
                ?.replace(Regex("""[^\p{L}\p{N}\s._-]"""), "")
                ?.trim()
                .orEmpty()

            val qLabel = when (quality) {
                2160 -> "4K"
                1080 -> "1080p"
                720 -> "720p"
                480 -> "480p"
                else -> "HD"
            }
            val labelParts = listOfNotNull(
                "BRFlix",
                hostTag.ifBlank { "FenixFlix" },
                listOf(qLabel, audioTag).filter { it.isNotBlank() }.joinToString(" ")
            )
            val label = labelParts.joinToString(" • ")

            streams.add(
                BrFlixResolvedStream(
                    label = label,
                    url = url,
                    isHls = isHls,
                    quality = quality,
                    referer = "",
                    headers = emptyMap()
                )
            )
        }

        // Prioritize Dublado/Dual Áudio + higher resolution first, cap at 8 candidates
        return streams.sortedWith(
            compareByDescending<BrFlixResolvedStream> {
                it.label.contains("Dublado", ignoreCase = true) || it.label.contains("Dual", ignoreCase = true)
            }.thenByDescending { it.quality }
        ).take(8)
    }

    private fun inferQuality(text: String): Int {
        val lower = text.lowercase()
        return when {
            lower.contains("4k") || lower.contains("2160") -> 2160
            lower.contains("1080") -> 1080
            lower.contains("720") -> 720
            lower.contains("480") || lower.contains(" sd") -> 480
            lower.contains("cam") -> 360
            else -> 1080
        }
    }
}
