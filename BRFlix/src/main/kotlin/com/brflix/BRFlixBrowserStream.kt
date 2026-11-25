package com.brflix

import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Keeps the verified browser session as transport; CloudStream still decodes the HLS. */
internal class BRFlixBrowserStream : Closeable {
    private val ui = Handler(Looper.getMainLooper())
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val secret = UUID.randomUUID().toString()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val directStreams = ConcurrentHashMap<String, DirectEntry>()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(6, 6, 0, TimeUnit.SECONDS, ArrayBlockingQueue(24),
        { task -> Thread(task, "BRFlix-stream").apply { isDaemon = true } })
    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    @Volatile private var closed = false

    private data class DirectEntry(
        val id: String,
        val url: String,
        val headers: Map<String, String>,
        @Volatile var touched: Long = System.currentTimeMillis()
    )

    init {
        Thread({
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                clients += socket
                try { workers.execute { serve(socket) } }
                catch (_: Exception) { clients -= socket; socket.close() }
            }
        }, "BRFlix-listener").apply { isDaemon = true; start() }
    }

    @Synchronized fun registerDirect(url: String, headers: Map<String, String>): String {
        check(!closed)
        val cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(30)
        directStreams.entries.removeAll { it.value.touched < cutoff }
        directStreams.values.firstOrNull { it.url == url }?.let {
            it.touched = System.currentTimeMillis()
            return "http://127.0.0.1:${server.localPort}/$secret/direct/${it.id}"
        }
        while (directStreams.size >= 32) {
            val oldest = directStreams.values.minByOrNull { it.touched } ?: break
            directStreams.remove(oldest.id)
        }
        val id = UUID.randomUUID().toString()
        directStreams[id] = DirectEntry(id, url, headers)
        return "http://127.0.0.1:${server.localPort}/$secret/direct/$id"
    }

    /**
     * Clean header-only reachability probe using OkHttp (no default Referer/Origin from NiceHttp).
     * Returns the final resolved URL after redirects if the stream is playable, or null otherwise.
     */
    fun probeExternalStream(url: String, headers: Map<String, String>): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", headers["User-Agent"] ?: DESKTOP_UA)
                .header("Range", "bytes=0-1")
            for ((k, v) in headers) {
                if (!k.equals("User-Agent", true) && !k.equals("Range", true) && v.isNotBlank()) {
                    req.header(k, v)
                }
            }
            http.newCall(req.build()).execute().use { resp ->
                if (resp.code !in 200..299) return null
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (ct.contains("text/html") || ct.contains("text/plain") || ct.contains("application/json")) {
                    return null
                }
                if (ct.contains("matroska") || ct.contains("x-mkv")) {
                    return null
                }
                val cl = resp.header("Content-Length")?.trim()?.toLongOrNull()
                if (ct.contains("mpegurl") && cl != null && cl < 64L) {
                    return null
                }
                resp.request.url.toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized fun register(provider: String, embed: String, subbed: Boolean, mp4: Boolean = false): String {
        check(!closed)
        val key = "$embed|$subbed"
        val cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(20)
        sessions.values.filter { it.touched < cutoff }.forEach { sessions.remove(it.id); it.close() }
        val resource = if (mp4) "video.mp4" else "master"
        sessions.values.firstOrNull { it.key == key }?.let { return local(it.id, resource) }
        while (sessions.size >= 16) {
            val oldest = sessions.values.minByOrNull { it.touched } ?: break
            sessions.remove(oldest.id); oldest.close()
        }
        val session = Session(provider, embed, subbed, key)
        sessions[session.id] = session
        return local(session.id, resource)
    }

    private fun local(session: String, resource: String) =
        "http://127.0.0.1:${server.localPort}/$secret/$session/$resource"

    private data class Reply(
        val status: Int,
        val bytes: ByteArray,
        val type: String,
        val range: String?,
        val finalUrl: String? = null
    )

    private inner class Session(val provider: String, val embed: String, val subbed: Boolean, val key: String) : Closeable {
        val id = UUID.randomUUID().toString()
        @Volatile var touched = System.currentTimeMillis()
        private val master = CompletableDeferred<String>()
        private val pending = ConcurrentHashMap<String, CompletableDeferred<Reply>>()
        @Volatile private var rateLimited: Pair<Long, Reply>? = null
        private val resources = ConcurrentHashMap<String, String>()
        private val hosts = ConcurrentHashMap.newKeySet<String>()
        private var started = false
        @Volatile private var disposed = false
        private var browser: WebView? = null
        private var dialog: Dialog? = null
        private var relay: JavaScriptReplyProxy? = null
        private var captured: String? = null
        @Volatile var browserUserAgent: String = DEFAULT_UA
        @Volatile var frameOrigin: String? = null
        private var playButton: Button? = null
        private var remotePlayReady = false

        @Synchronized fun start() {
            check(!disposed)
            if (started) return
            started = true
            ui.post {
                try {
                    val activity = CommonActivity.activity ?: error("CloudStream activity unavailable")
                    check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        "Atualize o Android System WebView para usar $provider"
                    }
                    val web = WebView(activity)
                    browser = web
                    browserUserAgent = web.settings.userAgentString?.takeIf { it.isNotBlank() } ?: DEFAULT_UA
                    web.settings.javaScriptEnabled = true
                    web.settings.domStorageEnabled = true
                    web.settings.mediaPlaybackRequiresUserGesture = false
                    CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
                    val cdn = "https://xn--80adalaab0boiodbce7addhh.delivery"
                    val providerOrigins = setOf("https://superflixapi.baby", "https://superflixapi.boats", "https://warezcdn.sbs")
                    val videoOrigins = providerOrigins + setOf(cdn, VIDSRC_PLAYER)
                    WebViewCompat.addWebMessageListener(web, "BRFlixTransport", videoOrigins) { _, message, source, _, reply ->
                        val cleanOrigin = source.toString().trimEnd('/')
                        if (cleanOrigin !in videoOrigins || disposed) return@addWebMessageListener
                        val data = message.data ?: return@addWebMessageListener
                        if (data == "ready" || data == "transport" || data == "play-unavailable") {
                            relay = reply
                            frameOrigin = cleanOrigin
                            remotePlayReady = data != "transport"
                            if (remotePlayReady) playButton?.apply { visibility = View.VISIBLE; requestFocus() }
                            complete()
                        } else try {
                            val obj = JSONObject(data)
                            val future = pending.remove(obj.optString("id")) ?: return@addWebMessageListener
                            val body = obj.optString("body")
                            require(body.length <= 24 * 1024 * 1024)
                            val result = Reply(obj.optInt("status"), Base64.decode(body, Base64.DEFAULT),
                                obj.optString("type", "application/octet-stream"),
                                obj.optString("range").takeUnless { it.isBlank() || it == "null" },
                                obj.optString("url").takeUnless { it.isBlank() || it == "null" })
                            if (result.status == 429) rateLimited = retryAt(obj.optString("retryAfter")) to result
                            future.complete(result)
                        } catch (e: Exception) { fail(e) }
                    }
                    WebViewCompat.addDocumentStartJavaScript(web, TRANSPORT, videoOrigins)
                    // Only ordinary language/server controls; verification widgets are left to the user.
                    WebViewCompat.addDocumentStartJavaScript(web, selectionScript(subbed), providerOrigins)
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val uri = request.url
                            val nativeMp4 = "${uri.scheme}://${uri.host}" in providerOrigins &&
                                uri.path?.startsWith("/player/native/media-source/") == true
                            val cdnHls = uri.host == "xn--80adalaab0boiodbce7addhh.delivery" && uri.path?.endsWith("/master.txt") == true
                            val vidsrcPlaylist = uri.path?.let {
                                it.endsWith(".m3u8") || Regex("/pl/[A-Za-z0-9_-]{40,160}/?").matches(it)
                            } == true
                            val vidsrcHls = vidsrcPlaylist && request.requestHeaders.entries.any {
                                it.key.equals("Referer", true) && it.value.startsWith("$VIDSRC_PLAYER/")
                            }
                            if (uri.scheme == "https" && (cdnHls || vidsrcHls || nativeMp4)) {
                                ui.post { if (!disposed) { captured = uri.toString(); complete() } }
                                // Stop the WebView's own <video> element from retrying /player/native/media-source/
                                // while our local proxy streams it directly to ExoPlayer.
                                if (nativeMp4 && request.requestHeaders.entries.none {
                                    it.key.equals("X-BRFlix-Transport", true) && it.value == "1"
                                }) return WebResourceResponse("video/mp4", null,
                                    java.io.ByteArrayInputStream(ByteArray(0)))
                            }
                            return null
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            request.isForMainFrame && request.url.host != "brflix.lat"
                    }
                    val layout = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(Color.rgb(18, 18, 18))
                    }
                    layout.addView(TextView(activity).apply {
                        text = "Preparando $provider • ${if (subbed) "Legendado" else "Dublado"}"
                        setTextColor(Color.WHITE); setPadding(24, 16, 24, 16)
                    })
                    layout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
                    playButton = Button(activity).apply {
                        text = "Iniciar vídeo"
                        visibility = View.GONE
                        setOnClickListener { requestPlay() }
                    }.also { layout.addView(it, LinearLayout.LayoutParams(-1, -2)) }
                    dialog = Dialog(activity).also { d ->
                        d.setContentView(layout)
                        d.setOnCancelListener { fail(IllegalStateException("Verificação cancelada")) }
                        d.setOnKeyListener { _, code, event ->
                            val playKey = remotePlayReady && code in setOf(KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
                                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                            if (playKey && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) requestPlay()
                            playKey
                        }
                        d.show()
                        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                    web.loadDataWithBaseURL("https://brflix.lat/",
                        "<!doctype html><meta name=viewport content=width=device-width,initial-scale=1>" +
                            "<style>html,body,iframe{margin:0;width:100%;height:100%;border:0;background:#111}</style>" +
                            "<iframe src=\"$embed\" allow=\"autoplay;fullscreen\"></iframe>",
                        "text/html", "UTF-8", null)
                    ui.postDelayed({ if (!master.isCompleted) fail(IllegalStateException("Tempo de verificação esgotado")) }, 90000)
                } catch (e: Exception) { fail(e) }
            }
        }

        private fun complete() {
            val url = captured ?: return
            if (relay == null || master.isCompleted) return
            relay?.postMessage("{\"action\":\"pause\"}")
            master.complete(url)
            dialog?.dismiss(); dialog = null
            // Retain the document and its message channel for subsequent native HLS requests.
            (browser?.parent as? ViewGroup)?.removeView(browser)
        }

        private fun requestPlay() {
            if (!disposed && !master.isCompleted) {
                relay?.postMessage("{\"action\":\"play\"}")
                remotePlayReady = false
                playButton?.visibility = View.GONE
                browser?.requestFocus()
            }
        }

        private fun fail(error: Exception) {
            sessions.remove(id, this)
            master.completeExceptionally(error)
            pending.values.forEach { it.completeExceptionally(error) }
            pending.clear()
            close()
        }

        fun awaitRoot(): String {
            touched = System.currentTimeMillis()
            start()
            return runBlocking { withTimeout(95000) { master.await() } }
        }

        fun fetch(resource: String, range: String?): Pair<String, Reply> {
            val root = awaitRoot()
            val nativeMp4 = URI(root).path.startsWith("/player/native/media-source/")
            if (resource == "master" && nativeMp4) throw MediaTypeMismatchException("Selecione a fonte Dublado (MP4)")
            if (resource == "video.mp4" && !nativeMp4) throw MediaTypeMismatchException("Selecione a fonte HLS")
            val url = if (resource == "master" || resource == "video.mp4") root else resources[resource] ?: error("Recurso expirado")
            rateLimited?.takeIf { it.first > System.currentTimeMillis() }?.let { return url to it.second }
            check(!disposed)
            val requestId = UUID.randomUUID().toString()
            val future = CompletableDeferred<Reply>()
            pending[requestId] = future
            ui.post {
                try {
                    check(!disposed)
                    val request = JSONObject().put("id", requestId).put("url", url)
                    if (range != null) request.put("range", range)
                    (relay ?: error("Sessão indisponível")).postMessage(request.toString())
                } catch (e: Exception) { future.completeExceptionally(e) }
            }
            return try { url to runBlocking { withTimeout(35000) { future.await() } } } finally { pending.remove(requestId) }
        }

        private fun resource(source: String, relative: String): String {
            val sanitized = relative.trim().replace(" ", "%20")
            var url = URI(source).resolve(sanitized)
            // Vidsrc's static playlists omit the token that its HLS loader adds to every same-origin request.
            val root = captured?.let(::URI)
            if (root != null && root.rawAuthority == url.rawAuthority && root.scheme == url.scheme) {
                val token = root.rawQuery?.split('&')?.firstOrNull { it.startsWith("token=") || it.startsWith("cast_token=") }
                if (token != null) {
                    val query = url.rawQuery.orEmpty().split('&').filter {
                        it.isNotEmpty() && !it.startsWith("token=") && !it.startsWith("cast_token=")
                    } + token
                    url = URI(android.net.Uri.parse(url.toString()).buildUpon().encodedQuery(query.joinToString("&")).build().toString())
                }
            }
            require((url.scheme == "https" || url.scheme == "http") && url.userInfo == null && (url.port == -1 || url.port in 1..65535))
            val host = url.host ?: error("Domínio de mídia inválido")
            if (!hosts.contains(host)) {
                require(host.contains('.') && InetAddress.getAllByName(host).all {
                    !it.isAnyLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress &&
                        !it.isSiteLocalAddress && !it.isMulticastAddress
                })
                hosts += host
            }
            require(resources.size < 50000)
            val resource = UUID.randomUUID().toString()
            resources[resource] = url.toString()
            return local(id, resource)
        }

        fun playlist(source: String, body: ByteArray): ByteArray {
            require(body.size <= 8 * 1024 * 1024)
            val attribute = Regex("URI=\"([^\"]+)\"")
            val text = body.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            return text.lineSequence().joinToString("\n") { line ->
                val clean = line.trim()
                if (clean.isNotEmpty() && !clean.startsWith('#')) resource(source, clean)
                else attribute.replace(line) { m ->
                    val raw = m.groupValues[1].trim()
                    if (raw.isEmpty() || raw.startsWith("data:", ignoreCase = true)) m.value
                    else "URI=\"${resource(source, raw)}\""
                }
            }.toByteArray(Charsets.UTF_8)
        }

        fun fetchNativeFallback(url: String, range: String?): Reply {
            val req = Request.Builder().url(url).header("User-Agent", browserUserAgent)
            frameOrigin?.let { origin ->
                req.header("Referer", "$origin/")
                req.header("Origin", origin)
            }
            val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            if (!cookies.isNullOrBlank()) req.header("Cookie", cookies)
            if (range != null) req.header("Range", range)
            http.newCall(req.build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val ct = resp.header("Content-Type") ?: "application/octet-stream"
                val cr = resp.header("Content-Range")
                return Reply(resp.code, bytes, ct, cr, resp.request.url.toString())
            }
        }

        override fun close() {
            if (disposed) return
            disposed = true
            master.completeExceptionally(IllegalStateException("Sessão encerrada"))
            pending.values.forEach { it.completeExceptionally(IllegalStateException("Sessão encerrada")) }
            pending.clear(); resources.clear()
            ui.post { dialog?.dismiss(); dialog = null; browser?.destroy(); browser = null; relay = null; playButton = null }
        }
    }

    private fun normalizeMediaContentType(rawType: String, body: ByteArray): String {
        val lower = rawType.lowercase()
        if (!lower.startsWith("image/") && !lower.contains("octet-stream") && !lower.contains("text/plain")) {
            return rawType
        }
        if (body.size >= 8) {
            val box = String(body, 4, 4, Charsets.US_ASCII)
            if (box == "ftyp" || box == "styp" || box == "moof" || box == "moov") return "video/mp4"
        }
        if (body.size >= 189 && body[0] == 0x47.toByte() && body[188] == 0x47.toByte()) {
            return "video/mp2t"
        }
        return rawType
    }

    private fun serveDirect(client: Socket, entry: DirectEntry, range: String?, headOnly: Boolean) {
        entry.touched = System.currentTimeMillis()
        val req = Request.Builder().url(entry.url)
        for ((k, v) in entry.headers) {
            if (!k.equals("Range", ignoreCase = true)) req.header(k, v)
        }
        // Always send Range (defaulting to bytes=0-) so hosts like genovevo.live return the full MP4
        // instead of a 1-byte HTTP 206 stub when ExoPlayer omits Range on open.
        req.header("Range", range ?: "bytes=0-")
        http.newCall(req.build()).execute().use { resp ->
            val status = resp.code.takeIf { it in 200..599 } ?: 502
            val ct = (resp.header("Content-Type") ?: "video/mp4").substringBefore('\r').substringBefore('\n')
            val cl = resp.header("Content-Length")?.trim()?.toLongOrNull()
            val cr = resp.header("Content-Range")?.takeUnless { it.contains('\r') || it.contains('\n') }
            val headerStr = buildString {
                append("HTTP/1.1 $status Stream\r\n")
                append("Content-Type: $ct\r\n")
                append("Accept-Ranges: bytes\r\n")
                if (cl != null && cl >= 0) append("Content-Length: $cl\r\n")
                if (cr != null) append("Content-Range: $cr\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = client.getOutputStream()
            out.write(headerStr.toByteArray(Charsets.US_ASCII))
            if (!headOnly) {
                resp.body?.byteStream()?.use { stream ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = stream.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            out.flush()
        }
    }

    private class MediaTypeMismatchException(message: String) : IllegalStateException(message)

    /**
     * Streams /player/native/media-source/ MP4 continuously via OkHttp using the verified WebView
     * cookies + UA + Referer (single connection, no 1MB chunk spam / 429 rate-limit).
     * Falls back to WebView fetch() chunked streaming only if OkHttp is rejected (401/403).
     */
    private fun serveNativeMp4(
        client: Socket,
        session: Session,
        range: String?,
        headOnly: Boolean,
        onHeaders: () -> Unit
    ) {
        val root = session.awaitRoot()
        if (!URI(root).path.startsWith("/player/native/media-source/")) {
            throw MediaTypeMismatchException("Selecione a fonte HLS")
        }
        val req = Request.Builder()
            .url(root)
            .header("User-Agent", session.browserUserAgent)
            .header("Range", range ?: "bytes=0-")
        val refOrigin = session.frameOrigin ?: runCatching {
            val u = URI(root); "${u.scheme}://${u.authority}"
        }.getOrNull()
        if (!refOrigin.isNullOrBlank()) {
            req.header("Referer", "$refOrigin/")
            req.header("Origin", refOrigin)
        }
        val cookies = runCatching { CookieManager.getInstance().getCookie(root) }.getOrNull()
        if (!cookies.isNullOrBlank()) req.header("Cookie", cookies)

        var fallbackToWebView = false
        try {
            http.newCall(req.build()).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    fallbackToWebView = true
                    return@use
                }
                val status = resp.code.takeIf { it in 200..599 } ?: 502
                val ct = (resp.header("Content-Type") ?: "video/mp4").substringBefore('\r').substringBefore('\n')
                val cl = resp.header("Content-Length")?.trim()?.toLongOrNull()
                val cr = resp.header("Content-Range")?.takeUnless { it.contains('\r') || it.contains('\n') }
                val headerStr = buildString {
                    append("HTTP/1.1 $status Stream\r\n")
                    append("Content-Type: $ct\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    if (cl != null && cl >= 0) append("Content-Length: $cl\r\n")
                    if (cr != null) append("Content-Range: $cr\r\n")
                    append("Connection: close\r\n\r\n")
                }
                onHeaders()
                val out = client.getOutputStream()
                out.write(headerStr.toByteArray(Charsets.US_ASCII))
                if (!headOnly && status in 200..299) {
                    resp.body?.byteStream()?.use { stream ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = stream.read(buf)
                            if (n <= 0) break
                            session.touched = System.currentTimeMillis()
                            out.write(buf, 0, n)
                        }
                    }
                }
                out.flush()
            }
        } catch (_: Exception) {
            fallbackToWebView = true
        }

        if (fallbackToWebView) {
            streamMp4ViaWebView(client, session, range, onHeaders)
        }
    }

    private fun streamMp4ViaWebView(client: Socket, session: Session, range: String?, onHeaders: () -> Unit) {
        val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toLong() ?: 0L
        val requestedEnd = range?.substringAfter('-')?.toLongOrNull()
        fun chunk(position: Long) = session.fetch("video.mp4",
            "bytes=$position-${minOf(requestedEnd ?: Long.MAX_VALUE, position + 1024 * 1024 - 1)}").second
        var part = chunk(start)
        if (part.status != 206) {
            onHeaders()
            write(client, part.status.takeIf { it in 400..599 } ?: 502, part.bytes, part.type, null)
            return
        }
        val info = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(part.range.orEmpty())
            ?: error("Servidor MP4 sem suporte a intervalos")
        val total = info.groupValues[3].toLong()
        val end = minOf(requestedEnd ?: (total - 1), total - 1)
        require(start <= end && part.status == 206)
        val contentRange = if (range == null) null else "bytes $start-$end/$total"
        onHeaders()
        writeHeaders(client, if (range == null) 200 else 206, end - start + 1, "video/mp4", contentRange)
        val output = client.getOutputStream()
        var position = start
        while (true) {
            val expected = minOf(end, position + 1024 * 1024 - 1)
            require(part.status == 206 && part.range == "bytes $position-$expected/$total" &&
                part.bytes.size.toLong() == expected - position + 1)
            output.write(part.bytes)
            position = expected + 1
            if (position > end) break
            part = chunk(position)
        }
        output.flush()
    }

    private fun serve(socket: Socket) {
        socket.use { client ->
            var responseStarted = false
            try {
                client.soTimeout = 20000
                val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                val first = reader.readLine()?.split(' ') ?: return
                val method = first.getOrNull(0) ?: return
                require(first.size == 3 && (method == "GET" || method == "HEAD") && first[1].length < 512)
                var range: String? = null
                var count = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    require(++count <= 64 && line.length <= 8192)
                    if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
                }
                require(range == null || Regex("bytes=\\d+-\\d*").matches(range!!))
                val path = first[1].split('/')
                if (path.size == 4 && path[1] == secret && path[2] == "direct") {
                    val entry = directStreams[path[3]]
                    if (entry == null) { write(client, 404, ByteArray(0), "text/plain", null); return }
                    serveDirect(client, entry, range, method == "HEAD")
                    return
                }
                val session = path.takeIf { it.size == 4 && it[1] == secret }?.get(2)?.let(sessions::get)
                if (session == null) { write(client, 404, ByteArray(0), "text/plain", null); return }
                if (path[3] == "video.mp4") {
                    serveNativeMp4(client, session, range, method == "HEAD") { responseStarted = true }
                    return
                }
                val (reqUrl, rawResult) = session.fetch(path[3], range)
                // If WebView fetch() failed (status 0 = CORS / cross-origin / >16MB segment), retry natively via OkHttp.
                val result = if (rawResult.status == 0) {
                    runCatching { session.fetchNativeFallback(reqUrl, range) }.getOrDefault(rawResult)
                } else {
                    rawResult
                }
                var body = result.bytes
                var type = result.type
                val effectiveUrl = result.finalUrl ?: reqUrl
                val headerPrefix = body.take(32).toByteArray().toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
                if (result.status in 200..299 && headerPrefix.startsWith("#EXTM3U")) {
                    body = session.playlist(effectiveUrl, body); type = "application/vnd.apple.mpegurl"
                } else if (result.status in 200..299) {
                    type = normalizeMediaContentType(type, body)
                } else {
                    android.util.Log.w("BRFlix", "BrowserStream status=${result.status} host=${runCatching { URI(effectiveUrl).host }.getOrNull()}")
                }
                write(client, result.status.takeIf { it in 200..599 } ?: 502, if (method == "HEAD") ByteArray(0) else body, type, result.range)
            } catch (e: MediaTypeMismatchException) {
                // Keep verified session alive so CloudStream advances straight to the matching (MP4)/(HLS) link!
                if (!responseStarted) try { write(client, 415, ByteArray(0), "text/plain", null) } catch (_: Exception) { }
            } catch (e: Exception) {
                android.util.Log.w("BRFlix", "BrowserStream err: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
                if (!responseStarted) try { write(client, 502, ByteArray(0), "text/plain", null) } catch (_: Exception) { }
            } finally { clients -= client }
        }
    }

    private fun write(socket: Socket, status: Int, body: ByteArray, type: String, range: String?) {
        writeHeaders(socket, status, body.size.toLong(), type, range)
        socket.getOutputStream().apply { write(body); flush() }
    }

    private fun writeHeaders(socket: Socket, status: Int, length: Long, type: String, range: String?) {
        val headers = "HTTP/1.1 $status Stream\r\nContent-Length: $length\r\n" +
            "Content-Type: ${type.substringBefore('\r').substringBefore('\n')}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n" +
            (range?.takeUnless { it.contains('\r') || it.contains('\n') }?.let { "Content-Range: $it\r\n" } ?: "") + "\r\n"
        socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
    }

    override fun close() {
        closed = true
        server.close(); clients.forEach { runCatching { it.close() } }; clients.clear()
        workers.shutdownNow(); sessions.values.forEach { it.close() }; sessions.clear()
        directStreams.clear()
        runCatching { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }

    companion object {
        private const val VIDSRC_PLAYER = "https://stellarconductornexus.com"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        private fun retryAt(value: String, now: Long = System.currentTimeMillis()): Long {
            val seconds = value.trim().toLongOrNull()?.takeIf { it >= 0 }
            if (seconds != null) {
                val delay = TimeUnit.SECONDS.toMillis(seconds)
                return if (delay > Long.MAX_VALUE - now) Long.MAX_VALUE else now + delay
            }
            return runCatching {
                SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { isLenient = false }
                    .parse(value.trim())?.time?.coerceAtLeast(now)
            }.getOrNull() ?: (now + 60000)
        }
        private val TRANSPORT = """
            BRFlixTransport.onmessage=async e=>{
              let m;
              try {
                m=JSON.parse(e.data);
                if(m.action==='play'){
                  const button=document.getElementById('bigPlay');
                  if(button){button.click();return;}
                  if(typeof window.jwplayer==='function'){
                    const player=window.jwplayer();
                    if(typeof player.getState==='function'){
                      player.setMute(true);player.play();return;
                    }
                  }
                  const video=document.querySelector('video');
                  if(video){video.muted=true;await video.play();}
                  else BRFlixTransport.postMessage('play-unavailable');return;
                }
                if(m.action==='pause'){
                  if(typeof window.jwplayer==='function'){
                    const player=window.jwplayer();if(typeof player.pause==='function')player.pause();
                  }
                  if(location.pathname.startsWith('/player/native/media/')){
                    const stop=()=>{
                      if(typeof window.videojs==='function')Object.values(window.videojs.getPlayers()).forEach(p=>{if(p)p.dispose();});
                      document.querySelectorAll('video,audio').forEach(v=>{v.pause();v.removeAttribute('src');v.load();});
                    };
                    if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',()=>setTimeout(stop,0),{once:true});else stop();
                  }
                  document.querySelectorAll('video,audio').forEach(v=>{v.pause();v.muted=true;});return;
                }
                const u=new URL(m.url);
                if(u.protocol!=='https:'&&u.protocol!=='http:')throw Error('scheme');
                const headers=m.range?{Range:m.range}:{};
                if(u.origin===location.origin&&u.pathname.startsWith('/player/native/media-source/'))headers['X-BRFlix-Transport']='1';
                const r=await fetch(u.href,{credentials:'same-origin',headers});
                if(m.range&&u.pathname.startsWith('/player/native/media-source/')&&r.status===200){
                  if(r.body)await r.body.cancel();throw Error('range unsupported');
                }
                const a=new Uint8Array(await r.arrayBuffer());if(a.length>16777216)throw Error('size');
                let s='';for(let i=0;i<a.length;i+=8192)s+=String.fromCharCode(...a.subarray(i,i+8192));
                BRFlixTransport.postMessage(JSON.stringify({id:m.id,status:r.status,url:r.url||u.href,body:btoa(s),type:r.headers.get('content-type'),range:r.headers.get('content-range'),retryAfter:r.headers.get('retry-after')}));
              } catch(x) {
                if(m&&m.action==='play')BRFlixTransport.postMessage('play-unavailable');
                else if(m&&m.id)BRFlixTransport.postMessage(JSON.stringify({id:m.id,status:0,body:''}));
              }
            };
            if(location.origin==='https://stellarconductornexus.com'&&location.pathname.startsWith('/embed/')){
              if(location.pathname.startsWith('/embed/player/'))BRFlixTransport.postMessage('transport');
              else {
                const ready=()=>{if(document.getElementById('bigPlay'))BRFlixTransport.postMessage('ready');};
                if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',ready,{once:true});else ready();
              }
            }else if(location.pathname.startsWith('/video/'))BRFlixTransport.postMessage('ready');
            else if(location.pathname.startsWith('/player/native/media/'))BRFlixTransport.postMessage('transport');
        """.trimIndent()

        private fun selectionScript(subbed: Boolean) = """
            (()=>{
              let language=false,selected=false;
              const timer=setInterval(()=>{
                if(selected){clearInterval(timer);return;}
                const elements=[...document.querySelectorAll('button,a,div,span,[role=button],[onclick]')].reverse();
                if(!language){
                  const label='${if (subbed) "legendado" else "dublado"}';
                  const option=elements.find(e=>e.getClientRects().length&&e.textContent.trim().toLowerCase()===label);
                  if(!option)return;option.click();language=true;return;
                }
                const server=elements.find(e=>e.getClientRects().length&&e.textContent.replace(/\s+/g,' ').trim().toLowerCase()==='servidor principal')
                  ${if (subbed) "||elements.find(e=>e.getClientRects().length&&e.textContent.trim().toLowerCase()==='legendado')" else ""};
                if(server){selected=true;server.click();clearInterval(timer);}
              },700);
              setTimeout(()=>clearInterval(timer),85000);
            })();
        """.trimIndent()
    }
}
