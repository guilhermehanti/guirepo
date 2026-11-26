package com.redecanais

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Visible interactive WebView dialog + local HTTP proxy for RedeCanais.
 * Only used as a fallback when direct HTTP extraction from player3/server.php
 * (or the video page) is blocked by Cloudflare.
 *
 * Never auto-clicks or bypasses any verification widget: if Cloudflare presents
 * a challenge, the user completes it directly in the visible WebView. Once the
 * real player page loads naturally, this helper captures the media URL (from
 * `window.SRC` / `<video>` or intercepted media requests) and proxies it to
 * ExoPlayer with HTTP Range support (MP4) or playlist rewriting (HLS).
 */
internal class RedeCanaisBrowserStream : Closeable {
    private val ui = Handler(Looper.getMainLooper())

    private fun resolveHostActivity(): Activity? {
        CommonActivity.activity?.takeIf { !it.isFinishing && !it.isDestroyed }?.let { return it }
        return try {
            val acra = Class.forName("com.lagradost.cloudstream3.AcraApplication")
            val act = acra.getMethod("getActivity").invoke(null) as? Activity
            act?.takeIf { !it.isFinishing && !it.isDestroyed }
        } catch (_: Throwable) {
            null
        }
    }

    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val secret = UUID.randomUUID().toString()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(
        6, 6, 0, TimeUnit.SECONDS, ArrayBlockingQueue(32),
        { task -> Thread(task, "RedeCanais-stream").apply { isDaemon = true } }
    )
    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    @Volatile private var closed = false

