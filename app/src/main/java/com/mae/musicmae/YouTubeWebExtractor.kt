package com.mae.musicmae

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CancellableContinuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Loads YouTube in a real WebView — bypasses PO token requirements since YouTube
// cannot distinguish it from a real mobile browser.
class YouTubeWebExtractor(private val context: Context) {

    fun extract(videoId: String, cont: CancellableContinuation<String>) {
        require(Looper.myLooper() == Looper.getMainLooper()) { "Must be on main thread" }
        var done = false
        val wv = WebView(context)

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            mediaPlaybackRequiresUserGesture = false
            userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        fun finish(result: Result<String>) {
            if (done) return
            done = true
            Handler(Looper.getMainLooper()).post { wv.stopLoading(); wv.destroy() }
            if (cont.isActive) result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
        }

        wv.addJavascriptInterface(object {
            @JavascriptInterface fun onUrl(url: String) = finish(Result.success(url))
            @JavascriptInterface fun onErr(msg: String) = finish(Result.failure(Exception(msg)))
        }, "App")

        wv.webViewClient = object : WebViewClient() {

            // Intercept CDN audio requests — YouTube's player makes these after decrypting ciphers
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val url = req.url.toString()
                if (!done && "googlevideo.com" in url &&
                    ("mime=audio%2F" in url || "itag=139" in url || "itag=140" in url || "itag=141" in url || "itag=251" in url)) {
                    // Strip range/rn params so we get the full file
                    val clean = buildFullAudioUrl(req.url)
                    finish(Result.success(clean))
                }
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (done) return
                // Try to get direct URL from ytInitialPlayerResponse (present in page HTML)
                view.evaluateJavascript(JS_EXTRACTOR, null)
                // Trigger player to start buffering so CDN requests are intercepted
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!done) view.evaluateJavascript(
                        "(function(){ var v=document.querySelector('video'); if(v){v.volume=0;v.play();} })()", null)
                }, 2000)
            }
        }

        Handler(Looper.getMainLooper()).postDelayed({
            finish(Result.failure(Exception("timeout (35s)")))
        }, 35_000)

        cont.invokeOnCancellation { finish(Result.failure(Exception("cancelado"))) }

        wv.loadUrl("https://m.youtube.com/watch?v=$videoId&hl=en&autoplay=0")
    }

    private fun buildFullAudioUrl(uri: Uri): String {
        val skip = setOf("range", "rn", "rbuf", "sq")
        val builder = uri.buildUpon().clearQuery()
        uri.queryParameterNames.forEach { name ->
            if (name !in skip) uri.getQueryParameter(name)?.let { builder.appendQueryParameter(name, it) }
        }
        return builder.build().toString()
    }

    companion object {
        private val JS_EXTRACTOR = """
            (function() {
                try {
                    var r = window.ytInitialPlayerResponse;
                    if (!r || !r.streamingData) return;
                    var formats = r.streamingData.adaptiveFormats || [];
                    var best = null, br = 0;
                    for (var i = 0; i < formats.length; i++) {
                        var f = formats[i];
                        if (f.url && f.mimeType && f.mimeType.indexOf('audio') >= 0) {
                            if ((f.bitrate || 0) > br) { br = f.bitrate || 0; best = f.url; }
                        }
                    }
                    if (best) App.onUrl(best);
                    // else: wait for shouldInterceptRequest to capture CDN audio stream
                } catch(e) { /* silent: fall through to shouldInterceptRequest */ }
            })()
        """.trimIndent()
    }
}
