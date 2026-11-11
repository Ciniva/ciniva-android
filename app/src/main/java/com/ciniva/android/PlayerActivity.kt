package com.ciniva.android

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * Plays one file. Direct play (the file as is, with byte-range seeking) when [Playback] says the device handles it;
 * otherwise, or when direct play fails, an HLS session from Ciniva that copies what the device decodes and converts
 * the rest. Progress goes back to the server every 10 s and on pause/exit; shows get "Next episode".
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        fun start(context: Context, fileId: Long, title: String, subtitle: String? = null, showId: Long? = null, startMs: Long = -1) {
            context.startActivity(
                Intent(context, PlayerActivity::class.java)
                    .putExtra("file", fileId)
                    .putExtra("title", title)
                    .putExtra("subtitle", subtitle)
                    .putExtra("show", showId ?: 0L)
                    .putExtra("start", startMs),
            )
        }
    }

    private data class Next(val fileId: Long, val label: String)

    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var caps: Playback
    private lateinit var titleView: TextView
    private lateinit var infoView: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var audioButton: Button
    private lateinit var actionButton: Button
    private lateinit var messageView: TextView

    private var file: FileView? = null
    private var audio: AudioInfo? = null
    private var quality = "auto"
    private var forceHls = false
    private var direct = false
    private var session: String? = null
    private var showId = 0L
    private var title = ""
    private var subtitle: String? = null
    private var next: Next? = null
    private var lastReport = 0L
    private var finishedReported = false
    private val ticker = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = intent.getStringExtra("title") ?: ""
        subtitle = intent.getStringExtra("subtitle")
        showId = intent.getLongExtra("show", 0L)
        quality = Session.pref("quality", "auto")
        caps = Playback(this, ContextCompat.getDisplayOrDefault(this))

        buildUi()
        immersive()

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(60_000)
            .setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${Session.token}"))
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        player.addListener(listener)
        view.player = player

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (view.isControllerFullyVisible) view.hideController() else finish()
            }
        })

        load(intent.getLongExtra("file", 0L), intent.getLongExtra("start", -1L))
    }

    /** TV remotes: media keys always reach the player, and D-pad presses with nothing focused show its controls. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val media = event.keyCode in setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
        )
        if (media && view.dispatchMediaKeyEvent(event)) return true
        if ((currentFocus == null) && view.dispatchKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    // ---- UI --------------------------------------------------------------------------------------------------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun pill(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(16), dp(8), dp(16), dp(8))
        val accent = ContextCompat.getColor(context, R.color.accent)
        background = StateListDrawable().apply {
            fun shape(color: Int) = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(color) }
            addState(intArrayOf(android.R.attr.state_focused), shape(accent))
            addState(intArrayOf(android.R.attr.state_pressed), shape(accent))
            addState(intArrayOf(), shape(0x99202530.toInt()))
        }
        setTextColor(
            ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                intArrayOf(0xFF1A1306.toInt(), 0xFF1A1306.toInt(), Color.WHITE),
            ),
        )
        stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = PlayerView(this).apply {
            keepScreenOn = true
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            controllerShowTimeoutMs = 4000
        }
        root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            maxLines = 1
        }
        infoView = TextView(this).apply {
            setTextColor(0xFF9AA2B4.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleView)
            addView(infoView)
        }
        audioButton = pill("Audio") { pickAudio() }
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0x00000000))
            addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(audioButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
            addView(pill("Quality") { pickQuality() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        }
        root.addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))

        actionButton = pill("") { action() }.apply { visibility = View.GONE }
        root.addView(actionButton, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(32), dp(110))
        })

        messageView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
        }
        root.addView(messageView, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.CENTER))

        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> topBar.visibility = visibility })
        setContentView(root)
        showTitles()
    }

    private fun immersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showTitles(info: String = "") {
        titleView.text = listOfNotNull(title, subtitle).joinToString(" · ")
        infoView.text = info
        audioButton.visibility = if ((file?.probe?.audio?.size ?: 0) > 1) View.VISIBLE else View.GONE
    }

    private fun message(text: String?) {
        messageView.text = text
        messageView.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ---- Loading ---------------------------------------------------------------------------------------------

    private fun load(fileId: Long, startMs: Long) {
        lifecycleScope.launch {
            message(null)
            val f = try {
                Api.get<FileView>("/files/$fileId")
            } catch (e: Exception) {
                message("Can't load this video: ${e.message}")
                return@launch
            }
            file = f
            audio = caps.defaultAudio(f.probe)
            forceHls = false
            finishedReported = false
            next = null
            val resume = if (f.watched == true) 0L else f.positionMs ?: 0L
            open(if (startMs >= 0) startMs else resume)
            if (showId != 0L) next = runCatching { findNext(showId, f.id) }.getOrNull()
        }
    }

    private suspend fun open(positionMs: Long) {
        val f = file ?: return
        stopSession()
        val blocker = when {
            quality != "auto" -> Playback.QUALITIES.first { it.id == quality }.label
            forceHls -> "direct play failed"
            else -> caps.directBlocker(f, audio)
        }
        direct = blocker == null
        val item = if (direct) {
            MediaItem.Builder().setUri(Api.url("/files/${f.id}/stream")).build()
        } else {
            val body = Api.json.encodeToString(caps.transcodeRequest(f, quality, audio))
            val started = try {
                Api.json.decodeFromString<TranscodeStarted>(Api.raw("POST", "/files/${f.id}/transcode", body))
            } catch (e: Exception) {
                message("The server couldn't start this video: ${e.message}")
                return
            }
            session = started.session
            MediaItem.Builder()
                .setUri(Session.server + started.playlist + "?token=" + URLEncoder.encode(Session.token, "UTF-8"))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setSubtitleConfigurations(sideloadedSubtitles(f))
                .build()
        }
        android.util.Log.i("Ciniva", "open file ${f.id} at $positionMs: " + if (direct) "direct" else "hls ($blocker) ${caps.transcodeRequest(f, quality, audio)}")
        showTitles(if (direct) "Direct play" else "Converted by the server · $blocker")
        player.setMediaItem(item, positionMs)
        player.prepare()
        player.playWhenReady = true
    }

    /** HLS carries no subtitles; text tracks come from the server as WebVTT instead. */
    private fun sideloadedSubtitles(f: FileView): List<MediaItem.SubtitleConfiguration> =
        f.probe?.subtitles.orEmpty().filter { it.codec in Playback.TEXT_SUBS }.map { s ->
            MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(Api.url("/files/${f.id}/subtitles/${s.index}.vtt")))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(s.language)
                .setLabel(s.title ?: s.language ?: "Track ${s.index}")
                .setSelectionFlags(if (s.forced) C.SELECTION_FLAG_FORCED else 0)
                .build()
        }

    private fun stopSession() {
        val sid = session ?: return
        session = null
        CinivaApp.scope.launch { runCatching { Api.raw("DELETE", "/transcode/$sid") } }
    }

    private suspend fun findNext(showId: Long, fileId: Long): Next? {
        val show = Api.get<ShowView>("/shows/$showId")
        var found = false
        for (season in show.seasons.map { it.seasonNumber }.filter { it > 0 }.sorted()) {
            for (e in Api.get<List<EpisodeView>>("/shows/$showId/seasons/$season")) {
                if (found && e.files.isNotEmpty()) {
                    return Next(e.files.first().id, "S${e.seasonNumber} · E${e.episodeNumber}" + (e.title?.let { " — $it" } ?: ""))
                }
                if (e.files.any { it.id == fileId }) found = true
            }
        }
        return null
    }

    // ---- Player events ---------------------------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            android.util.Log.e("Ciniva", "playback error (direct=$direct, forceHls=$forceHls, session=$session)", error)
            val failed = (error as? ExoPlaybackException)?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }?.rendererFormat?.sampleMimeType
            if (failed != null && failed.startsWith("audio/") && caps.markAudioBroken(failed)) {
                // The device's audio decoder failed on a format it claims; from now on the server converts that audio.
                val at = player.currentPosition
                lifecycleScope.launch { open(at) }
            } else if (direct && !forceHls) {
                // Some files the decoders claim to handle still fail; the server's conversion is the fallback.
                forceHls = true
                val at = player.currentPosition
                lifecycleScope.launch { open(at) }
            } else {
                message("Playback failed: ${error.errorCodeName}")
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (!direct || forceHls) return
            val broken = listOf(C.TRACK_TYPE_VIDEO, C.TRACK_TYPE_AUDIO).any { tracks.containsType(it) && !tracks.isTypeSupported(it) }
            if (broken) onPlayerError(PlaybackException("unsupported track", null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) report(force = true)
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) message(null)
            if (state == Player.STATE_ENDED) {
                reportFinished()
                if (next != null) playNext() else finish()
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (player.isPlaying) report(force = false)
            updateAction()
            ticker.postDelayed(this, 1000)
        }
    }

    override fun onStart() {
        super.onStart()
        ticker.post(tick)
    }

    override fun onStop() {
        super.onStop()
        ticker.removeCallbacks(tick)
        report(force = true)
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSession()
        player.release()
    }

    // ---- Progress, credits, next episode ---------------------------------------------------------------------

    private fun durationMs(): Long {
        val d = player.duration
        return if (d != C.TIME_UNSET && d > 0) d else ((file?.duration ?: 0.0) * 1000).toLong()
    }

    private fun report(force: Boolean) {
        val f = file ?: return
        val d = durationMs()
        val t = player.currentPosition
        if (d <= 0 || t < 1000 || finishedReported) return
        val now = System.currentTimeMillis()
        if (!force && now - lastReport < 10_000) return
        lastReport = now
        CinivaApp.scope.launch { runCatching { Api.send("PUT", "/files/${f.id}/progress", ProgressBody(t, d)) } }
    }

    private fun reportFinished() {
        val f = file ?: return
        val d = durationMs()
        if (d <= 0 || finishedReported) return
        finishedReported = true
        CinivaApp.scope.launch { runCatching { Api.send("PUT", "/files/${f.id}/progress", ProgressBody(d, d)) } }
    }

    private fun creditsStartMs(): Long? {
        val c = file?.creditsStart ?: return null
        val d = durationMs()
        return (c * 1000).toLong().takeIf { d <= 0 || it < d - 5000 }
    }

    private fun updateAction() {
        val f = file
        val d = durationMs()
        val t = player.currentPosition
        val credits = creditsStartMs()
        val atCredits = credits != null && t >= credits
        val label = when {
            f == null || d <= 0 || player.playbackState != Player.STATE_READY -> null
            next != null && (atCredits || d - t < 30_000) -> "Next episode  ▸  ${next!!.label}"
            atCredits -> "Skip credits"
            else -> null
        }
        if (label == null) {
            if (actionButton.visibility != View.GONE) actionButton.visibility = View.GONE
        } else {
            actionButton.text = label
            if (actionButton.visibility != View.VISIBLE) {
                actionButton.visibility = View.VISIBLE
                if (!view.isControllerFullyVisible) actionButton.requestFocus()
            }
        }
    }

    private fun action() {
        actionButton.visibility = View.GONE
        reportFinished()
        if (next != null) playNext() else finish()
    }

    private fun playNext() {
        val n = next ?: return
        stopSession()
        player.stop()
        subtitle = n.label
        showTitles()
        load(n.fileId, 0)
    }

    // ---- Menus -----------------------------------------------------------------------------------------------

    private fun pickAudio() {
        val f = file ?: return
        val tracks = f.probe?.audio.orEmpty()
        if (tracks.isEmpty()) return
        val labels = tracks.map { a ->
            listOfNotNull(
                a.title ?: a.language?.uppercase(),
                a.codec.uppercase() + (if (a.atmos) " Atmos" else ""),
                a.channels?.let { if (it >= 6) "${it - 1}.1" else if (it == 2) "Stereo" else "$it ch" },
            ).joinToString(" · ")
        }.toTypedArray()
        dialog("Audio", labels, tracks.indexOf(audio)) { i ->
            val a = tracks[i]
            audio = a
            val group = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.getOrNull(i)
            if (direct && caps.playsAudio(a.codec) && group != null) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                    .build()
            } else {
                val at = player.currentPosition
                lifecycleScope.launch { open(at) }
            }
        }
    }

    private fun pickQuality() {
        val options = Playback.QUALITIES
        dialog("Quality", options.map { it.label }.toTypedArray(), options.indexOfFirst { it.id == quality }) { i ->
            quality = options[i].id
            Session.setPref("quality", quality)
            forceHls = false
            val at = player.currentPosition
            lifecycleScope.launch { open(at) }
        }
    }

    private fun dialog(title: String, items: Array<String>, selected: Int, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setSingleChoiceItems(items, selected) { d, i ->
                d.dismiss()
                if (i != selected) onPick(i)
            }
            .show()
    }
}
