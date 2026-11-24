package com.brflix

import org.json.JSONArray
import org.json.JSONObject

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

internal object BRFlixVodParser {

    private const val TMDB_IMG_BASE = "https://image.tmdb.org/t/p"
    private val SERVERS_REGEX = Regex("""servers:\s*(\[[\s\S]*?\])\s*,""", RegexOption.IGNORE_CASE)

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

        // Prioritize SpeedNET first (verified reliable HLS), keep original order for others
        return servers.sortedBy { if (it.label.contains("speednet", ignoreCase = true)) 0 else 1 }
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

    fun isPlayableHlsManifest(manifestText: String): Boolean {
        val trimmed = manifestText.trim()
        if (!trimmed.startsWith("#EXTM3U", ignoreCase = true)) return false
        return trimmed.contains("#EXTINF:", ignoreCase = true) ||
            trimmed.contains("#EXT-X-STREAM-INF", ignoreCase = true) ||
            trimmed.contains("#EXT-X-MEDIA:", ignoreCase = true)
    }

    fun looksLikeHtmlError(contentType: String, bodyStart: String): Boolean {
        if (contentType.contains("text/html", ignoreCase = true)) return true
        val trimmed = bodyStart.trimStart()
        return trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
            trimmed.startsWith("<html", ignoreCase = true)
    }
}
