package com.ultracinetv

import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
import java.util.concurrent.atomic.AtomicInteger

/** Keeps the verified browser session as transport; CloudStream still decodes the HLS. */
internal class UltraCineBrowserStream : Closeable {
    private val ui = Handler(Looper.getMainLooper())
    private fun resolveHostActivity(): android.app.Activity? {
        CommonActivity.activity?.takeIf { !it.isFinishing && !it.isDestroyed }?.let { return it }
        return try {
            val acra = Class.forName("com.lagradost.cloudstream3.AcraApplication")
            val act = acra.getMethod("getActivity").invoke(null) as? android.app.Activity
            act?.takeIf { !it.isFinishing && !it.isDestroyed }
        } catch (_: Throwable) {
            null
        }
    }
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val secret = UUID.randomUUID().toString()
    private val sessions = ConcurrentHashMap<String, Session>()
    private val directStreams = ConcurrentHashMap<String, DirectEntry>()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(6, 6, 0, TimeUnit.SECONDS, ArrayBlockingQueue(24),
        { task -> Thread(task, "UltraCine-stream").apply { isDaemon = true } })
    private val http = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val noRedirectHttp = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
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
        }, "UltraCine-listener").apply { isDaemon = true; start() }
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

    @Synchronized fun register(
        provider: String,
        embed: String,
        subbed: Boolean,
        baseReferer: String = "https://www.ultracinetv.lat/"
    ): String {
        check(!closed)
        val key = "$embed|$subbed|$baseReferer"
        val cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(20)
        sessions.values.filter { it.touched < cutoff }.forEach { sessions.remove(it.id); it.close() }
        sessions.values.firstOrNull { it.key == key }?.let { return local(it.id, "master") }
        while (sessions.size >= 16) {
            val oldest = sessions.values.minByOrNull { it.touched } ?: break
            sessions.remove(oldest.id); oldest.close()
        }
        val session = Session(provider, embed, subbed, key, baseReferer)
        sessions[session.id] = session
        return local(session.id, "master")
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

    private data class RangeFetchResult(
        val status: Int,
        val bytes: ByteArray,
        val contentType: String,
        val totalSize: Long,
        val finalUrl: String
    )

    private inner class Session(
        val provider: String,
        val embed: String,
        val subbed: Boolean,
        val key: String,
        val baseReferer: String
    ) : Closeable {
        val id = UUID.randomUUID().toString()
        @Volatile var touched = System.currentTimeMillis()
        private val master = CompletableDeferred<String>()
        private val pending = ConcurrentHashMap<String, CompletableDeferred<Reply>>()
        private val nativePending = ConcurrentHashMap<String, CompletableDeferred<Reply>>()
        private val nativeRelays = ConcurrentHashMap<String, JavaScriptReplyProxy>()
        private val nativeFetchInFlight = AtomicInteger(0)
        @Volatile private var rateLimited: Pair<Long, Reply>? = null
        private val resources = ConcurrentHashMap<String, String>()
        private val hosts = ConcurrentHashMap.newKeySet<String>()
        private var started = false
        @Volatile private var disposed = false
        private var browser: WebView? = null
        private var dialog: Dialog? = null
        private var relay: JavaScriptReplyProxy? = null
        private var captured: String? = null
        @Volatile var isNativeMedia = false
        @Volatile var capturedNativeOrigin: String? = null
        @Volatile var capturedNativeHeaders: Map<String, String> = emptyMap()
        @Volatile var resolvedDirectNativeUrl: String? = null
        @Volatile var nativeTotalSize: Long = -1L
        private val remuxLock = Any()
        @Volatile private var remuxedMp4: Mp4ToHlsRemuxer.RemuxedMp4? = null
        @Volatile private var nativeHlsPlaylist: ByteArray? = null
        @Volatile var browserUserAgent: String = DEFAULT_UA
        @Volatile var frameOrigin: String? = null
        private var playButton: Button? = null
        private var statusText: TextView? = null
        private var remotePlayReady = false

        @Synchronized fun start() {
            check(!disposed)
            if (started) return
            started = true
            ui.post {
                try {
                    val activity = resolveHostActivity() ?: error("Host activity unavailable")
                    check(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        "Atualize o Android System WebView para usar $provider"
                    }
                    val web = WebView(activity)
                    browser = web
                    browserUserAgent = web.settings.userAgentString?.takeIf { it.isNotBlank() } ?: DEFAULT_UA
                    web.settings.javaScriptEnabled = true
                    web.settings.domStorageEnabled = true
                    web.settings.mediaPlaybackRequiresUserGesture = false
                    web.webChromeClient = WebChromeClient()
                    CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
                    val cdn = "https://xn--80adalaab0boiodbce7addhh.delivery"
                    val videoOrigins = setOf(cdn, VIDSRC_PLAYER)
                    val providerOrigins = setOf(
                        "https://superflixapi.baby",
                        "https://superflixapi.boats",
                        "https://warezcdn.sbs",
                        "https://streambetter.xyz"
                    )
                    val superflixOrigins = setOf(
                        "https://superflixapi.baby",
                        "https://superflixapi.boats",
                        "https://warezcdn.sbs"
                    )
                    // HLS transport listener: strictly isolated to videoOrigins so provider pages can never hijack it.
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
                    // Separate native-player transport listener for /player/native/media-source/ fallback & pause.
                    WebViewCompat.addWebMessageListener(web, "BRFlixNativeTransport", providerOrigins) { _, message, source, _, reply ->
                        val cleanOrigin = source.toString().trimEnd('/')
                        if (cleanOrigin !in providerOrigins || disposed) return@addWebMessageListener
                        val data = message.data ?: return@addWebMessageListener
                        if (data == "ready") {
                            nativeRelays[cleanOrigin] = reply
                        } else try {
                            val obj = JSONObject(data)
                            val future = nativePending.remove(obj.optString("id")) ?: return@addWebMessageListener
                            val body = obj.optString("body")
                            require(body.length <= 24 * 1024 * 1024)
                            val result = Reply(obj.optInt("status"), Base64.decode(body, Base64.DEFAULT),
                                obj.optString("type", "application/octet-stream"),
                                obj.optString("range").takeUnless { it.isBlank() || it == "null" },
                                obj.optString("url").takeUnless { it.isBlank() || it == "null" })
                            if (result.status == 429) rateLimited = retryAt(obj.optString("retryAfter")) to result
                            future.complete(result)
                        } catch (e: Exception) {
                            android.util.Log.w("UltraCineTV", "NativeTransport err: ${e.message}")
                        }
                    }
                    WebViewCompat.addDocumentStartJavaScript(web, TRANSPORT, videoOrigins)
                    WebViewCompat.addDocumentStartJavaScript(web, NATIVE_TRANSPORT, providerOrigins)
                    // Only ordinary language/server controls; verification widgets are left to the user.
                    WebViewCompat.addDocumentStartJavaScript(web, selectionScript(subbed), superflixOrigins)
                    val allowedMainHost = runCatching { URI(baseReferer).host }.getOrNull() ?: "www.ultracinetv.lat"
                    val isStreamBetter = provider.equals("StreamBetter", ignoreCase = true)
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val uri = request.url
                            val uriOrigin = if (uri.scheme == "https" && uri.host != null) "https://${uri.host}" else null
                            val pathStr = uri.path.orEmpty()
                            val lowerPath = pathStr.lowercase()
                            val nativeMedia = uriOrigin != null && uriOrigin in superflixOrigins &&
                                pathStr.startsWith("/player/native/media-source/")
                            val streamBetterMedia = isStreamBetter && uri.scheme == "https" && uri.host != null &&
                                !isIgnoredMediaHost(uri.host!!) &&
                                isStreamBetterMediaPath(lowerPath)
                            if (nativeMedia || streamBetterMedia) {
                                // Let our own WebView fallback fetch() pass directly to the network.
                                if (nativeFetchInFlight.get() > 0) return null
                                val headersCopy = request.requestHeaders?.toMap().orEmpty()
                                val originForRelay = if (uriOrigin in providerOrigins) uriOrigin else "https://streambetter.xyz"
                                ui.post {
                                    if (!disposed && !master.isCompleted) {
                                        captured = uri.toString()
                                        capturedNativeOrigin = originForRelay
                                        capturedNativeHeaders = headersCopy
                                        completeNative()
                                    }
                                }
                                // Prevent the hidden WebView's <video> from streaming the same media concurrently.
                                val emptyMime = if (lowerPath.endsWith(".m3u8") || lowerPath.endsWith("/master.txt")) {
                                    "application/vnd.apple.mpegurl"
                                } else {
                                    "video/mp4"
                                }
                                return WebResourceResponse(emptyMime, null, ByteArrayInputStream(ByteArray(0)))
                            }
                            if (isStreamBetter && uri.scheme == "https" && !master.isCompleted &&
                                uri.host != null && !isIgnoredMediaHost(uri.host!!) && !isStaticAssetPath(lowerPath)
                            ) {
                                android.util.Log.i("UltraCineTV", "SB req: ${uri.host}${pathStr.take(100)}")
                            }
                            val cdnHls = uri.host == "xn--80adalaab0boiodbce7addhh.delivery" && pathStr.endsWith("/master.txt")
                            val vidsrcPlaylist = (lowerPath.endsWith(".m3u8") || Regex("/pl/[A-Za-z0-9_-]{40,160}/?").matches(pathStr))
                            val vidsrcHls = vidsrcPlaylist && request.requestHeaders.entries.any {
                                it.key.equals("Referer", true) && it.value.startsWith("$VIDSRC_PLAYER/")
                            }
                            if (uri.scheme == "https" && (cdnHls || vidsrcHls)) {
                                ui.post { if (!disposed) { captured = uri.toString(); complete() } }
                            }
                            return null
                        }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            request.isForMainFrame && request.url.host != allowedMainHost
                    }
                    val layout = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setBackgroundColor(Color.rgb(18, 18, 18))
                    }
                    statusText = TextView(activity).apply {
                        text = "Preparando $provider • ${if (subbed) "Legendado" else "Dublado"}"
                        setTextColor(Color.WHITE); setPadding(24, 16, 24, 16)
                    }.also { layout.addView(it) }
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
                    web.loadDataWithBaseURL(baseReferer,
                        "<!doctype html><meta name=viewport content=width=device-width,initial-scale=1>" +
                            "<style>html,body,iframe{margin:0;width:100%;height:100%;border:0;background:#111}</style>" +
                            "<iframe src=\"$embed\" allow=\"autoplay *; encrypted-media *; picture-in-picture *; fullscreen *\" allowfullscreen></iframe>",
                        "text/html", "UTF-8", null)
                    ui.postDelayed({ if (!master.isCompleted) fail(IllegalStateException("Tempo de verificação esgotado")) }, 90000)
                } catch (e: Exception) { fail(e) }
            }
        }

        private fun complete() {
            val url = captured ?: return
            if (isNativeMedia || relay == null || master.isCompleted) return
            relay?.postMessage("{\"action\":\"pause\"}")
            master.complete(url)
            dialog?.dismiss(); dialog = null
            // Retain the document and its message channel for subsequent native HLS requests.
            (browser?.parent as? ViewGroup)?.removeView(browser)
        }

        private fun completeNative() {
            val url = captured ?: return
            if (master.isCompleted) return
            isNativeMedia = true
            capturedNativeOrigin?.let { origin ->
                nativeRelays[origin]?.postMessage("{\"action\":\"stop\"}")
            }
            master.complete(url)
            dialog?.dismiss(); dialog = null
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
            nativePending.values.forEach { it.completeExceptionally(error) }
            nativePending.clear()
            close()
        }

        fun awaitRoot(): String {
            touched = System.currentTimeMillis()
            start()
            return runBlocking { withTimeout(95000) { master.await() } }
        }

        fun fetch(resource: String, range: String?): Pair<String, Reply> {
            val root = awaitRoot()
            val url = if (resource == "master") root else resources[resource] ?: error("Recurso expirado")
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

        private fun fetchViaNativeWebView(url: String, range: String?): Reply? {
            val origin = capturedNativeOrigin ?: return null
            val nativeRelay = nativeRelays[origin] ?: return null
            check(!disposed)
            val requestId = UUID.randomUUID().toString()
            val future = CompletableDeferred<Reply>()
            nativePending[requestId] = future
            nativeFetchInFlight.incrementAndGet()
            ui.post {
                try {
                    check(!disposed)
                    val request = JSONObject().put("id", requestId).put("url", url)
                    if (range != null) request.put("range", range)
                    nativeRelay.postMessage(request.toString())
                } catch (e: Exception) { future.completeExceptionally(e) }
            }
            return try {
                runBlocking { withTimeout(35000) { future.await() } }
            } catch (_: Exception) {
                null
            } finally {
                nativePending.remove(requestId)
                nativeFetchInFlight.decrementAndGet()
            }
        }

        /**
         * Reads a single byte range from a `/player/native/media-source/...` endpoint using OkHttp
         * with manual cross-host redirect handling (sending Cookie + captured Referer ONLY on the
         * provider host and stripping them on cross-host CDN redirects). Falls back to WebView
         * `BRFlixNativeTransport` only if the provider host rejects OkHttp (401/403/503).
         */
        private fun fetchNativeSingleChunk(rootUrl: String, offset: Long, length: Int): RangeFetchResult {
            touched = System.currentTimeMillis()
            val endByte = offset + length - 1
            val rangeHeader = "bytes=$offset-$endByte"
            val rootHost = runCatching { URI(rootUrl).host.orEmpty().lowercase() }.getOrDefault("")

            fun executeOkHttpRange(targetUrl: String, isOriginalHost: Boolean): RangeFetchResult? {
                val req = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", browserUserAgent)
                    .header("Range", rangeHeader)
                if (isOriginalHost) {
                    val cookies = runCatching { CookieManager.getInstance().getCookie(targetUrl) }.getOrNull()
                    if (!cookies.isNullOrBlank()) req.header("Cookie", cookies)
                    val capturedRef = capturedNativeHeaders.entries
                        .firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
                    if (!capturedRef.isNullOrBlank()) {
                        req.header("Referer", capturedRef)
                    } else if (!capturedNativeOrigin.isNullOrBlank()) {
                        req.header("Referer", "$capturedNativeOrigin/")
                    }
                    capturedNativeHeaders.entries
                        .firstOrNull { it.key.equals("Accept-Language", ignoreCase = true) }?.value
                        ?.takeIf { it.isNotBlank() }?.let { req.header("Accept-Language", it) }
                    req.header("Accept", "*/*")
                }
                noRedirectHttp.newCall(req.build()).execute().use { resp ->
                    val code = resp.code
                    if (code in setOf(301, 302, 303, 307, 308)) {
                        val loc = resp.header("Location")?.trim() ?: return null
                        val nextUrl = URI(targetUrl).resolve(loc).toString()
                        val nextHost = runCatching { URI(nextUrl).host.orEmpty().lowercase() }.getOrDefault("")
                        return RangeFetchResult(code, ByteArray(0), "", -1L, nextUrl.also {
                            if (nextHost != rootHost) { /* handled by loop */ }
                        })
                    }
                    val ct = resp.header("Content-Type") ?: "application/octet-stream"
                    val cr = resp.header("Content-Range")
                    val cl = resp.header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
                    val totalSize = parseTotalFromContentRange(cr) ?: if (code == 200 && cl > 0) cl else -1L
                    if (code !in 200..299) {
                        return RangeFetchResult(code, ByteArray(0), ct, totalSize, targetUrl)
                    }
                    val stream = resp.body?.byteStream() ?: return RangeFetchResult(code, ByteArray(0), ct, totalSize, targetUrl)
                    val out = ByteArrayOutputStream(minOf(length, 256 * 1024))
                    val buf = ByteArray(32 * 1024)
                    var remaining = length
                    while (remaining > 0) {
                        val n = stream.read(buf, 0, minOf(buf.size, remaining))
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        remaining -= n
                    }
                    return RangeFetchResult(code, out.toByteArray(), ct, totalSize, targetUrl)
                }
            }

            // 1. Fast path: reuse resolved cross-host CDN URL directly when cached.
            resolvedDirectNativeUrl?.let { cachedUrl ->
                val direct = runCatching { executeOkHttpRange(cachedUrl, isOriginalHost = false) }.getOrNull()
                if (direct != null && direct.status in 200..299 && direct.bytes.isNotEmpty()) {
                    return direct
                }
                resolvedDirectNativeUrl = null
            }

            // 2. Walk from provider rootUrl following redirects manually so cross-host CDNs never receive
            //    provider cookies or Referer/Origin headers.
            var curUrl = rootUrl
            var lastStatus = 0
            for (hop in 0 until 6) {
                val curHost = runCatching { URI(curUrl).host.orEmpty().lowercase() }.getOrDefault("")
                val isOrig = curHost == rootHost
                val res = runCatching { executeOkHttpRange(curUrl, isOrig) }.getOrNull()
                if (res == null) {
                    lastStatus = 0
                    break
                }
                if (res.status in setOf(301, 302, 303, 307, 308)) {
                    curUrl = res.finalUrl
                    continue
                }
                lastStatus = res.status
                if (res.status in 200..299) {
                    if (!isOrig) resolvedDirectNativeUrl = curUrl
                    return res
                }
                break
            }

            // 3. Fallback: if provider host rejected OkHttp (e.g. TLS check 401/403/503 or network error),
            //    use same-origin WebView fetch() on the verified session.
            if (lastStatus == 0 || lastStatus == 401 || lastStatus == 403 || lastStatus == 503) {
                val wv = fetchViaNativeWebView(rootUrl, rangeHeader)
                if (wv != null && wv.status in 200..299) {
                    val total = parseTotalFromContentRange(wv.range) ?: if (wv.status == 200) wv.bytes.size.toLong() else -1L
                    return RangeFetchResult(wv.status, wv.bytes, wv.type, total, wv.finalUrl ?: rootUrl)
                }
                if (wv != null && wv.status > 0) {
                    return RangeFetchResult(wv.status, wv.bytes, wv.type, -1L, wv.finalUrl ?: rootUrl)
                }
            }
            error("Falha ao ler mídia nativa (HTTP $lastStatus)")
        }

        private fun createNativeRangeReader(rootUrl: String, knownTotalSize: Long): Mp4ToHlsRemuxer.ByteRangeReader {
            return object : Mp4ToHlsRemuxer.ByteRangeReader {
                override fun readRange(offset: Long, length: Int): ByteArray {
                    if (length <= 0) return ByteArray(0)
                    val targetLen = if (knownTotalSize > 0) {
                        minOf(length.toLong(), (knownTotalSize - offset).coerceAtLeast(0L)).toInt()
                    } else length
                    if (targetLen <= 0) return ByteArray(0)
                    val out = ByteArray(targetLen)
                    var filled = 0
                    var attempts = 0
                    while (filled < targetLen && attempts++ < 16) {
                        val chunk = fetchNativeSingleChunk(rootUrl, offset + filled, targetLen - filled)
                        require(chunk.status in 200..299 && chunk.bytes.isNotEmpty()) {
                            "Native range read failed @${offset + filled} (HTTP ${chunk.status})"
                        }
                        // If server returned 200 (ignored Range) for a non-zero offset, fail fast rather than corrupting offsets.
                        if (chunk.status == 200 && offset + filled > 0L) {
                            error("Servidor de vídeo não suporta busca por intervalo (HTTP 200)")
                        }
                        val copy = minOf(chunk.bytes.size, targetLen - filled)
                        System.arraycopy(chunk.bytes, 0, out, filled, copy)
                        filled += copy
                    }
                    return if (filled == targetLen) out else out.copyOf(filled)
                }
            }
        }

        fun ensureNativePrepared(): Pair<String, ByteArray> {
            val rootUrl = awaitRoot()
            remuxedMp4?.let { remux ->
                return "application/vnd.apple.mpegurl" to remux.buildPlaylist(
                    initUrl = local(id, "init.mp4"),
                    segmentUrl = { idx -> local(id, "seg-$idx.m4s") }
                ).toByteArray(Charsets.UTF_8)
            }
            nativeHlsPlaylist?.let { hls ->
                return "application/vnd.apple.mpegurl" to hls
            }
            synchronized(remuxLock) {
                remuxedMp4?.let { remux ->
                    return "application/vnd.apple.mpegurl" to remux.buildPlaylist(
                        initUrl = local(id, "init.mp4"),
                        segmentUrl = { idx -> local(id, "seg-$idx.m4s") }
                    ).toByteArray(Charsets.UTF_8)
                }
                nativeHlsPlaylist?.let { hls ->
                    return "application/vnd.apple.mpegurl" to hls
                }

                val probe = fetchNativeSingleChunk(rootUrl, 0L, 65536)
                require(probe.status in 200..299 && probe.bytes.isNotEmpty()) {
                    "Mídia nativa indisponível (HTTP ${probe.status})"
                }
                val prefix = probe.bytes.take(32).toByteArray().toString(Charsets.UTF_8).removePrefix("\uFEFF").trimStart()
                if (prefix.startsWith("#EXTM3U")) {
                    val fullBytes = if (probe.totalSize > probe.bytes.size && probe.totalSize <= 8 * 1024 * 1024) {
                        createNativeRangeReader(rootUrl, probe.totalSize).readRange(0L, probe.totalSize.toInt())
                    } else {
                        probe.bytes
                    }
                    val rewritten = playlist(probe.finalUrl, fullBytes)
                    nativeHlsPlaylist = rewritten
                    return "application/vnd.apple.mpegurl" to rewritten
                }

                require(Mp4ToHlsRemuxer.looksLikeMp4(probe.bytes)) {
                    "Formato de mídia não suportado (${probe.contentType})"
                }
                nativeTotalSize = probe.totalSize
                val reader = createNativeRangeReader(rootUrl, probe.totalSize)
                val parsed = Mp4ToHlsRemuxer.parse(reader, probe.bytes, probe.totalSize)
                remuxedMp4 = parsed
                val m3u8 = parsed.buildPlaylist(
                    initUrl = local(id, "init.mp4"),
                    segmentUrl = { idx -> local(id, "seg-$idx.m4s") }
                ).toByteArray(Charsets.UTF_8)
                return "application/vnd.apple.mpegurl" to m3u8
            }
        }

        fun getNativeInitSegment(): ByteArray {
            touched = System.currentTimeMillis()
            ensureNativePrepared()
            return remuxedMp4?.initSegment ?: error("Init segment indisponível")
        }

        fun getNativeMediaSegment(index: Int): ByteArray {
            touched = System.currentTimeMillis()
            val rootUrl = awaitRoot()
            val remux = remuxedMp4 ?: run {
                ensureNativePrepared()
                remuxedMp4 ?: error("Remux MP4 indisponível")
            }
            val reader = createNativeRangeReader(rootUrl, nativeTotalSize)
            return remux.buildMediaSegment(index, reader)
        }

        fun fetchNativeHlsResource(resource: String, range: String?): Pair<String, Reply> {
            touched = System.currentTimeMillis()
            val url = resources[resource] ?: error("Recurso expirado")
            val wv = fetchViaNativeWebView(url, range)
            if (wv != null && wv.status in 200..299) return url to wv
            val req = Request.Builder().url(url).header("User-Agent", browserUserAgent)
            val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            if (!cookies.isNullOrBlank()) req.header("Cookie", cookies)
            if (range != null) req.header("Range", range)
            http.newCall(req.build()).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                val ct = resp.header("Content-Type") ?: "application/octet-stream"
                val cr = resp.header("Content-Range")
                return url to Reply(resp.code, bytes, ct, cr, resp.request.url.toString())
            }
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
            pending.clear()
            nativePending.values.forEach { it.completeExceptionally(IllegalStateException("Sessão encerrada")) }
            nativePending.clear()
            nativeRelays.clear()
            resources.clear()
            remuxedMp4 = null
            nativeHlsPlaylist = null
            ui.post { dialog?.dismiss(); dialog = null; browser?.destroy(); browser = null; relay = null; playButton = null; statusText = null }
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

    private fun serve(socket: Socket) {
        socket.use { client ->
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
                val resource = path[3]

                // Wait for verification and check if this session resolved to a native MP4/media-source.
                session.awaitRoot()
                if (session.isNativeMedia) {
                    when {
                        resource == "master" -> {
                            val (ct, body) = session.ensureNativePrepared()
                            write(client, 200, if (method == "HEAD") ByteArray(0) else body, ct, null)
                            return
                        }
                        resource == "init.mp4" -> {
                            val initBytes = session.getNativeInitSegment()
                            write(client, 200, if (method == "HEAD") ByteArray(0) else initBytes, "video/mp4", null)
                            return
                        }
                        resource.startsWith("seg-") && resource.endsWith(".m4s") -> {
                            val idx = resource.removePrefix("seg-").removeSuffix(".m4s").toIntOrNull()
                            if (idx == null || idx < 0) {
                                write(client, 404, ByteArray(0), "text/plain", null)
                                return
                            }
                            val segBytes = session.getNativeMediaSegment(idx)
                            write(client, 200, if (method == "HEAD") ByteArray(0) else segBytes, "video/mp4", null)
                            return
                        }
                    }
                }

                val (reqUrl, rawResult) = if (session.isNativeMedia) {
                    session.fetchNativeHlsResource(resource, range)
                } else {
                    session.fetch(resource, range)
                }
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
                    android.util.Log.w("UltraCineTV", "BrowserStream status=${result.status} host=${runCatching { URI(effectiveUrl).host }.getOrNull()}")
                }
                write(client, result.status.takeIf { it in 200..599 } ?: 502, if (method == "HEAD") ByteArray(0) else body, type, result.range)
            } catch (e: Exception) {
                android.util.Log.w("UltraCineTV", "BrowserStream err: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
                try { write(client, 502, ByteArray(0), "text/plain", null) } catch (_: Exception) { }
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

        private fun isIgnoredMediaHost(host: String): Boolean {
            val h = host.lowercase()
            return h.contains("cloudflare") || h.contains("google") || h.contains("gstatic") ||
                h.contains("servetraff") || h.contains("doubleclick") || h.contains("analytics") ||
                h.contains("facebook") || h.contains("twitter") || h.contains("fonts.")
        }

        private fun isStaticAssetPath(lowerPath: String): Boolean =
            lowerPath.endsWith(".js") || lowerPath.endsWith(".css") || lowerPath.endsWith(".png") ||
                lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg") || lowerPath.endsWith(".webp") ||
                lowerPath.endsWith(".svg") || lowerPath.endsWith(".woff") || lowerPath.endsWith(".woff2") ||
                lowerPath.endsWith(".ico") || lowerPath.endsWith(".gif")

        private fun isStreamBetterMediaPath(lowerPath: String): Boolean {
            if (isStaticAssetPath(lowerPath) || lowerPath.endsWith(".vtt") || lowerPath.endsWith(".srt")) return false
            return lowerPath.endsWith(".m3u8") || lowerPath.endsWith("/master.txt") || lowerPath.endsWith(".mp4") ||
                lowerPath.contains("/media-source/") || lowerPath.contains("/hls/") || lowerPath.contains("/manifest")
        }

        private fun parseTotalFromContentRange(header: String?): Long? {
            if (header.isNullOrBlank()) return null
            return Regex("bytes\\s+\\d+-\\d+/(\\d+)").find(header)?.groupValues?.get(1)?.toLongOrNull()
        }

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
                  document.querySelectorAll('video,audio').forEach(v=>{v.pause();v.muted=true;});return;
                }
                const u=new URL(m.url);
                if(u.protocol!=='https:'&&u.protocol!=='http:')throw Error('scheme');
                const r=await fetch(u.href,{credentials:'same-origin',headers:m.range?{Range:m.range}:{}});
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
        """.trimIndent()

        private val NATIVE_TRANSPORT = """
            BRFlixNativeTransport.onmessage=async e=>{
              let m;
              try {
                m=JSON.parse(e.data);
                if(m.action==='stop'){
                  const stop=()=>{
                    try{if(typeof window.videojs==='function')Object.values(window.videojs.getPlayers()).forEach(p=>{if(p)p.dispose();});}catch(_){}
                    document.querySelectorAll('video,audio').forEach(v=>{v.pause();v.muted=true;v.removeAttribute('src');v.load();});
                  };
                  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',()=>setTimeout(stop,0),{once:true});else stop();
                  return;
                }
                const u=new URL(m.url);
                if(u.protocol!=='https:'&&u.protocol!=='http:')throw Error('scheme');
                const r=await fetch(u.href,{credentials:'same-origin',headers:m.range?{Range:m.range}:{}});
                const a=new Uint8Array(await r.arrayBuffer());if(a.length>16777216)throw Error('size');
                let s='';for(let i=0;i<a.length;i+=8192)s+=String.fromCharCode(...a.subarray(i,i+8192));
                BRFlixNativeTransport.postMessage(JSON.stringify({id:m.id,status:r.status,url:r.url||u.href,body:btoa(s),type:r.headers.get('content-type'),range:r.headers.get('content-range'),retryAfter:r.headers.get('retry-after')}));
              } catch(x) {
                if(m&&m.id)BRFlixNativeTransport.postMessage(JSON.stringify({id:m.id,status:0,body:''}));
              }
            };
            if(location.pathname.startsWith('/player/native/media/')||location.origin==='https://streambetter.xyz'){
              BRFlixNativeTransport.postMessage('ready');
            }
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
