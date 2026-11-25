package com.kairou

import org.json.JSONObject
import java.net.URLDecoder

internal data class KairouItem(
    val id: Int,
    val mediaType: String, // "movie" or "tv"
    val title: String,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: Int?,
    val rating: Double?,
    val isAnime: Boolean
)

internal data class KairouEpisode(
    val season: Int,
    val episode: Int,
    val name: String,
    val overview: String?,
    val posterUrl: String?
)

internal data class KairouSubtitle(
    val label: String,
    val url: String
)

internal data class KairouSource(
    val label: String,
    val url: String,
    val isM3u8: Boolean,
    val isDash: Boolean = false,
    val height: Int,
    val referer: String? = null
)

internal data class KairouExtractResult(
    val sources: List<KairouSource>,
    val subtitles: List<KairouSubtitle>
)

internal object KairouParser {

    private const val TMDB_IMG_BASE = "https://image.tmdb.org/t/p"

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

    fun parseTmdbList(jsonText: String, forcedMediaType: String? = null): Pair<List<KairouItem>, Boolean> {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return emptyList<KairouItem>() to false
        }

        val page = root.optInt("page", 1)
        val totalPages = root.optInt("total_pages", 1)
        val arr = root.optJSONArray("results") ?: return emptyList<KairouItem>() to false
        val items = mutableListOf<KairouItem>()

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
            val rating = obj.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
            val origLang = obj.optString("original_language").lowercase()
            val genreIds = obj.optJSONArray("genre_ids")
            var hasAnimation = false
            if (genreIds != null) {
                for (g in 0 until genreIds.length()) {
                    if (genreIds.optInt(g) == 16) hasAnimation = true
                }
            }

