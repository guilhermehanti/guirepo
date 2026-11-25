package com.CineAgora

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

object CineAgoraExtractor {
    private const val BASE_PLAYER = "https://watch.brstream.cc"
    private const val REFERER_CINEAGORA = "https://cineagora.club/"
    private const val PRIMARY_SOURCE = "CineAgora"

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit = {},
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Support multi-audio entries encoded as "url1#label=Dublado||url2#label=Legendado"
        // (and backwards-compatible with legacy "Label|https://..." or fixUrl-prefixed ".../Label|https://...")
        if (url.contains("||") || url.contains("#label=") || url.contains("|http")) {
            var foundAny = false
            val entries = url.split("||").map { it.trim() }.filter { it.isNotBlank() }
            for (entry in entries) {
                val (entryName, entryUrl) = parseTaggedEntry(entry, name)
                if (extractSingleUrl(entryUrl, entryName, subtitleCallback, callback)) {
                    foundAny = true
                }
            }
            return foundAny
        }

        return extractSingleUrl(url, name, subtitleCallback, callback)
    }

    private fun parseTaggedEntry(entry: String, defaultName: String): Pair<String, String> {
        val hashIdx = entry.indexOf("#label=")
        if (hashIdx > 0) {
            val cleanUrl = entry.substring(0, hashIdx).trim()
            val lbl = entry.substring(hashIdx + "#label=".length).trim()
            val linkLabel = if (lbl.isNotBlank()) "$PRIMARY_SOURCE - $lbl" else defaultName
            return linkLabel to cleanUrl
        }
        val pipeIdx = entry.indexOf("|http")
        if (pipeIdx > 0) {
            val rawPrefix = entry.substring(0, pipeIdx).trim()
            val lbl = rawPrefix.substringAfterLast("/").trim()
            val linkLabel = if (lbl.isNotBlank()) "$PRIMARY_SOURCE - $lbl" else defaultName
            return linkLabel to entry.substring(pipeIdx + 1).trim()
        }
        return defaultName to entry
    }

    private suspend fun extractSingleUrl(
        url: String,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return when {
            url.contains("watch.brstream.cc/watch?v=") || url.contains("watch.brplayer.cc/watch?v=") -> {
                extractHlsFromWatchPage(url, name, subtitleCallback, callback)
            }
            (url.contains("watch.brstream.cc/watch/") || url.contains("watch.brplayer.cc/watch/")) && !url.contains("?v=") -> {
                extractHlsFromWatchPage(url, name, subtitleCallback, callback)
            }
            url.contains("cineagora.club") -> {
                extractFromCineAgoraPage(url, name, subtitleCallback, callback)
            }
            else -> {
                extractHlsFromWatchPage(url, name, subtitleCallback, callback)
            }
        }
    }

    private suspend fun extractFromCineAgoraPage(
        cineAgoraUrl: String,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val html = app.get(cineAgoraUrl, referer = REFERER_CINEAGORA).text
            val doc = Jsoup.parse(html, cineAgoraUrl)

            // 1. Collect ALL player buttons with data-link (e.g. Dublado + Legendado) preserving labels
            val labeledTargets = linkedMapOf<String, String>() // normalized watch/tv URL -> label
            for (el in doc.select("[data-link]")) {
                var rawLink = el.attr("data-link").trim()
                if (rawLink.isBlank()) continue
                if (rawLink.startsWith("/watch") || rawLink.startsWith("/tv/")) {
                    rawLink = BASE_PLAYER + rawLink
                }
                if (!rawLink.contains("watch.brstream.cc") && !rawLink.contains("watch.brplayer.cc")) continue
                val cleanLabel = el.text().replace(Regex("""\s+"""), " ").trim()
                if (!labeledTargets.containsKey(rawLink)) {
                    labeledTargets[rawLink] = cleanLabel
                }
            }

            // Also include any iframes pointing to watch.brstream.cc / watch.brplayer.cc
            for (iframe in doc.select("iframe[src]")) {
                var src = iframe.attr("src").trim()
                if (src.startsWith("/watch") || src.startsWith("/tv/")) {
                    src = BASE_PLAYER + src
                }
                if ((src.contains("watch.brstream.cc") || src.contains("watch.brplayer.cc")) && !labeledTargets.containsKey(src)) {
                    labeledTargets[src] = ""
                }
            }

            // Regex fallback in case links only appear inside inline scripts
            val fallbackPattern = """https://watch\.br(?:stream|player)\.cc/(?:watch\?v=|watch/|tv/)[^"'\s<>]+"""
            for (match in Regex(fallbackPattern).findAll(html)) {
                val foundUrl = match.value.trim()
                if (!labeledTargets.containsKey(foundUrl)) {
                    labeledTargets[foundUrl] = ""
                }
            }

            var foundAny = false
            val processedWatchUrls = mutableSetOf<String>()

            for ((rawTarget, rawLabel) in labeledTargets) {
                val linkLabel = if (rawLabel.isNotBlank()) "$PRIMARY_SOURCE - $rawLabel" else name
                val watchUrl = when {
                    rawTarget.contains("/watch?v=") -> rawTarget
                    rawTarget.contains("/watch/") -> {
                        if (!rawTarget.contains("?")) "$rawTarget?ref=&d=null" else rawTarget
                    }
                    rawTarget.contains("/tv/") -> {
                        val videoSlug = extractVideoSlugFromSeriesPage(rawTarget) ?: continue
                        "$BASE_PLAYER/watch/$videoSlug?ref=&d=null"
                    }
                    else -> rawTarget
                }

                if (!processedWatchUrls.add(watchUrl)) continue
                if (extractHlsFromWatchPage(watchUrl, linkLabel, subtitleCallback, callback)) {
                    foundAny = true
                }
            }

            return foundAny
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private suspend fun extractVideoSlugFromSeriesPage(seriesUrl: String): String? {
        try {
            val html = app.get(seriesUrl, referer = REFERER_CINEAGORA).text
            
            val patterns = listOf(
                """video_slug["']\s*:\s*["']([^"']+)["']""",
                """["']slug["']\s*:\s*["']([^"']+)["']""",
                """/watch/([^"'\s<>/]+)""",
                """data-link=["']([^"']+)["'].*?video_slug""",
                """var\s+video_slug\s*=\s*["']([^"']+)["']""",
                """video_slug\s*=\s*["']([^"']+)["']"""
            )
            
            for (pattern in patterns) {
                val match = Regex(pattern).find(html)
                if (match != null) {
                    val slug = match.groupValues[1]
                    if (slug.isNotBlank() && slug.matches(Regex("^[A-Z0-9]+$"))) {
                        return slug
                    }
                }
            }
            
            return null
        } catch (e: Exception) {
            return null
        }
    }

    private suspend fun extractHlsFromWatchPage(
        watchUrl: String,
        name: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "pt-BR",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache",
                "Referer" to if (watchUrl.contains("/tv/")) watchUrl else "https://watch.brstream.cc/tv/severance",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "same-origin",
                "Upgrade-Insecure-Requests" to "1",
                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
            )
            
            val html = app.get(watchUrl, headers = headers).text
            emitSubtitlesFromHtml(html, subtitleCallback)

            val videoParams = extractVideoParams(html)
            if (videoParams != null) {
                val masterUrl = "$BASE_PLAYER/m3u8/${videoParams.uid}/${videoParams.md5}/master.txt?s=1&id=${videoParams.id}&cache=${videoParams.status}"
                
                val hlsHeaders = mapOf(
                    "Referer" to watchUrl,
                    "Origin" to BASE_PLAYER,
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                )

                try {
                    val allLinks = M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = masterUrl,
                        referer = watchUrl,
                        headers = hlsHeaders
                    )
                    
                    allLinks.forEach { link ->
                        callback(link)
                    }
                    
                    return true
                    
                } catch (e: Exception) {
                    val fallbackLink = newExtractorLink(
                        source = name,
                        name = name,
                        url = masterUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = watchUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = hlsHeaders
                    }
                    callback(fallbackLink)
                    
                    return true
                }
            }

            val m3u8Url = extractM3u8UrlDirect(html)
            if (m3u8Url != null) {
                val hlsHeaders = mapOf(
                    "Referer" to watchUrl,
                    "Origin" to BASE_PLAYER
                )

                val directLink = newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = watchUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = hlsHeaders
                }
                callback(directLink)
                return true
            }

            return false
            
        } catch (e: Exception) {
            return false
        }
    }

    private suspend fun emitSubtitlesFromHtml(
        html: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val unescaped = html.replace("\\/", "/")
        val trackObjRegex = Regex("""\{[^{}]*"file"\s*:\s*"([^"]+\.(?:vtt|srt)[^"]*)"[^{}]*\}""", RegexOption.IGNORE_CASE)
        val seen = mutableSetOf<String>()
        for (match in trackObjRegex.findAll(unescaped)) {
            val block = match.value
            if (block.contains("thumbnails", ignoreCase = true)) continue
            var subUrl = match.groupValues[1].trim()
            if (subUrl.startsWith("/")) subUrl = BASE_PLAYER + subUrl
            if (!subUrl.startsWith("http") || !seen.add(subUrl)) continue
            val label = Regex(""""label"\s*:\s*"([^"]+)"""").find(block)?.groupValues?.get(1)?.trim()
                ?.ifBlank { "Português" } ?: "Português"
            subtitleCallback(newSubtitleFile(label, subUrl))
        }
    }

    private fun extractVideoParams(html: String): VideoParams? {
        val videoPattern = """var\s+video\s*=\s*\{[^}]+\}"""
        val videoMatch = Regex(videoPattern, RegexOption.DOT_MATCHES_ALL).find(html)
        
        if (videoMatch != null) {
            val videoJson = videoMatch.value
            
            val uid = extractFromRegex(videoJson, """"uid"\s*:\s*"([^"]+)"""")
            val md5 = extractFromRegex(videoJson, """"md5"\s*:\s*"([^"]+)"""")
            val id = extractFromRegex(videoJson, """"id"\s*:\s*"([^"]+)"""")
            val status = extractFromRegex(videoJson, """"status"\s*:\s*"([^"]+)"""") ?: "1"
            
            if (uid != null && md5 != null && id != null) {
                return VideoParams(uid, md5, id, status)
            }
        }
        
        val uid = extractFromRegex(html, """"uid"\s*:\s*"([^"]+)"""")
        val md5 = extractFromRegex(html, """"md5"\s*:\s*"([^"]+)"""")
        val id = extractFromRegex(html, """"id"\s*:\s*"([^"]+)"""")
        val status = extractFromRegex(html, """"status"\s*:\s*"([^"]+)"""") ?: "1"
        
        if (uid != null && md5 != null && id != null) {
            return VideoParams(uid, md5, id, status)
        }
        
        val configPattern = """jwplayer\('[^']+'\)\.setup\(([\s\S]*?)\);"""
        val configMatch = Regex(configPattern).find(html)
        
        if (configMatch != null) {
            val configText = configMatch.groupValues[1]
            
            val videoInConfig = extractFromRegex(configText, """video"\s*:\s*\{([^}]+)\}""")
            if (videoInConfig != null) {
                val uid2 = extractFromRegex(videoInConfig, """"uid"\s*:\s*"([^"]+)"""")
                val md5_2 = extractFromRegex(videoInConfig, """"md5"\s*:\s*"([^"]+)"""")
                val id2 = extractFromRegex(videoInConfig, """"id"\s*:\s*"([^"]+)"""")
                val status2 = extractFromRegex(videoInConfig, """"status"\s*:\s*"([^"]+)"""") ?: "1"
                
                if (uid2 != null && md5_2 != null && id2 != null) {
                    return VideoParams(uid2, md5_2, id2, status2)
                }
            }
        }
        
        return null
    }

    private fun extractFromRegex(text: String, pattern: String): String? {
        val regex = Regex(pattern)
        val match = regex.find(text)
        return match?.groupValues?.getOrNull(1)
    }

    private fun extractM3u8UrlDirect(html: String): String? {
        val patterns = listOf(
            """file\s*:\s*["']/m3u8/([^/]+)/([^/]+)/master\.txt\?s=1&id=([^&]+)&cache=([^"']+)["']""",
            """"file"\s*:\s*["']([^"']+/m3u8/[^"']+\.txt[^"']*)["']""",
            """sources\s*:\s*\[\{.*?"file"\s*:\s*["']([^"']+\.txt[^"']*)["']""",
            """master\.txt\?s=1&id=\d+&cache=\d+""",
            """["'](https?://[^"']+\.m3u8[^"']*)["']""",
            """["'](/m3u8/[^"']+\.txt[^"']*)["']"""
        )
        
        for (pattern in patterns) {
            val regex = Regex(pattern, RegexOption.DOT_MATCHES_ALL)
            val match = regex.find(html)
            if (match != null) {
                var url = match.groupValues.getOrNull(1) ?: match.value
                
                if (url.startsWith("/m3u8/") && !url.startsWith("//")) {
                    url = BASE_PLAYER + url
                }
                
                return url
            }
        }
        
        return null
    }

    data class VideoParams(
        val uid: String,
        val md5: String,
        val id: String,
        val status: String
    )
}