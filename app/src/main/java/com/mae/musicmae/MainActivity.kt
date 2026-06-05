package com.mae.musicmae

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.mae.musicmae.databinding.ActivityMainBinding
import com.mae.musicmae.databinding.ItemStemBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()

    private val stemBindings: List<ItemStemBinding> by lazy {
        listOf(binding.stem0, binding.stem1, binding.stem2, binding.stem3)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            cursor.moveToFirst()
            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        } ?: "audio"
        vm.processUri(uri, name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupObserver()
        setupButtons()
    }

    private fun setupObserver() {
        vm.state.observe(this) { state ->
            when (state) {
                is UiState.Idle -> showSection(idle = true)
                is UiState.Processing -> {
                    showSection(processing = true)
                    binding.tvStatus.text = state.message
                }
                is UiState.Ready -> {
                    showSection(player = true)
                    setupPlayer(state)
                }
                is UiState.Err -> {
                    showSection(idle = true)
                    Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupButtons() {
        binding.btnPickFile.setOnClickListener {
            filePicker.launch(arrayOf("audio/*", "audio/mpeg", "audio/wav", "audio/flac", "audio/mp4"))
        }

        binding.btnYoutube.setOnClickListener { showYouTubeDialog() }

        binding.btnSettings.setOnClickListener { showApiKeyDialog() }

        binding.btnCancel.setOnClickListener { vm.cancel() }

        binding.btnNewSong.setOnClickListener {
            vm.stemPlayer.stop()
            handler.removeCallbacks(progressUpdater)
            vm.state.value = UiState.Idle
        }

        binding.btnPlayPause.setOnClickListener {
            if (vm.stemPlayer.isPlaying) {
                vm.stemPlayer.pause()
                binding.btnPlayPause.text = "▶"
                handler.removeCallbacks(progressUpdater)
            } else {
                vm.stemPlayer.play()
                binding.btnPlayPause.text = "⏸"
                handler.post(progressUpdater)
            }
        }

        binding.btnStop.setOnClickListener {
            vm.stemPlayer.stop()
            binding.btnPlayPause.text = "▶"
            handler.removeCallbacks(progressUpdater)
            binding.seekProgress.progress = 0
        }

        binding.seekProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val pos = (progress * vm.stemPlayer.getDurationMs() / 100)
                vm.stemPlayer.seekTo(pos)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        binding.btnPitchDown.setOnClickListener {
            vm.stemPlayer.setPitch(vm.stemPlayer.pitchSemitones - 1)
            binding.tvPitch.text = "${vm.stemPlayer.pitchSemitones.toInt()} st"
        }
        binding.btnPitchUp.setOnClickListener {
            vm.stemPlayer.setPitch(vm.stemPlayer.pitchSemitones + 1)
            binding.tvPitch.text = "${vm.stemPlayer.pitchSemitones.toInt()} st"
        }
        binding.btnTempoDown.setOnClickListener {
            val s = (vm.stemPlayer.speedFactor - 0.1f).coerceAtLeast(0.5f)
            vm.stemPlayer.setSpeed(s)
            binding.tvTempo.text = "%.1f×".format(s)
        }
        binding.btnTempoUp.setOnClickListener {
            val s = (vm.stemPlayer.speedFactor + 0.1f).coerceAtMost(2.0f)
            vm.stemPlayer.setSpeed(s)
            binding.tvTempo.text = "%.1f×".format(s)
        }
    }

    private fun setupPlayer(state: UiState.Ready) {
        binding.tvSongTitle.text = state.title
        binding.btnPlayPause.text = "▶"
        binding.tvPitch.text = "0 st"
        binding.tvTempo.text = "1.0×"

        stemBindings.forEachIndexed { i, sb ->
            if (i < state.stems.size) {
                sb.root.visibility = View.VISIBLE
                sb.tvStemName.text = state.stems[i].name
                sb.btnMute.isChecked = false
                sb.seekVolume.progress = 100
                sb.btnMute.setOnCheckedChangeListener { _, isChecked ->
                    vm.stemPlayer.setMuted(i, isChecked)
                }
                sb.seekVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) vm.stemPlayer.setVolume(i, progress / 100f)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar) {}
                })
            } else {
                sb.root.visibility = View.GONE
            }
        }
    }

    private fun updateProgress() {
        val dur = vm.stemPlayer.getDurationMs()
        val pos = vm.stemPlayer.getPositionMs()
        if (dur > 0) {
            binding.seekProgress.progress = (pos * 100 / dur).toInt()
            binding.tvPosition.text = formatTime(pos)
            binding.tvDuration.text = formatTime(dur)
        }
    }

    private fun formatTime(ms: Long): String {
        val sec = ms / 1000
        return "%d:%02d".format(sec / 60, sec % 60)
    }

    private fun showYouTubeDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_youtube)
        }
        AlertDialog.Builder(this)
            .setTitle("URL de YouTube")
            .setView(input)
            .setPositiveButton("Separar") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotBlank()) vm.processYouTube(url)
                else Toast.makeText(this, "Ingresa una URL válida", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showApiKeyDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.hint_api_key)
            setText(vm.prefs.apiKey)
        }
        AlertDialog.Builder(this)
            .setTitle("Token de Replicate")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                vm.prefs.apiKey = input.text.toString().trim()
                Toast.makeText(this, "Token guardado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showSection(idle: Boolean = false, processing: Boolean = false, player: Boolean = false) {
        binding.llIdle.visibility = if (idle) View.VISIBLE else View.GONE
        binding.llProcessing.visibility = if (processing) View.VISIBLE else View.GONE
        binding.llPlayer.visibility = if (player) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(progressUpdater)
    }
}
