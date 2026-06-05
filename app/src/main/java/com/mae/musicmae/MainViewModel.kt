package com.mae.musicmae

import android.app.Application
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File

sealed class UiState {
    object Idle : UiState()
    data class Processing(val message: String) : UiState()
    data class Ready(val stems: List<StemResult>, val title: String) : UiState()
    data class Err(val message: String) : UiState()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val state = MutableLiveData<UiState>(UiState.Idle)
    val stemPlayer = StemPlayer(app.applicationContext)
    val prefs = PrefsManager(app.applicationContext)

    private var processJob: Job? = null

    fun processUri(uri: Uri, displayName: String) {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            state.value = UiState.Err("Configura tu token de Replicate en ⚙")
            return
        }
        processJob = viewModelScope.launch {
            try {
                state.value = UiState.Processing("Copiando archivo...")
                val file = copyUriToCache(getApplication(), uri, displayName)

                state.value = UiState.Processing("Subiendo a Replicate...")
                val repo = ReplicateRepository(apiKey)
                val audioUrl = repo.uploadAudioFile(file)

                state.value = UiState.Processing("Separando pistas...")
                val stems = repo.runDemucs(audioUrl) { status ->
                    state.postValue(UiState.Processing("Replicate: $status..."))
                }

                downloadAndLoad(stems, repo, displayName)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                state.postValue(UiState.Err(e.message ?: "Error desconocido"))
            }
        }
    }

    fun processYouTube(url: String) {
        val apiKey = prefs.apiKey
        if (apiKey.isBlank()) {
            state.value = UiState.Err("Configura tu token de Replicate en ⚙")
            return
        }
        processJob = viewModelScope.launch {
            try {
                state.value = UiState.Processing("Extrayendo audio de YouTube...")
                val streamUrl = withContext(Dispatchers.IO) {
                    val info = StreamInfo.getInfo(NewPipe.getService(0), url)
                    info.audioStreams
                        .maxByOrNull { it.averageBitrate }
                        ?.url
                        ?: throw Exception("No se encontró stream de audio")
                }

                state.value = UiState.Processing("Separando pistas...")
                val repo = ReplicateRepository(apiKey)
                val stems = repo.runDemucs(streamUrl) { status ->
                    state.postValue(UiState.Processing("Replicate: $status..."))
                }

                val title = url.substringAfter("v=").take(11)
                downloadAndLoad(stems, repo, title)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                state.postValue(UiState.Err(e.message ?: "Error desconocido"))
            }
        }
    }

    fun cancel() {
        processJob?.cancel()
        state.value = UiState.Idle
    }

    private suspend fun downloadAndLoad(stems: List<StemResult>, repo: ReplicateRepository, title: String) {
        val ctx = getApplication<Application>().applicationContext
        val files = stems.mapIndexed { i, stem ->
            state.postValue(UiState.Processing("Descargando ${stem.name}..."))
            val dest = File(ctx.cacheDir, "stem_$i.mp3")
            repo.downloadStem(stem.url, dest)
            dest
        }
        withContext(Dispatchers.Main) {
            stemPlayer.load(files)
            state.value = UiState.Ready(stems, title)
        }
    }

    private suspend fun copyUriToCache(ctx: Context, uri: Uri, name: String): File =
        withContext(Dispatchers.IO) {
            val ext = MimeTypeMap.getFileExtensionFromUrl(name).ifBlank {
                ctx.contentResolver.getType(uri)?.let {
                    MimeTypeMap.getSingleton().getExtensionFromMimeType(it)
                } ?: "mp3"
            }
            val dest = File(ctx.cacheDir, "upload.$ext")
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            dest
        }

    override fun onCleared() {
        super.onCleared()
        stemPlayer.release()
    }
}
