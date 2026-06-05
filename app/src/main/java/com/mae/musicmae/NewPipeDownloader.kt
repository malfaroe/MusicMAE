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

    private val cobaltInstances = listOf(
        "https://api.cobalt.tools",
        "https://cobalt.api.timelessnesses.me"
    )

    private val invidiousInstances = listOf(
        "https://invidious.kavin.rocks",
        "https://yewtu.be",
        "https://inv.vern.cc",
        "https://invidious.privacyredirect.com",
        "https://invidious.nerdvpn.de"
    )

    suspend fun getAudioDownloadUrl(youtubeUrl: String): String = withContext(Dispatchers.IO) {
        val videoId = extractVideoId(youtubeUrl)
            ?: throw Exception("URL inválida. Usa: youtube.com/watch?v=... o youtu.be/...")

        val errors = mutableListOf<String>()

        for (instance in cobaltInstances) {
            try {
                return@withContext fromCobalt(instance, youtubeUrl)
            } catch (e: Exception) {
                errors += "cobalt(${instance.substringAfterLast("/")}): ${e.message}"
            }
        }

        for (instance in invidiousInstances) {
            try {
                return@withContext fromInvidious(instance, videoId)
            } catch (e: Exception) {
                errors += "${instance.removePrefix("https://")}: ${e.message}"
            }
        }

        throw Exception(errors.joinToString("\n"))
    }

    private fun fromCobalt(baseUrl: String, url: String): String {
        val body = JSONObject().apply {
            put("url", url)
            put("downloadMode", "audio")
            put("audioFormat", "mp3")
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("User-Agent", "Mozilla/5.0 (Android 14)")
            .post(body)
            .build()

        return client.newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string() ?: throw Exception("sin respuesta"))
            val status = json.optString("status", "")
            if (status == "redirect" || status == "tunnel") {
                json.getString("url")
            } else {
                throw Exception(json.optJSONObject("error")?.optString("code") ?: "status=$status http=${response.code}")
            }
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
            val json = JSONObject(response.body?.string() ?: throw Exception("sin respuesta"))
            val formats = json.getJSONArray("adaptiveFormats")

            var bestUrl = ""
            var bestBitrate = -1

            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                val type = f.optString("type", "")
                if ("audio/mp4" in type) {
                    val bitrate = f.optInt("bitrate", 0)
                    if (bitrate > bestBitrate) { bestBitrate = bitrate; bestUrl = f.getString("url") }
                }
            }
            if (bestUrl.isBlank()) {
                for (i in 0 until formats.length()) {
                    val f = formats.getJSONObject(i)
                    if ("audio" in f.optString("type", "")) {
                        val bitrate = f.optInt("bitrate", 0)
                        if (bitrate > bestBitrate) { bestBitrate = bitrate; bestUrl = f.getString("url") }
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
