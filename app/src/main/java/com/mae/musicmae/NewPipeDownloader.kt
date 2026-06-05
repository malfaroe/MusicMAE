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
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.in",
        "https://piped-api.garudalinux.org",
        "https://api.piped.projectsegfau.lt",
        "https://pipedapi.adminforge.de"
    )

    suspend fun getAudioDownloadUrl(youtubeUrl: String): String = withContext(Dispatchers.IO) {
        extractVideoId(youtubeUrl)
            ?: throw Exception("URL inválida. Usa: youtube.com/watch?v=... o youtu.be/...")

        val errors = mutableListOf<String>()

        // 1. Intenta cobalt.tools
        try {
            return@withContext fromCobalt(youtubeUrl)
        } catch (e: Exception) {
            errors += "cobalt: ${e.message}"
        }

        // 2. Intenta instancias de Piped
        val videoId = extractVideoId(youtubeUrl)!!
        for (instance in pipedInstances) {
            try {
                return@withContext fromPiped(instance, videoId)
            } catch (e: Exception) {
                errors += "${instance.substringAfter("https://")}: ${e.message}"
            }
        }

        throw Exception("No se pudo obtener audio.\n${errors.joinToString("\n")}")
    }

    private fun fromCobalt(url: String): String {
        val bodyJson = JSONObject().apply {
            put("url", url)
            put("downloadMode", "audio")
            put("audioFormat", "mp3")
        }
        val request = Request.Builder()
            .url("https://api.cobalt.tools/")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val json = JSONObject(response.body?.string() ?: throw Exception("Sin respuesta"))
            val status = json.optString("status", "")
            if (status == "redirect" || status == "tunnel") return json.getString("url")
            val errCode = json.optJSONObject("error")?.optString("code") ?: status
            throw Exception(errCode)
        }
    }

    private fun fromPiped(baseUrl: String, videoId: String): String {
        val request = Request.Builder()
            .url("$baseUrl/streams/$videoId")
            .header("User-Agent", "Mozilla/5.0 (Android 14)")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            val body = response.body?.string() ?: throw Exception("Sin respuesta")
            val json = JSONObject(body)

            if (json.has("message") && !json.has("audioStreams"))
                throw Exception(json.optString("message", "error"))

            val streams = json.getJSONArray("audioStreams")
            var bestUrl = ""
            var bestBitrate = -1

            // Preferir mp4/m4a por compatibilidad con Demucs
            for (i in 0 until streams.length()) {
                val s = streams.getJSONObject(i)
                val mime = s.optString("mimeType", "")
                val bitrate = s.optInt("bitrate", 0)
                if (("mp4" in mime || "m4a" in mime) && bitrate > bestBitrate) {
                    bestBitrate = bitrate
                    bestUrl = s.getString("url")
                }
            }
            // Fallback: cualquier formato
            if (bestUrl.isBlank()) {
                for (i in 0 until streams.length()) {
                    val s = streams.getJSONObject(i)
                    val bitrate = s.optInt("bitrate", 0)
                    if (bitrate > bestBitrate) {
                        bestBitrate = bitrate
                        bestUrl = s.getString("url")
                    }
                }
            }
            if (bestUrl.isBlank()) throw Exception("0 streams encontrados")
            bestUrl
        }
    }

    fun extractVideoId(url: String): String? =
        Regex("(?:v=|youtu\\.be/|shorts/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
}
