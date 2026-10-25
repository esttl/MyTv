package app.mytv.player

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale
import kotlin.math.min

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var api: XtreamApi
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var tvChannel: TextView
    private lateinit var btnOptions: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvStats: TextView

    private var player: ExoPlayer? = null
    private var index = 0
    private var retries = 0
    private var formatOverride: String? = null
    private var droppedFrames = 0
    private val handler = Handler(Looper.getMainLooper())

    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private val retryRunnable = Runnable { startPlayback() }
    private val statsRunnable = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).let {
            it.hide(WindowInsetsCompat.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        playerView = findViewById(R.id.playerView)
        overlay = findViewById(R.id.overlay)
        tvChannel = findViewById(R.id.tvChannel)
        btnOptions = findViewById(R.id.btnOptions)
        progress = findViewById(R.id.progress)
        tvStatus = findViewById(R.id.tvStatus)
        tvStats = findViewById(R.id.tvStats)

        prefs = Prefs(this)
        if (Repo.playlist.isEmpty() || !prefs.hasAccount) {
            finish()
            return
        }
        api = XtreamApi(prefs.server, prefs.user, prefs.pass)
        index = intent.getIntExtra("index", 0).coerceIn(0, Repo.playlist.size - 1)

        btnOptions.setOnClickListener { showOptions() }
        findViewById<View>(R.id.playerRoot).setOnClickListener {
            if (overlay.visibility == View.VISIBLE) overlay.visibility = View.GONE else showOverlay()
        }
        applyResizeMode()
    }

    override fun onStart() {
        super.onStart()
        if (!::api.isInitialized) return
        createPlayer()
        startPlayback()
        showOverlay()
        handler.post(statsRunnable)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacksAndMessages(null)
        if (::playerView.isInitialized) releasePlayer()
    }

    // ---------------------------------------------------------------- reproductor

    private data class BufferPreset(val minMs: Int, val maxMs: Int, val startMs: Int, val rebufferMs: Int, val liveOffsetMs: Long)

    private fun preset(): BufferPreset = when (prefs.bufferPreset) {
        0 -> BufferPreset(15_000, 30_000, 3_000, 6_000, 8_000)
        2 -> BufferPreset(60_000, 120_000, 20_000, 30_000, 30_000)
        else -> BufferPreset(30_000, 60_000, 10_000, 15_000, 20_000)
    }

    private fun buildPlayer(): ExoPlayer {
        val bp = preset()

        // Buffer grande: el video arranca más tarde, pero aguanta cortes de red.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(bp.minMs, bp.maxMs, bp.startMs, bp.rebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val okHttp = OkHttpDataSource.Factory(Http.client).setUserAgent(Http.USER_AGENT)
        val dataSourceFactory = DefaultDataSource.Factory(this, okHttp)

        // Ajustes tolerantes para MPEG-TS de IPTV (keyframes no IDR, sin AUD).
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )

        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractors)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(10))

        val renderers = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        return ExoPlayer.Builder(this, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            // Mantiene el Wi-Fi en alto rendimiento mientras se reproduce.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    private fun createPlayer() {
        val p = buildPlayer()
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        progress.visibility = View.VISIBLE
                        if (retries == 0) tvStatus.text = "Cargando…"
                    }
                    Player.STATE_READY -> {
                        progress.visibility = View.GONE
                        tvStatus.text = ""
                        retries = 0
                    }
                    Player.STATE_ENDED -> scheduleRetry(null)
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                scheduleRetry(error)
            }
        })
        p.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                this@PlayerActivity.droppedFrames += droppedFrames
            }
        })
        playerView.player = p
        player = p
    }

    private fun releasePlayer() {
        player?.release()
        player = null
        playerView.player = null
    }

    private fun currentFormat(): String = formatOverride ?: prefs.format

    private fun startPlayback() {
        val channel = Repo.playlist.getOrNull(index)
        if (channel == null) {
            finish()
            return
        }
        tvChannel.text = channel.name
        val url = api.streamUrl(channel.id, currentFormat())
        val item = MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(preset().liveOffsetMs)
                    .setMinPlaybackSpeed(1f)
                    .setMaxPlaybackSpeed(1f)
                    .build()
            )
            .build()
        player?.apply {
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
    }

    private fun scheduleRetry(error: PlaybackException?) {
        retries++
        progress.visibility = View.VISIBLE
        val code = error?.errorCodeName?.let { " [$it]" } ?: ""
        tvStatus.text = "Reconectando… intento $retries$code"

        // Si el formato elegido no funciona, prueba el otro una vez.
        if (retries == 3 && formatOverride == null) {
            formatOverride = if (prefs.format == "m3u8") "ts" else "m3u8"
            tvStatus.text = "Probando formato ${formatOverride?.uppercase()}…"
        }
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, min(1000L * retries, 8000L))
    }

    private fun zap(delta: Int) {
        val n = Repo.playlist.size
        if (n == 0) return
        index = (index + delta + n) % n
        retries = 0
        formatOverride = null
        droppedFrames = 0
        handler.removeCallbacks(retryRunnable)
        tvStatus.text = ""
        showOverlay()
        startPlayback()
    }

    // ---------------------------------------------------------------- interfaz

    private fun showOverlay(focusButton: Boolean = false) {
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 5000)
        if (focusButton) btnOptions.requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && ::api.isInitialized) {
            val shown = overlay.visibility == View.VISIBLE
            val first = event.repeatCount == 0
            when (event.keyCode) {
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_SETTINGS -> {
                    if (first) showOptions()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (first) zap(1)
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (first) zap(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (!shown) {
                        showOverlay(focusButton = true)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (!shown) {
                        if (first) zap(1)
                        return true
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (!shown) {
                        if (first) zap(-1)
                        return true
                    }
                }
            }
            if (shown) showOverlay()
        }
        return super.dispatchKeyEvent(event)
    }

    private fun applyResizeMode() {
        playerView.resizeMode = when (prefs.resizeMode) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    private fun updateStats() {
        val p = player
        if (!prefs.showStats || p == null) {
            tvStats.visibility = View.GONE
            return
        }
        tvStats.visibility = View.VISIBLE
        val buffer = p.totalBufferedDuration / 1000.0
        val vf = p.videoFormat
        val res = if (vf != null && vf.height > 0) "${vf.width}x${vf.height}" else "-"
        val live = p.currentLiveOffset
        val delay = if (live != C.TIME_UNSET) String.format(Locale.US, "%.0fs", live / 1000.0) else "-"
        val state = when (p.playbackState) {
            Player.STATE_IDLE -> "idle"
            Player.STATE_BUFFERING -> "buffering"
            Player.STATE_READY -> "ok"
            else -> "fin"
        }
        tvStats.text = String.format(
            Locale.US,
            "Buffer %.1fs · Retraso %s · %s · Descartados %d · %s · %s",
            buffer, delay, res, droppedFrames, state, currentFormat().uppercase()
        )
    }

    // ---------------------------------------------------------------- opciones

    private fun bufferName(i: Int) = when (i) {
        0 -> "Normal (arranca ~3 s)"
        2 -> "Máximo (arranca ~20 s)"
        else -> "Grande (arranca ~10 s)"
    }

    private fun resizeName(i: Int) = when (i) {
        1 -> "Zoom"
        2 -> "Llenar"
        else -> "Ajustar"
    }

    private fun showOptions() {
        showOverlay()
        val labels = arrayOf(
            "Calidad de video…",
            "Formato: ${currentFormat().uppercase()}  (tocar para cambiar)",
            "Buffer: ${bufferName(prefs.bufferPreset)}  (tocar para cambiar)",
            "Estadísticas: ${if (prefs.showStats) "sí" else "no"}",
            "Pantalla: ${resizeName(prefs.resizeMode)}"
        )
        AlertDialog.Builder(this)
            .setTitle("Opciones")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showQuality()
                    1 -> toggleFormat()
                    2 -> cycleBuffer()
                    3 -> {
                        prefs.showStats = !prefs.showStats
                        updateStats()
                    }
                    4 -> {
                        prefs.resizeMode = (prefs.resizeMode + 1) % 3
                        applyResizeMode()
                    }
                }
            }
            .show()
    }

    private fun toggleFormat() {
        val next = if (currentFormat() == "m3u8") "ts" else "m3u8"
        prefs.format = next
        formatOverride = null
        retries = 0
        Toast.makeText(this, "Formato: ${next.uppercase()}", Toast.LENGTH_SHORT).show()
        startPlayback()
    }

    private fun cycleBuffer() {
        prefs.bufferPreset = (prefs.bufferPreset + 1) % 3
        Toast.makeText(this, "Buffer: ${bufferName(prefs.bufferPreset)}", Toast.LENGTH_SHORT).show()
        // El buffer se define al crear el reproductor, así que se recrea.
        releasePlayer()
        createPlayer()
        retries = 0
        startPlayback()
    }

    private fun setParams(change: (TrackSelectionParameters.Builder) -> TrackSelectionParameters.Builder) {
        val p = player ?: return
        p.trackSelectionParameters = change(p.trackSelectionParameters.buildUpon()).build()
    }

    private fun showQuality() {
        val p = player ?: return
        val labels = mutableListOf("Automática")
        val actions = mutableListOf<() -> Unit>()
        actions.add { setParams { b -> b.clearOverridesOfType(C.TRACK_TYPE_VIDEO) } }

        for (group in p.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val f = group.getTrackFormat(i)
                val res = if (f.height > 0) "${f.height}p" else "Variante ${i + 1}"
                val rate = if (f.bitrate > 0) String.format(Locale.US, " · %.1f Mbps", f.bitrate / 1_000_000.0) else ""
                labels.add(res + rate)
                actions.add {
                    setParams { b -> b.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)) }
                }
            }
        }

        if (labels.size <= 2) {
            Toast.makeText(
                this,
                "Este canal solo ofrece una calidad (el proveedor no envía variantes).",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Calidad de video")
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }
}