    init {
        Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                clients += socket
                try {
                    workers.execute { serve(socket) }
                } catch (_: Exception) {
                    clients -= socket
                    runCatching { socket.close() }
                }
            }
        }, "RedeCanais-listener").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun register(targetUrl: String, pageReferer: String, label: String): String {
        check(!closed)
        val key = "$targetUrl|$pageReferer"
        val cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(20)
        sessions.values.filter { it.touched < cutoff }.forEach {
            sessions.remove(it.id)
            it.close()
        }
        sessions.values.firstOrNull { it.key == key }?.let {
            it.touched = System.currentTimeMillis()
            return localUrl(it.id, "video.mp4")
        }
        while (sessions.size >= 12) {
            val oldest = sessions.values.minByOrNull { it.touched } ?: break
            sessions.remove(oldest.id)
            oldest.close()
        }
        val session = Session(targetUrl, pageReferer, label, key)
        sessions[session.id] = session
        return localUrl(session.id, "video.mp4")
    }

    private fun localUrl(sessionId: String, resource: String): String =
        "http://127.0.0.1:${server.localPort}/$secret/$sessionId/$resource"

    private data class CapturedMedia(
        val url: String,
        val headers: Map<String, String>,
        val userAgent: String,
        val isHls: Boolean
    )

    private inner class Session(
        val targetUrl: String,
        val pageReferer: String,
        val label: String,
        val key: String
    ) : Closeable {
        val id = UUID.randomUUID().toString()
        @Volatile var touched = System.currentTimeMillis()
        private val captured = CompletableDeferred<CapturedMedia>()
        private val resources = ConcurrentHashMap<String, String>()
        private val reverseResources = ConcurrentHashMap<String, String>()
        private val loggedFirstChunk = AtomicBoolean(false)
        private var started = false
        @Volatile private var disposed = false
        private var browser: WebView? = null
        private var dialog: Dialog? = null
        @Volatile var browserUserAgent: String = DEFAULT_UA

        @Synchronized
        fun start() {
            check(!disposed)
            if (started) return
            started = true
            ui.post {
                try {
                    val activity = resolveHostActivity() ?: error("Host activity indisponível")
                    val web = WebView(activity)
                    browser = web
                    browserUserAgent = web.settings.userAgentString?.takeIf { it.isNotBlank() } ?: DEFAULT_UA
                    web.settings.javaScriptEnabled = true
                    web.settings.domStorageEnabled = true
                    web.settings.mediaPlaybackRequiresUserGesture = false
                    web.settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    web.settings.setSupportMultipleWindows(false)
                    web.settings.javaScriptCanOpenWindowsAutomatically = false
                    web.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?
                        ): Boolean = false
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

                    web.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            runCatching { CookieManager.getInstance().flush() }
                            if (view != null && !captured.isCompleted) {
                                inspectPlayerPage(view, url.orEmpty())
                            }
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            Log.w(TAG, "onReceivedError: ${request?.url} -> ${error?.errorCode} ${error?.description}")
                        }

                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest
                        ): WebResourceResponse? {
                            val uri = request.url
                            val scheme = uri.scheme?.lowercase() ?: return null
                            if (scheme != "https" && scheme != "http") return null
                            val host = uri.host?.lowercase() ?: return null
                            val fullUrl = uri.toString()
                            val pathLower = uri.path.orEmpty().lowercase()

                            if (isBlockedAdHost(host)) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            val isHls = pathLower.endsWith(".m3u8") ||
                                fullUrl.contains(".m3u8?", ignoreCase = true)
                            val isDirectVideo = (pathLower.endsWith(".mp4") ||
                                fullUrl.contains(".mp4?", ignoreCase = true) ||
                                pathLower.endsWith(".mkv") ||
                                fullUrl.contains(".mkv?", ignoreCase = true)) &&
                                !pathLower.contains("preview") &&
                                !pathLower.contains("banner") &&
                                !pathLower.contains("ads")

                            if ((isHls || isDirectVideo) && !isIgnoredAssetHost(host)) {
                                Log.i(TAG, "CAPTURED stream via network: $fullUrl (hls=$isHls)")
                                val reqHeaders = request.requestHeaders?.toMap().orEmpty()
                                val media = CapturedMedia(
                                    url = fullUrl,
                                    headers = reqHeaders,
                                    userAgent = browserUserAgent,
                                    isHls = isHls
                                )
                                ui.post { completeWith(media) }
                                val mime = if (isHls) "application/vnd.apple.mpegurl" else "video/mp4"
                                return WebResourceResponse(mime, null, ByteArrayInputStream(ByteArray(0)))
                            }
                            return null
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val host = request.url.host?.lowercase().orEmpty()
                            return isBlockedAdHost(host)
                        }
                    }

                    val layout = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(Color.rgb(18, 18, 18))
                    }
                    val topBar = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(24, 14, 24, 14)
                    }
                    val titleView = TextView(activity).apply {
                        text = "$label — Aguarde ou conclua a verificação"
                        setTextColor(Color.WHITE)
                        textSize = 15f
                    }
                    val reloadBtn = Button(activity).apply {
                        text = "Recarregar"
                        setOnClickListener {
                            web.loadUrl(targetUrl, mapOf("Referer" to pageReferer))
                        }
                    }
                    val cancelBtn = Button(activity).apply {
                        text = "Cancelar"
                        setOnClickListener { fail(IllegalStateException("Verificação cancelada")) }
                    }
                    topBar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    topBar.addView(reloadBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    topBar.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    layout.addView(topBar, LinearLayout.LayoutParams(-1, -2))
                    layout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

                    dialog = Dialog(activity).also { d ->
                        d.setContentView(layout)
                        d.setOnCancelListener { fail(IllegalStateException("Verificação cancelada")) }
                        d.show()
                        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }

                    web.loadUrl(targetUrl, mapOf("Referer" to pageReferer))

                    ui.postDelayed({
                        if (!captured.isCompleted) {
                            fail(IllegalStateException("Tempo de verificação do RedeCanais esgotado"))
                        }
                    }, 120_000L)
                } catch (e: Exception) {
                    fail(e)
                }
            }
        }

        /**
         * Read-only inspection of the loaded player page once any verification has completed
         * naturally. Reads RedeCanais's `window.SRC` / `<video>` element without clicking or
         * interacting with any page element.
         */
        private fun inspectPlayerPage(web: WebView, currentUrl: String) {
            val js = """
                (function() {
                  try {
                    var src = (typeof SRC === 'string' && SRC) ? SRC : '';
                    var fmt = (typeof SOURCE_FORMAT === 'string') ? SOURCE_FORMAT : '';
                    var mime = (typeof SOURCE_MIME === 'string') ? SOURCE_MIME : '';
                    if (!src) {
                      var v = document.querySelector('video source[src], video[src]');
                      if (v) src = v.getAttribute('src') || v.src || '';
                    }
                    if (!src) {
                      var ifr = document.querySelector('iframe[name="Player"][src], iframe[src*="player3/server.php"], iframe[src*="/embed.php"]');
                      if (ifr) {
                        return JSON.stringify({ iframe: ifr.src || ifr.getAttribute('src') || '' });
                      }
                    }
                    return JSON.stringify({ src: src, fmt: fmt, mime: mime });
                  } catch (e) {
                    return '{}';
                  }
                })();
            """.trimIndent()

            web.evaluateJavascript(js) { raw ->
                if (disposed || captured.isCompleted || raw.isNullOrBlank() || raw == "null") return@evaluateJavascript
                try {
                    val unwrapped = if (raw.startsWith("\"") && raw.endsWith("\"")) {
                        JSONObject("{\"v\":$raw}").optString("v")
                    } else {
                        raw
                    }
                    val obj = JSONObject(unwrapped)
                    val src = obj.optString("src").trim()
                    if (src.startsWith("http://") || src.startsWith("https://")) {
                        val fmt = obj.optString("fmt")
                        val mime = obj.optString("mime")
                        val isHls = src.contains(".m3u8", ignoreCase = true) ||
                            fmt.equals("hls", ignoreCase = true) ||
                            mime.contains("mpegurl", ignoreCase = true)
                        Log.i(TAG, "CAPTURED stream via player DOM: $src (hls=$isHls)")
                        val referer = currentUrl.ifBlank { targetUrl }
                        completeWith(
                            CapturedMedia(
                                url = src,
                                headers = mapOf("Referer" to referer),
                                userAgent = browserUserAgent,
                                isHls = isHls
                            )
                        )
                        return@evaluateJavascript
                    }
                    // If we started on the video page (because pageUrl itself was challenged),
                    // navigate to the discovered player iframe URL once the page loads.
                    val discoveredIframe = obj.optString("iframe").trim()
                    if (discoveredIframe.startsWith("http") &&
                        discoveredIframe != currentUrl &&
                        !currentUrl.contains("server.php", ignoreCase = true)
                    ) {
                        Log.i(TAG, "Navigating to player iframe: $discoveredIframe")
                        web.loadUrl(discoveredIframe, mapOf("Referer" to currentUrl.ifBlank { pageReferer }))
                    }
                } catch (_: Exception) {
                    // Ignore non-JSON evaluation results during intermediate loads
                }
            }
        }

        private fun completeWith(media: CapturedMedia) {
            if (disposed || captured.isCompleted) return
            runCatching { CookieManager.getInstance().flush() }
            captured.complete(media)
            dismissUi()
        }

        private fun dismissUi() {
            val d = dialog
            dialog = null
            runCatching { d?.dismiss() }
            val w = browser
            browser = null
            if (w != null) {
                runCatching {
                    (w.parent as? ViewGroup)?.removeView(w)
                    w.stopLoading()
                    w.loadUrl("about:blank")
                    w.destroy()
                }
            }
        }

        private fun fail(error: Exception) {
            sessions.remove(id, this)
            captured.completeExceptionally(error)
            close()
        }

        fun awaitCaptured(): CapturedMedia {
            touched = System.currentTimeMillis()
            start()
            return runBlocking { withTimeout(125_000L) { captured.await() } }
        }

        fun registerResource(targetUrl: String, isSegment: Boolean = true): String {
            reverseResources[targetUrl]?.let { return localUrl(id, "res/$it") }
            val ext = when {
                targetUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true) -> ".m3u8"
                targetUrl.substringBefore("?").endsWith(".key", ignoreCase = true) -> ".key"
                isSegment -> ".ts"
                else -> ""
            }
            val token = UUID.randomUUID().toString().replace("-", "") + ext
            resources[token] = targetUrl
            reverseResources[targetUrl] = token
            return localUrl(id, "res/$token")
        }

        fun resolveResource(token: String): String? = resources[token]

        fun buildRequestHeaders(url: String, media: CapturedMedia, range: String?): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            map["User-Agent"] = media.userAgent
            map["Accept"] = "*/*"
            val referer = media.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                ?.takeIf { it.isNotBlank() } ?: runCatching {
                val u = URI(targetUrl)
                "${u.scheme}://${u.authority}/"
            }.getOrDefault("https://redecanais.sk/")
            map["Referer"] = referer
            runCatching {
                val u = URI(referer)
                map["Origin"] = "${u.scheme}://${u.authority}"
            }

            val targetCookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            val siteCookies = runCatching { CookieManager.getInstance().getCookie(targetUrl) }.getOrNull()
            val targetHost = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
            val siteHost = runCatching { URI(targetUrl).host.orEmpty().lowercase() }.getOrDefault("")
            val cookieHeader = when {
                !targetCookies.isNullOrBlank() -> targetCookies
                targetHost == siteHost && !siteCookies.isNullOrBlank() -> siteCookies
                else -> null
            }
            if (!cookieHeader.isNullOrBlank()) map["Cookie"] = cookieHeader
            if (!range.isNullOrBlank()) map["Range"] = range
            return map
        }

        fun rewritePlaylist(baseUrl: String, body: ByteArray): ByteArray {
            val baseUri = URI(baseUrl)
            val text = body.toString(Charsets.UTF_8)
            val isMaster = text.contains("#EXT-X-STREAM-INF")
            val rewritten = text.lineSequence().joinToString("\n") { line ->
                val trimmed = line.trim()
                when {
                    trimmed.isEmpty() -> line
                    trimmed.startsWith("#") -> {
                        URI_ATTR_REGEX.replace(line) { m ->
                            val raw = m.groupValues[1]
                            val abs = runCatching { baseUri.resolve(raw).toString() }.getOrDefault(raw)
                            "URI=\"${registerResource(abs, isSegment = false)}\""
                        }
                    }
                    else -> {
                        val abs = runCatching { baseUri.resolve(trimmed).toString() }.getOrDefault(trimmed)
                        registerResource(abs, isSegment = !isMaster)
                    }
                }
            }
            return rewritten.toByteArray(Charsets.UTF_8)
        }

        fun logFirstChunk(url: String, code: Int, contentType: String) {
            if (loggedFirstChunk.compareAndSet(false, true)) {
                Log.i(TAG, "Streaming upstream: $url (code=$code, ct=$contentType)")
            }
        }

        override fun close() {
            if (disposed) return
            disposed = true
            resources.clear()
            reverseResources.clear()
            ui.post { dismissUi() }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val headerBytes = readHttpHeaders(input) ?: return
            val headerLines = headerBytes.toString(Charsets.ISO_8859_1).split("\r\n")
            val requestLine = headerLines.firstOrNull().orEmpty().split(" ")
            if (requestLine.size < 2) {
                writeSimpleResponse(output, 400, "text/plain", ByteArray(0))
                return
            }
            val method = requestLine[0].uppercase()
            val rawPath = requestLine[1].substringBefore("?")
            val reqHeaders = headerLines.drop(1)
                .mapNotNull { line ->
                    val idx = line.indexOf(':')
                    if (idx > 0) line.substring(0, idx).trim().lowercase() to line.substring(idx + 1).trim() else null
                }.toMap()
            val range = reqHeaders["range"]

            val segments = rawPath.trimStart('/').split("/")
            if (segments.size < 3 || segments[0] != secret) {
                writeSimpleResponse(output, 404, "text/plain", ByteArray(0))
                return
            }
            val session = sessions[segments[1]]
            if (session == null) {
                writeSimpleResponse(output, 404, "text/plain", ByteArray(0))
                return
            }
            session.touched = System.currentTimeMillis()

            val media = try {
                session.awaitCaptured()
            } catch (e: Exception) {
                writeSimpleResponse(output, 502, "text/plain", (e.message ?: "Erro").toByteArray())
                return
            }

            val isEntry = segments[2] == "video.mp4" || segments[2] == "master.m3u8"
            val upstreamUrl = when {
                isEntry -> media.url
                segments[2] == "res" && segments.size >= 4 -> session.resolveResource(segments[3])
                else -> null
            }
            if (upstreamUrl == null) {
                writeSimpleResponse(output, 404, "text/plain", ByteArray(0))
                return
            }

            val isSegmentToken = segments.getOrNull(3)?.endsWith(".ts") == true
            val reqRange = if (isEntry && media.isHls) null else range
            val headers = session.buildRequestHeaders(upstreamUrl, media, reqRange)
            val reqBuilder = Request.Builder().url(upstreamUrl)
            if (method == "HEAD") reqBuilder.head() else reqBuilder.get()
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }

            http.newCall(reqBuilder.build()).execute().use { resp ->
                val upstreamCt = resp.header("Content-Type")
                    ?: if (media.isHls) "application/vnd.apple.mpegurl" else "video/mp4"
                val finalUrl = resp.request.url.toString()
                session.logFirstChunk(finalUrl, resp.code, upstreamCt)

                val looksHls = !isSegmentToken && (
                    (isEntry && media.isHls) ||
                        upstreamCt.contains("mpegurl", ignoreCase = true) ||
                        finalUrl.substringBefore("?").endsWith(".m3u8", ignoreCase = true)
                    )

                if (looksHls && resp.code in 200..299 && method != "HEAD") {
                    val rawBytes = resp.body?.bytes() ?: ByteArray(0)
                    val trimmedText = rawBytes.take(32).toByteArray().toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
                    val outBytes = if (trimmedText.startsWith("#EXTM3U")) {
                        session.rewritePlaylist(finalUrl, rawBytes)
                    } else {
                        rawBytes
                    }
                    writeSimpleResponse(output, resp.code, "application/vnd.apple.mpegurl", outBytes)
                } else {
                    val contentRange = resp.header("Content-Range")
                    val contentLength = resp.header("Content-Length")?.toLongOrNull()
                    val head = buildString {
                        append("HTTP/1.1 ${resp.code} ${statusText(resp.code)}\r\n")
                        append("Content-Type: $upstreamCt\r\n")
                        append("Accept-Ranges: bytes\r\n")
                        if (!contentRange.isNullOrBlank()) append("Content-Range: $contentRange\r\n")
                        if (contentLength != null && contentLength >= 0) append("Content-Length: $contentLength\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    output.write(head.toByteArray(Charsets.ISO_8859_1))
                    if (method != "HEAD") {
                        resp.body?.byteStream()?.use { stream ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = stream.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                            }
                        }
                    }
                    output.flush()
                }
            }
        } catch (_: Exception) {
            // Client aborted or upstream closed
        } finally {
            clients -= socket
            runCatching { socket.close() }
        }
    }

    private fun readHttpHeaders(input: InputStream): ByteArray? {
        val buf = java.io.ByteArrayOutputStream(512)
        var prev1 = 0
        var prev2 = 0
        var prev3 = 0
        while (buf.size() < 16_384) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toByteArray()
            buf.write(b)
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                return buf.toByteArray()
            }
            prev3 = prev2
            prev2 = prev1
            prev1 = b
        }
        return buf.toByteArray()
    }

    private fun writeSimpleResponse(output: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val head = "HTTP/1.1 $status ${statusText(status)}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Accept-Ranges: bytes\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        502 -> "Bad Gateway"
        else -> "Status"
    }

    override fun close() {
        closed = true
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { server.close() }
        runCatching { workers.shutdownNow() }
    }

    companion object {
        private const val TAG = "RedeCanais"
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val URI_ATTR_REGEX = Regex("""URI="([^"]+)"""")

        private fun isBlockedAdHost(host: String): Boolean {
            return host.contains("doubleclick") ||
                host.contains("googlesyndication") ||
                host.contains("popads") ||
                host.contains("acscdn") ||
                host.contains("adsterra") ||
                host.contains("histats")
        }

        private fun isIgnoredAssetHost(host: String): Boolean {
            return host == "cdn.jsdelivr.net" ||
                host.endsWith("google-analytics.com") ||
                host.endsWith("googletagmanager.com")
        }
    }
}
