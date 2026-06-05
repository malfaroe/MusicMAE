package com.mae.musicmae

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object YouTubeExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val pipedInstances = listOf(
        "https://pipedapi.adminforge.de",
        "https://piped-api.privacy.com.de",
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.tokhmi.xyz"
    )

    private val invidiousInstances = listOf(
        "https://invidious.jing.rocks",
        "https://invidious.nerdvpn.de",
        "https://invidious.tiekoetter.com",
        "https://inv.tux.pizza"
    )

    suspend fun getAudioDownloadUrl(youtubeUrl: String): String = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(youtubeUrl)
            ?: throw Exception("URL inválida. Usa: youtube.com/watch?v=... o youtu.be/...")

        val errors = mutableListOf<String>()

        // 1. IOS client — no userAgent en body, sin apiKey en URL
        try {
            return@withContext youtubePlayerApi(
                videoId,
                clientName = "IOS",
                clientVersion = "19.45.4",
                clientId = 5,
                userAgent = "com.google.ios.youtube/19.45.4 (iPhone; CPU iPhone OS 17_6 like Mac OS X) AppleWebKit/605.1.15",
                apiKey = null,
                extraContext = mapOf("deviceModel" to "iPhone16,2", "timeZone" to "UTC")
            )
        } catch (e: Exception) { errors += "ios: ${e.message}" }

        // 2. TVHTML5_SIMPLY_EMBEDDED_PLAYER — cliente de embedding, ruta diferente a PO tokens
        try {
            return@withContext youtubeTvEmbedApi(videoId)
        } catch (e: Exception) { errors += "tv_embed: ${e.message}" }

        // 3. ANDROID — versión vieja que aún puede funcionar sin token
        try {
            return@withContext youtubePlayerApi(
                videoId,
                clientName = "ANDROID",
                clientVersion = "18.11.34",
                clientId = 3,
                userAgent = "com.google.android.youtube/18.11.34 (Linux; U; Android 11) gzip",
                apiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM394"
            )
        } catch (e: Exception) { errors += "android: ${e.message}" }

        // 4. Piped
        for (instance in pipedInstances) {
            try {
                return@withContext fromPiped(instance, videoId)
            } catch (e: Exception) { errors += "${instance.removePrefix("https://pipedapi.").removePrefix("https://")}: ${e.message}" }
        }

        // 5. Invidious
        for (instance in invidiousInstances) {
            try {
                return@withContext fromInvidious(instance, videoId)
            } catch (e: Exception) { errors += "${instance.removePrefix("https://")}: ${e.message}" }
        }

        throw Exception(errors.joinToString("\n"))
    }

    private fun youtubePlayerApi(
        videoId: String,
        clientName: String,
        clientVersion: String,
        clientId: Int,
        userAgent: String,
        apiKey: String?,
        extraContext: Map<String, String> = emptyMap()
    ): String {
        // userAgent va SOLO en el header HTTP, no en el body JSON
        val clientObj = JSONObject().apply {
            put("clientName", clientName)
            put("clientVersion", clientVersion)
            put("hl", "en")
            put("gl", "US")
            put("utcOffsetMinutes", 0)
            for ((k, v) in extraContext) put(k, v)
        }
        val body = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().put("client", clientObj))
        }.toString().toRequestBody("application/json".toMediaType())

        val url = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false" +
                if (apiKey != null) "&key=$apiKey" else ""

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("User-Agent", userAgent)
            .header("X-YouTube-Client-Name", clientId.toString())
            .header("X-YouTube-Client-Version", clientVersion)
            .post(body)
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: throw Exception("sin respuesta"))
            checkPlayability(json)
            extractBestAudioUrl(json)
        }
    }

    private fun youtubeTvEmbedApi(videoId: String): String {
        val clientObj = JSONObject().apply {
            put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER")
            put("clientVersion", "2.0")
            put("hl", "en")
            put("gl", "US")
            put("utcOffsetMinutes", 0)
        }
        val body = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().apply {
                put("client", clientObj)
                put("thirdParty", JSONObject().put("embedUrl", "https://www.youtube.com"))
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/6.0 TV Safari/538.1")
            .header("X-YouTube-Client-Name", "85")
            .header("X-YouTube-Client-Version", "2.0")
            .post(body)
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: throw Exception("sin respuesta"))
            checkPlayability(json)
            extractBestAudioUrl(json)
        }
    }

    private fun checkPlayability(json: JSONObject) {
        val playability = json.optJSONObject("playabilityStatus") ?: return
        val status = playability.optString("status", "OK")
        if (status != "OK") {
            throw Exception(playability.optString("reason").ifBlank { status })
        }
    }

    private fun extractBestAudioUrl(json: JSONObject): String {
        val adaptiveFormats = json.optJSONObject("streamingData")
            ?.optJSONArray("adaptiveFormats")
            ?: throw Exception("sin adaptiveFormats")

        var bestUrl = ""; var bestBitrate = -1

        for (i in 0 until adaptiveFormats.length()) {
            val f = adaptiveFormats.getJSONObject(i)
            val url = f.optString("url", "")
            if (url.isNotBlank() && "audio/mp4" in f.optString("mimeType", "")) {
                val br = f.optInt("bitrate", 0)
                if (br > bestBitrate) { bestBitrate = br; bestUrl = url }
            }
        }
        if (bestUrl.isBlank()) {
            for (i in 0 until adaptiveFormats.length()) {
                val f = adaptiveFormats.getJSONObject(i)
                val url = f.optString("url", "")
                if (url.isNotBlank() && "audio" in f.optString("mimeType", "")) {
                    val br = f.optInt("bitrate", 0)
                    if (br > bestBitrate) { bestBitrate = br; bestUrl = url }
                }
            }
        }
        if (bestUrl.isBlank()) throw Exception("todos los formatos usan signatureCipher (no hay URL directa)")
        return bestUrl
    }

    private fun fromPiped(baseUrl: String, videoId: String): String {
        val request = Request.Builder()
            .url("$baseUrl/streams/$videoId")
            .header("User-Agent", "Mozilla/5.0 (Android 14)")
            .header("Accept", "application/json")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: throw Exception("sin respuesta")
            if (body.startsWith("<") || !body.startsWith("{")) throw Exception("HTML en lugar de JSON")
            val json = JSONObject(body)
            val streams = json.optJSONArray("audioStreams") ?: throw Exception("sin audioStreams")
            var bestUrl = ""; var bestBitrate = -1
            for (i in 0 until streams.length()) {
                val s = streams.getJSONObject(i)
                val url = s.optString("url", "")
                val br = s.optInt("bitrate", 0)
                if (url.isNotBlank() && br > bestBitrate) { bestBitrate = br; bestUrl = url }
            }
            if (bestUrl.isBlank()) throw Exception("sin URL en audioStreams")
            bestUrl
        }
    }

    private fun fromInvidious(baseUrl: String, videoId: String): String {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/videos/$videoId?fields=adaptiveFormats")
            .header("User-Agent", "Mozilla/5.0 (Android 14)")
            .header("Accept", "application/json")
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: throw Exception("sin respuesta")
            if (body.startsWith("<")) throw Exception("HTML en lugar de JSON")
            val json = JSONObject(body)
            val formats = json.optJSONArray("adaptiveFormats") ?: throw Exception("sin adaptiveFormats")
            var bestUrl = ""; var bestBitrate = -1
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                val type = f.optString("type", "")
                if ("audio/mp4" in type) {
                    val br = f.optInt("bitrate", 0)
                    if (br > bestBitrate) { bestBitrate = br; bestUrl = f.getString("url") }
                }
            }
            if (bestUrl.isBlank()) {
                for (i in 0 until formats.length()) {
                    val f = formats.getJSONObject(i)
                    if ("audio" in f.optString("type", "")) {
                        val br = f.optInt("bitrate", 0)
                        if (br > bestBitrate) { bestBitrate = br; bestUrl = f.getString("url") }
                    }
                }
            }
            if (bestUrl.isBlank()) throw Exception("0 formatos de audio")
            bestUrl
        }
    }

    fun extractVideoId(url: String): String? =
        Regex("(?:v=|youtu\\.be/|shorts/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
}
