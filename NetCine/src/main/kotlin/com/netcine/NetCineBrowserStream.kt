package com.netcine

import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
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
 * Visible interactive WebView dialog + local HTTP proxy for NetCine (maskara.lat).
 * Loads the site's player iframe inside the page origin so PHPSESSID, Referer, and
 * frame-ancestors match a normal browser. Once the player requests its HLS playlist
 * (including NetCine's `/media-player/hls/<name>-ALTO.php` / `<name>-BAIXO.php?token=...`
 * manifests and disguised segment extensions), this proxy hands the stream cleanly
 * to CloudStream's native ExoPlayer using the WebView's cookies and headers.
 */
internal class NetCineBrowserStream : Closeable {
    private val ui = Handler(Looper.getMainLooper())
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val secret = UUID.randomUUID().toString()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(
        6, 6, 0, TimeUnit.SECONDS, ArrayBlockingQueue(32),
        { task -> Thread(task, "NetCine-stream").apply { isDaemon = true } }
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
        }, "NetCine-listener").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun register(archiveUrl: String, pageReferer: String, audioLabel: String): String {
        check(!closed)
        val key = "$archiveUrl|$pageReferer"
        val cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(20)
        sessions.values.filter { it.touched < cutoff }.forEach {
            sessions.remove(it.id)
            it.close()
        }
        sessions.values.firstOrNull { it.key == key }?.let {
            it.touched = System.currentTimeMillis()
            return localUrl(it.id, "master.m3u8")
        }
        while (sessions.size >= 12) {
            val oldest = sessions.values.minByOrNull { it.touched } ?: break
            sessions.remove(oldest.id)
            oldest.close()
        }
        val session = Session(archiveUrl, pageReferer, audioLabel, key)
        sessions[session.id] = session
        return localUrl(session.id, "master.m3u8")
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
        val archiveUrl: String,
        val pageReferer: String,
        val audioLabel: String,
        val key: String
    ) : Closeable {
        val id = UUID.randomUUID().toString()
        @Volatile var touched = System.currentTimeMillis()
        private val captured = CompletableDeferred<CapturedMedia>()
        private val resources = ConcurrentHashMap<String, String>()
        private val reverseResources = ConcurrentHashMap<String, String>()
        private val loggedFirstSeg = AtomicBoolean(false)
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
                    val activity = CommonActivity.activity ?: error("CloudStream activity indisponível")
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

                        override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
                            val src = cm.sourceId().orEmpty()
                            if (!src.contains("disable-devtool", ignoreCase = true)) {
                                Log.i(TAG, "JS [${cm.messageLevel()}] ${cm.message().take(200)} ($src:${cm.lineNumber()})")
                            }
                            return true
                        }
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

                    web.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            runCatching { CookieManager.getInstance().flush() }
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

                            // Block known ad/anti-devtools hosts inside the player frame.
                            if (isBlockedAdHost(host) || fullUrl.contains("disable-devtool", ignoreCase = true)) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            val isNetCineHlsPhp = request.method.equals("GET", ignoreCase = true) &&
                                pathLower.startsWith("/media-player/hls/") &&
                                pathLower.endsWith(".php") &&
                                !pathLower.endsWith("/hls.php") &&
                                !pathLower.endsWith("/hlsarchive.php") &&
                                fullUrl.contains("token=", ignoreCase = true)

                            val isStandardHls = pathLower.endsWith(".m3u8") ||
                                fullUrl.contains(".m3u8?", ignoreCase = true) ||
                                pathLower.endsWith("/master.txt")

                            val isMp4 = (pathLower.endsWith(".mp4") || fullUrl.contains(".mp4?", ignoreCase = true)) &&
                                !pathLower.contains("preview") && !pathLower.contains("banner")

                            if ((isNetCineHlsPhp || isStandardHls || isMp4) && !isIgnoredAssetHost(host)) {
                                Log.i(TAG, "CAPTURED stream: $fullUrl (hls=${isNetCineHlsPhp || isStandardHls})")
                                val reqHeaders = request.requestHeaders?.toMap().orEmpty()
                                val media = CapturedMedia(
                                    url = fullUrl,
                                    headers = reqHeaders,
                                    userAgent = browserUserAgent,
                                    isHls = isNetCineHlsPhp || isStandardHls
                                )
                                ui.post { completeWith(media) }
                                val mime = if (media.isHls) "application/vnd.apple.mpegurl" else "video/mp4"
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
                        text = "NetCine • $audioLabel — Clique em Assistir Online"
                        setTextColor(Color.WHITE)
                        textSize = 15f
                    }
                    val playBtn = Button(activity).apply {
                        text = "Assistir Online (TV)"
                        setOnClickListener { triggerAssistirOnline(web) }
                    }
                    val cancelBtn = Button(activity).apply {
                        text = "Cancelar"
                        setOnClickListener { fail(IllegalStateException("Verificação cancelada")) }
                    }
                    topBar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    topBar.addView(playBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    topBar.addView(cancelBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    layout.addView(topBar, LinearLayout.LayoutParams(-1, -2))
                    layout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))

                    dialog = Dialog(activity).also { d ->
                        d.setContentView(layout)
                        d.setOnCancelListener { fail(IllegalStateException("Verificação cancelada")) }
                        d.setOnKeyListener { _, code, event ->
                            if (code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                    triggerAssistirOnline(web)
                                }
                                true
                            } else {
                                false
                            }
                        }
                        d.show()
                        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }

                    playBtn.requestFocus()
                    // Load inside an iframe under the movie/episode page URL so PHPSESSID,
                    // Referer, and CSP frame-ancestors ('self' *.maskara.lat maskara.lat) match Chrome.
                    val iframeHtml = "<!doctype html><meta name=viewport content=width=device-width,initial-scale=1>" +
                        "<style>html,body,iframe{margin:0;padding:0;width:100%;height:100%;border:0;background:#111;overflow:hidden}</style>" +
                        "<iframe id=\"ncframe\" src=\"$archiveUrl\" allow=\"autoplay *; encrypted-media *; picture-in-picture *; fullscreen *\" allowfullscreen></iframe>"
                    web.loadDataWithBaseURL(pageReferer, iframeHtml, "text/html", "UTF-8", null)

                    ui.postDelayed({
                        if (!captured.isCompleted) {
                            fail(IllegalStateException("Tempo de carregamento do NetCine esgotado"))
                        }
                    }, 120_000L)
                } catch (e: Exception) {
                    fail(e)
                }
            }
        }

        /**
         * Helper for Android TV D-Pad users: clicks only the ordinary "Assistir Online"
         * navigation link on hlsarchive.php (never interacts with any verification widget).
         */
        private fun triggerAssistirOnline(web: WebView) {
            val js = """
                (function() {
                  function tryDoc(doc) {
                    if (!doc) return false;
                    var onlineLink = doc.querySelector('a.btn[href*="hls.php"], a[href*="/hls/hls.php"]');
                    if (onlineLink) { onlineLink.click(); return true; }
                    var frames = doc.querySelectorAll('iframe');
                    for (var i = 0; i < frames.length; i++) {
                      try { if (tryDoc(frames[i].contentDocument)) return true; } catch(e) {}
                    }
                    return false;
                  }
                  tryDoc(document);
                })();
            """.trimIndent()
            web.evaluateJavascript(js, null)
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

        fun buildRequestHeaders(targetUrl: String, media: CapturedMedia, range: String?): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            map["User-Agent"] = media.userAgent
            map["Accept"] = "*/*"
            val referer = media.headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                ?.takeIf { it.isNotBlank() } ?: archiveUrl
            map["Referer"] = referer
            val origin = media.headers.entries.firstOrNull { it.key.equals("Origin", true) }?.value
                ?.takeIf { it.isNotBlank() }
                ?: runCatching {
                    val u = URI(referer)
                    "${u.scheme}://${u.authority}"
                }.getOrNull()
            if (!origin.isNullOrBlank()) map["Origin"] = origin

            val targetCookies = runCatching { CookieManager.getInstance().getCookie(targetUrl) }.getOrNull()
            val siteCookies = runCatching { CookieManager.getInstance().getCookie(archiveUrl) }.getOrNull()
            val targetHost = runCatching { URI(targetUrl).host.orEmpty().lowercase() }.getOrDefault("")
            val siteHost = runCatching { URI(archiveUrl).host.orEmpty().lowercase() }.getOrDefault("")
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

        fun tryLogFirstSegment(url: String, upstreamCt: String, bytes: ByteArray, count: Int) {
            if (loggedFirstSeg.compareAndSet(false, true)) {
                val hex = bytes.take(minOf(16, count)).joinToString(" ") { "%02x".format(it) }
                Log.i(TAG, "First segment: $url (upstreamCt=$upstreamCt, magic=$hex)")
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

    private fun fetchMasterPlaylist(session: Session, media: CapturedMedia): Pair<String, ByteArray>? {
        // When NetCine starts on -BAIXO.php, check if -ALTO.php (HD) is available with the same token.
        val candidates = buildList {
            if (media.url.contains("-BAIXO.php", ignoreCase = true)) {
                add(media.url.replace("-BAIXO.php", "-ALTO.php", ignoreCase = true))
            }
            add(media.url)
        }
        for (candidate in candidates) {
            try {
                val headers = session.buildRequestHeaders(candidate, media, null)
                val req = Request.Builder().url(candidate).get()
                headers.forEach { (k, v) -> req.header(k, v) }
                http.newCall(req.build()).execute().use { resp ->
                    if (resp.code in 200..299) {
                        val bytes = resp.body?.bytes() ?: ByteArray(0)
                        val head = bytes.take(32).toByteArray().toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
                        if (head.startsWith("#EXTM3U")) {
                            Log.i(TAG, "Using playlist: ${resp.request.url} (${bytes.size} bytes)")
                            return resp.request.url.toString() to bytes
                        }
                    }
                }
            } catch (_: Exception) {
                // Fall back to captured URL
            }
        }
        return null
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

            val isMaster = segments[2] == "master.m3u8"
            if (isMaster && media.isHls) {
                val resolved = fetchMasterPlaylist(session, media)
                if (resolved != null) {
                    val rewritten = session.rewritePlaylist(resolved.first, resolved.second)
                    writeSimpleResponse(output, 200, "application/vnd.apple.mpegurl", if (method == "HEAD") ByteArray(0) else rewritten)
                    return
                }
            }

            val targetUrl = when {
                isMaster -> media.url
                segments[2] == "res" && segments.size >= 4 -> session.resolveResource(segments[3])
                else -> null
            }
            if (targetUrl == null) {
                writeSimpleResponse(output, 404, "text/plain", ByteArray(0))
                return
            }

            val isSegmentToken = segments.getOrNull(3)?.endsWith(".ts") == true
            val headers = session.buildRequestHeaders(targetUrl, media, if (isMaster && media.isHls) null else range)
            val reqBuilder = Request.Builder().url(targetUrl)
            if (method == "HEAD") reqBuilder.head() else reqBuilder.get()
            headers.forEach { (k, v) -> reqBuilder.header(k, v) }

            http.newCall(reqBuilder.build()).execute().use { resp ->
                val upstreamCt = resp.header("Content-Type") ?: "application/octet-stream"
                val finalUrl = resp.request.url.toString()
                val looksHls = !isSegmentToken && (
                    (isMaster && media.isHls) ||
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
                    val bodyStream = if (method != "HEAD") resp.body?.byteStream() else null
                    val firstBuf = ByteArray(32 * 1024)
                    val firstRead = bodyStream?.read(firstBuf) ?: 0

                    // Normalize disguised segment Content-Types (e.g. text/html or application/javascript for MPEG-TS)
                    val effectiveCt = if (isSegmentToken && resp.code in 200..299) {
                        if (firstRead > 0) session.tryLogFirstSegment(finalUrl, upstreamCt, firstBuf, firstRead)
                        detectSegmentMime(firstBuf, firstRead, upstreamCt)
                    } else {
                        upstreamCt
                    }

                    val contentRange = resp.header("Content-Range")
                    val contentLength = resp.header("Content-Length")?.toLongOrNull()
                    val head = buildString {
                        append("HTTP/1.1 ${resp.code} ${statusText(resp.code)}\r\n")
                        append("Content-Type: $effectiveCt\r\n")
                        append("Accept-Ranges: bytes\r\n")
                        if (!contentRange.isNullOrBlank()) append("Content-Range: $contentRange\r\n")
                        if (contentLength != null && contentLength >= 0) append("Content-Length: $contentLength\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    output.write(head.toByteArray(Charsets.ISO_8859_1))
                    if (bodyStream != null) {
                        bodyStream.use { stream ->
                            if (firstRead > 0) output.write(firstBuf, 0, firstRead)
                            while (true) {
                                val n = stream.read(firstBuf)
                                if (n <= 0) break
                                output.write(firstBuf, 0, n)
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

    private fun detectSegmentMime(buf: ByteArray, len: Int, upstreamCt: String): String {
        if (len >= 1 && buf[0] == 0x47.toByte()) return "video/mp2t"
        if (len >= 8) {
            val box = String(buf, 4, 4, Charsets.US_ASCII)
            if (box == "ftyp" || box == "moof" || box == "styp") return "video/mp4"
        }
        val lower = upstreamCt.lowercase()
        if (lower.contains("html") || lower.contains("javascript") || lower.contains("css") || lower.contains("image") || lower.contains("text/plain")) {
            return "video/mp2t"
        }
        return upstreamCt
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
        private const val TAG = "NetCine"
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private val URI_ATTR_REGEX = Regex("""URI="([^"]+)"""")

        private fun isBlockedAdHost(host: String): Boolean {
            return host.contains("wdice-ref") ||
                host.contains("doubleclick") ||
                host.contains("googlesyndication") ||
                host.contains("popads") ||
                host.contains("acscdn") ||
                (host.contains("cloudfront.net") && host.startsWith("d4bx2if"))
        }

        private fun isIgnoredAssetHost(host: String): Boolean {
            return host == "trf.bcdn.zip" ||
                host == "cap.bcdn.zip" ||
                host == "imghost.bcdn.zip" ||
                host == "cdn.jsdelivr.net" ||
                host.endsWith("google-analytics.com") ||
                host.endsWith("googletagmanager.com")
        }
    }
}
