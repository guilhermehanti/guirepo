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
import org.json.JSONObject
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
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
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(12),
        { task -> Thread(task, "BRFlix-stream").apply { isDaemon = true } })
    @Volatile private var closed = false

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

    @Synchronized fun register(provider: String, embed: String, subbed: Boolean): String {
        check(!closed)
        val key = "$embed|$subbed"
        val cutoff = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(20)
        sessions.values.filter { it.touched < cutoff }.forEach { sessions.remove(it.id); it.close() }
        sessions.values.firstOrNull { it.key == key }?.let { return local(it.id, "master") }
        while (sessions.size >= 16) {
            val oldest = sessions.values.minByOrNull { it.touched } ?: break
            sessions.remove(oldest.id); oldest.close()
        }
        val session = Session(provider, embed, subbed, key)
        sessions[session.id] = session
        return local(session.id, "master")
    }

    private fun local(session: String, resource: String) =
        "http://127.0.0.1:${server.localPort}/$secret/$session/$resource"

    private data class Reply(val status: Int, val bytes: ByteArray, val type: String, val range: String?)

    private inner class Session(val provider: String, val embed: String, val subbed: Boolean, val key: String) : Closeable {
        val id = UUID.randomUUID().toString()
        @Volatile var touched = System.currentTimeMillis()
        private val master = CompletableDeferred<String>()
        private val pending = ConcurrentHashMap<String, CompletableDeferred<Reply>>()
        private val resources = ConcurrentHashMap<String, String>()
        private val hosts = ConcurrentHashMap.newKeySet<String>()
        private var started = false
        @Volatile private var disposed = false
        private var browser: WebView? = null
        private var dialog: Dialog? = null
        private var relay: JavaScriptReplyProxy? = null
        private var captured: String? = null
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
                    web.settings.javaScriptEnabled = true
                    web.settings.domStorageEnabled = true
                    web.settings.mediaPlaybackRequiresUserGesture = false
                    CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
                    val cdn = "https://xn--80adalaab0boiodbce7addhh.delivery"
                    val videoOrigins = setOf(cdn, VIDSRC_PLAYER)
                    WebViewCompat.addWebMessageListener(web, "BRFlixTransport", videoOrigins) { _, message, source, _, reply ->
                        if (source.toString().trimEnd('/') !in videoOrigins || disposed) return@addWebMessageListener
                        val data = message.data ?: return@addWebMessageListener
                        if (data == "ready" || data == "transport" || data == "play-unavailable") {
                            relay = reply
                            remotePlayReady = data != "transport"
                            if (remotePlayReady) playButton?.apply { visibility = View.VISIBLE; requestFocus() }
                            complete()
                        } else try {
                            val obj = JSONObject(data)
                            val future = pending.remove(obj.optString("id")) ?: return@addWebMessageListener
                            val body = obj.optString("body")
                            require(body.length <= 24 * 1024 * 1024)
                            future.complete(Reply(obj.optInt("status"), Base64.decode(body, Base64.DEFAULT),
                                obj.optString("type", "application/octet-stream"),
                                obj.optString("range").takeUnless { it.isBlank() || it == "null" }))
                        } catch (e: Exception) { fail(e) }
                    }
                    WebViewCompat.addDocumentStartJavaScript(web, TRANSPORT, videoOrigins)
                    // Only ordinary language/server controls; verification widgets are left to the user.
                    WebViewCompat.addDocumentStartJavaScript(web, selectionScript(subbed), setOf(
                        "https://superflixapi.baby", "https://superflixapi.boats", "https://warezcdn.sbs"))
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val uri = request.url
                            val cdnHls = uri.host == "xn--80adalaab0boiodbce7addhh.delivery" && uri.path?.endsWith("/master.txt") == true
                            val vidsrcPlaylist = uri.path?.let {
                                it.endsWith(".m3u8") || Regex("/pl/[A-Za-z0-9_-]{40,160}/?").matches(it)
                            } == true
                            val vidsrcHls = vidsrcPlaylist && request.requestHeaders.entries.any {
                                it.key.equals("Referer", true) && it.value.startsWith("$VIDSRC_PLAYER/")
                            }
                            if (uri.scheme == "https" && (cdnHls || vidsrcHls)) {
                                ui.post { if (!disposed) { captured = uri.toString(); complete() } }
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

        fun fetch(resource: String, range: String?): Pair<String, Reply> {
            touched = System.currentTimeMillis()
            start()
            val root = runBlocking { withTimeout(95000) { master.await() } }
            val url = if (resource == "master") root else resources[resource] ?: error("Recurso expirado")
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
            var url = URI(source).resolve(relative.trim())
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
            require(url.scheme == "https" && url.userInfo == null && (url.port == -1 || url.port == 443))
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
            return body.toString(Charsets.UTF_8).lineSequence().joinToString("\n") { line ->
                val clean = line.trim()
                if (clean.isNotEmpty() && !clean.startsWith('#')) resource(source, clean)
                else attribute.replace(line) { "URI=\"${resource(source, it.groupValues[1])}\"" }
            }.toByteArray(Charsets.UTF_8)
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

    private fun serve(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 15000
                val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                val first = reader.readLine()?.split(' ') ?: return
                require(first.size == 3 && first[0] == "GET" && first[1].length < 512)
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
                val session = path.takeIf { it.size == 4 && it[1] == secret }?.get(2)?.let(sessions::get)
                if (session == null) { write(client, 404, ByteArray(0), "text/plain", null); return }
                val (url, result) = session.fetch(path[3], range)
                var body = result.bytes
                var type = result.type
                if (result.status == 200 && body.take(16).toByteArray().toString(Charsets.UTF_8).trim().startsWith("#EXTM3U")) {
                    body = session.playlist(url, body); type = "application/vnd.apple.mpegurl"
                }
                write(client, result.status.takeIf { it in 200..599 } ?: 502, body, type, result.range)
            } catch (_: Exception) {
                try { write(client, 502, ByteArray(0), "text/plain", null) } catch (_: Exception) { }
            } finally { clients -= client }
        }
    }

    private fun write(socket: Socket, status: Int, body: ByteArray, type: String, range: String?) {
        val headers = "HTTP/1.1 $status Stream\r\nContent-Length: ${body.size}\r\n" +
            "Content-Type: ${type.substringBefore('\r').substringBefore('\n')}\r\nConnection: close\r\n" +
            (range?.takeUnless { it.contains('\r') || it.contains('\n') }?.let { "Content-Range: $it\r\n" } ?: "") + "\r\n"
        socket.getOutputStream().apply { write(headers.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }

    override fun close() {
        closed = true
        server.close(); clients.forEach { runCatching { it.close() } }; clients.clear()
        workers.shutdownNow(); sessions.values.forEach { it.close() }; sessions.clear()
    }

    companion object {
        private const val VIDSRC_PLAYER = "https://stellarconductornexus.com"
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
                if(u.protocol!=='https:')throw Error('scheme');
                const r=await fetch(u.href,{credentials:'same-origin',headers:m.range?{Range:m.range}:{}});
                const a=new Uint8Array(await r.arrayBuffer());if(a.length>16777216)throw Error('size');
                let s='';for(let i=0;i<a.length;i+=8192)s+=String.fromCharCode(...a.subarray(i,i+8192));
                BRFlixTransport.postMessage(JSON.stringify({id:m.id,status:r.status,body:btoa(s),type:r.headers.get('content-type'),range:r.headers.get('content-range')}));
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
