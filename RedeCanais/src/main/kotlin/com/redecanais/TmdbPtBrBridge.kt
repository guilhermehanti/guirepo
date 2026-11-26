package com.redecanais

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.abs

internal object TmdbPtBrBridge {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun isTmdbInput(rawUrl: String): Boolean {
        val t = rawUrl.trim()
        return t.startsWith("{") || t.contains("themoviedb.org/movie/", true) || t.contains("themoviedb.org/tv/", true)
    }

    suspend fun resolveUrl(
        rawUrl: String,
        searchFn: suspend (String) -> List<SearchResponse>
    ): String? {
        val (tmdbId, isMovie) = parseTmdbTarget(rawUrl) ?: return null
        val meta = fetchTmdbMeta(tmdbId, isMovie) ?: return null

        val queries = buildList {
            if (meta.ptTitle.length >= 2) add(meta.ptTitle)
            val ptBase = meta.ptTitle.substringBefore(":").substringBefore(" - ").trim()
            if (ptBase.length >= 2 && ptBase !in this) add(ptBase)
            if (meta.origTitle.length >= 2 && meta.origTitle !in this) add(meta.origTitle)
        }

        val targetNorms = buildList {
            normalize(meta.ptTitle).takeIf { it.isNotBlank() }?.let { add(it) }
            normalize(meta.origTitle).takeIf { it.isNotBlank() && it !in this }?.let { add(it) }
        }
        if (targetNorms.isEmpty()) return null

        var bestUrl: String? = null
        var bestScore = 0.0

        for (q in queries) {
            val results = try {
                searchFn(q)
            } catch (_: Exception) {
                emptyList()
            }
            for (res in results) {
                if (!matchesMediaType(res.type, isMovie)) continue
                val score = scoreCandidate(res, targetNorms, meta.year)
                if (score > bestScore) {
                    bestScore = score
                    bestUrl = res.url
                }
            }
            if (bestScore >= 0.92) break
        }

        return if (bestScore >= 0.72) bestUrl else null
    }

    private data class TmdbMeta(val ptTitle: String, val origTitle: String, val year: Int?)

    private fun parseTmdbTarget(rawUrl: String): Pair<Int, Boolean>? {
        val t = rawUrl.trim()
        if (t.startsWith("{")) {
            val obj = runCatching { JSONObject(t) }.getOrNull() ?: return null
            val id = obj.optInt("id", 0).takeIf { it > 0 }
                ?: obj.optInt("tmdbID", 0).takeIf { it > 0 }
                ?: return null
            val type = obj.optString("type").lowercase()
            val isMovie = type == "movie" || obj.optBoolean("isMovie", false)
            return id to isMovie
        }
        if (t.contains("themoviedb.org/movie/", true) || t.contains("themoviedb.org/tv/", true)) {
            val isMovie = t.contains("/movie/", true)
            val seg = t.substringBefore("?").trimEnd('/').substringAfterLast("/")
            val id = seg.substringBefore("-").toIntOrNull() ?: return null
            return id to isMovie
        }
        return null
    }

    private suspend fun fetchTmdbMeta(tmdbId: Int, isMovie: Boolean): TmdbMeta? {
        val kind = if (isMovie) "movie" else "tv"
        val urls = listOf(
            "https://kairou.space/api/tmdb/$kind/$tmdbId?language=pt-BR" to "https://kairou.space/",
            "https://brflix.lat/tmdb-cached/$kind/$tmdbId?language=pt-BR" to "https://brflix.lat/"
        )
        for ((u, ref) in urls) {
            val text = try {
                app.get(
                    u,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "application/json, */*",
                        "Referer" to ref
                    ),
                    timeout = 8L
                ).text
            } catch (_: Exception) {
                continue
            }
            val obj = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val ptTitle = if (isMovie) {
                obj.optString("title").ifBlank { obj.optString("original_title") }.trim()
            } else {
                obj.optString("name").ifBlank { obj.optString("original_name") }.trim()
            }
            val origTitle = if (isMovie) {
                obj.optString("original_title").ifBlank { ptTitle }.trim()
            } else {
                obj.optString("original_name").ifBlank { ptTitle }.trim()
            }
            val yearStr = if (isMovie) obj.optString("release_date") else obj.optString("first_air_date")
            val year = yearStr.take(4).toIntOrNull()
            if (ptTitle.isNotBlank()) return TmdbMeta(ptTitle, origTitle, year)
        }
        return null
    }

    private fun matchesMediaType(type: TvType?, isMovie: Boolean): Boolean {
        if (type == null) return true
        return if (isMovie) {
            type == TvType.Movie || type == TvType.AnimeMovie || type == TvType.Documentary
        } else {
            type == TvType.TvSeries || type == TvType.Anime || type == TvType.Cartoon || type == TvType.OVA || type == TvType.AsianDrama
        }
    }

    private fun scoreCandidate(res: SearchResponse, targetNorms: List<String>, targetYear: Int?): Double {
        val candNorm = normalize(res.name)
        if (candNorm.isBlank()) return 0.0
        var bestSim = 0.0
        for (target in targetNorms) {
            val sim = similarity(candNorm, target)
            if (sim > bestSim) bestSim = sim
        }
        val respYear = when (res) {
            is MovieSearchResponse -> res.year
            is TvSeriesSearchResponse -> res.year
            is AnimeSearchResponse -> res.year
            else -> null
        }
        val candYear = extractYear(res.name) ?: respYear ?: extractYear(res.url)
        if (targetYear != null && candYear != null) {
            val diff = abs(targetYear - candYear)
            when {
                diff == 0 -> bestSim += 0.08
                diff == 1 -> bestSim += 0.04
                diff >= 2 -> bestSim -= 0.35
            }
        }
        return bestSim
    }

    private fun extractYear(title: String): Int? =
        Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.groupValues?.get(1)?.toIntOrNull()

    private fun normalize(raw: String): String {
        val noDiacritics = Normalizer.normalize(raw.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return noDiacritics
            .replace(Regex("""\(\d{4}\)"""), " ")
            .replace(Regex("""\b(19\d{2}|20\d{2})\b"""), " ")
            .replace(Regex("""\b(assistir|dublado|legendado|dual\s*audio|nacional|completo|online|fullhd|hd|4k|1080p|720p)\b"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a.contains(b) || b.contains(a)) {
            val ratio = minOf(a.length, b.length).toDouble() / maxOf(a.length, b.length).toDouble()
            if (ratio >= 0.80) return 0.90
        }
        val dist = levenshtein(a, b)
        return 1.0 - (dist.toDouble() / maxOf(a.length, b.length).toDouble())
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(curr, 0, prev, 0, prev.size)
        }
        return prev[b.length]
    }
}