            items.add(
                KairouItem(
                    id = id,
                    mediaType = mediaType,
                    title = title,
                    posterUrl = tmdbImage(obj.optString("poster_path"), "w500"),
                    backdropUrl = tmdbImage(obj.optString("backdrop_path"), "w1280"),
                    year = year,
                    rating = rating,
                    isAnime = hasAnimation && origLang == "ja"
                )
            )
        }

        return items.distinctBy { "${it.mediaType}:${it.id}" } to (page < totalPages)
    }

    fun parseSeasonEpisodes(jsonText: String, seasonNumber: Int): List<KairouEpisode> {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return emptyList()
        }
        val arr = root.optJSONArray("episodes") ?: return emptyList()
        val list = mutableListOf<KairouEpisode>()

        for (i in 0 until arr.length()) {
            val ep = arr.optJSONObject(i) ?: continue
            val num = ep.optInt("episode_number", i + 1)
            val name = ep.optString("name").trim().ifBlank { "Episódio $num" }
            val overview = ep.optString("overview").trim().ifBlank { null }
            val still = tmdbImage(ep.optString("still_path"), "w500")
            list.add(
                KairouEpisode(
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

    fun parseExtractResponse(jsonText: String, baseUrl: String): KairouExtractResult {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return KairouExtractResult(emptyList(), emptyList())
        }

        val sources = mutableListOf<KairouSource>()
        val subtitles = mutableListOf<KairouSubtitle>()
        val seenUrls = mutableSetOf<String>()
        val seenSubs = mutableSetOf<String>()

        val arr = root.optJSONArray("sources")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val rawUrl = obj.optString("url").ifBlank { obj.optString("proxiedUrl") }.trim()
                val resolvedUrl = resolveUrl(rawUrl, baseUrl)
                if (resolvedUrl.isBlank()) continue

                val provider = obj.optString("provider").trim().lowercase()
                // kairou.space/api/proxy corrupts Solstice fMP4 segments because route.transcode.cfd
                // serves binary fMP4 segments under a .m3u8 path (proxy rewrites binary bytes as text).
                // Unwrap to the direct upstream URL + Referer so ExoPlayer receives clean fMP4 segments.
                val unwrapped = if (provider == "solstice" || resolvedUrl.contains("m3u8-proxy.m3u8", ignoreCase = true)) {
                    unwrapProxyUrl(resolvedUrl)
                } else {
                    null
                }
                val fullUrl = unwrapped?.first ?: resolvedUrl
                val customReferer = unwrapped?.second
                if (!seenUrls.add(fullUrl)) continue

                val rawLabel = obj.optString("serverLabel").ifBlank {
                    obj.optString("title").ifBlank { obj.optString("provider", "Kairou ${i + 1}") }
                }.trim()
                val hasOriginalAudio = run {
                    val tracks = obj.optJSONArray("audioTracks") ?: return@run false
                    (0 until tracks.length()).any { idx ->
                        val t = tracks.optJSONObject(idx) ?: return@any false
                        val lang = t.optString("language").lowercase()
                        val tLabel = t.optString("label").lowercase()
                        lang == "en" || tLabel.contains("original")
                    }
                }
                val label = if ((provider == "zephyr" || provider == "vortex" || provider == "solstice" || hasOriginalAudio) &&
                    !rawLabel.contains("dub", ignoreCase = true) &&
                    !rawLabel.contains("leg", ignoreCase = true) &&
                    !rawLabel.contains("original", ignoreCase = true)
                ) {
                    "$rawLabel · Legendado (Original)"
                } else {
                    rawLabel
                }

                val kind = obj.optString("kind").trim().lowercase()
                val isDash = kind == "dash" ||
                    fullUrl.contains(".mpd", ignoreCase = true) ||
                    fullUrl.contains("manifest.mpd", ignoreCase = true)
                val isM3u8 = !isDash && (
                    kind == "hls" ||
                        fullUrl.contains(".m3u8", ignoreCase = true) ||
                        fullUrl.contains("playlist.m3u8", ignoreCase = true) ||
                        fullUrl.contains("nuvio-media", ignoreCase = true)
                    )

                val height = obj.optInt("height", 0).takeIf { it > 0 } ?: inferHeight(
                    obj.optString("resolution").ifBlank { label }
                )

                sources.add(
                    KairouSource(
                        label = label,
                        url = fullUrl,
                        isM3u8 = isM3u8,
                        isDash = isDash,
                        height = height,
                        referer = customReferer
                    )
                )

                val subArr = obj.optJSONArray("subtitles")
                if (subArr != null) {
                    for (s in 0 until subArr.length()) {
                        val sub = subArr.optJSONObject(s) ?: continue
                        val subUrl = resolveUrl(sub.optString("url"), baseUrl)
                        if (subUrl.isBlank() || !seenSubs.add(subUrl)) continue
                        val subLabel = sub.optString("label").ifBlank {
                            sub.optString("language", "Português")
                        }.trim()
                        subtitles.add(KairouSubtitle(label = subLabel, url = subUrl))
                    }
                }
            }
        }

        return KairouExtractResult(sources = sources, subtitles = subtitles)
    }

    fun mergeExtractResults(results: List<KairouExtractResult>): KairouExtractResult {
        val sources = mutableListOf<KairouSource>()
        val subtitles = mutableListOf<KairouSubtitle>()
        val seenUrls = mutableSetOf<String>()
        val seenSubs = mutableSetOf<String>()
        for (res in results) {
            for (src in res.sources) {
                if (seenUrls.add(src.url)) sources.add(src)
            }
            for (sub in res.subtitles) {
                if (seenSubs.add(sub.url)) subtitles.add(sub)
            }
        }
        return KairouExtractResult(sources = sources, subtitles = subtitles)
    }

    fun parseOpenSubtitlesJson(jsonText: String): List<KairouSubtitle> {
        val root = try {
            JSONObject(jsonText)
        } catch (_: Exception) {
            return emptyList()
        }
        val arr = root.optJSONArray("subtitles") ?: return emptyList()
        val buckets = linkedMapOf<String, MutableList<KairouSubtitle>>(
            "pob" to mutableListOf(),
            "por" to mutableListOf(),
            "eng" to mutableListOf(),
            "spa" to mutableListOf()
        )
        val langLabels = mapOf(
            "pob" to "Português (Brasil)",
            "por" to "Português",
            "eng" to "Inglês",
            "spa" to "Espanhol"
        )
        val seenUrls = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val rawLang = obj.optString("lang").trim().lowercase()
            val normLang = when (rawLang) {
                "pob", "pt-br", "pt_br", "pb" -> "pob"
                "por", "pt" -> "por"
                "eng", "en" -> "eng"
                "spa", "es" -> "spa"
                else -> continue
            }
            val url = obj.optString("url").trim()
            if (!url.startsWith("http") || !seenUrls.add(url)) continue
            val bucket = buckets[normLang] ?: continue
            if (bucket.size >= 3) continue
            val baseLabel = langLabels[normLang] ?: normLang.uppercase()
            val label = if (bucket.isEmpty()) baseLabel else "$baseLabel #${bucket.size + 1}"
            bucket.add(KairouSubtitle(label = label, url = url))
        }
        return buckets.values.flatten().take(8)
    }

    fun unwrapProxyUrl(proxyUrl: String): Pair<String, String?>? {
        val query = proxyUrl.substringAfter("?", "").ifBlank { return null }
        var targetUrl: String? = null
        var targetRef: String? = null
        for (pair in query.split("&")) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = pair.substring(0, idx)
            val value = try {
                URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
            } catch (_: Exception) {
                pair.substring(idx + 1)
            }
            when (key) {
                "url" -> if (value.startsWith("http://") || value.startsWith("https://")) targetUrl = value
                "referer" -> if (value.startsWith("http://") || value.startsWith("https://")) targetRef = value
            }
        }
        return targetUrl?.let { it to targetRef }
    }

    private fun inferHeight(text: String): Int {
        val lower = text.lowercase()
        return when {
            lower.contains("4k") || lower.contains("2160") -> 2160
            lower.contains("1080") -> 1080
            lower.contains("720") -> 720
            lower.contains("480") -> 480
            else -> 1080
        }
    }
}
