package io.github.proify.lyricon.localprovider.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

/** Lives in a hooked player, without reading the module's private files. */
object RuntimeBridge {
    @Volatile private var artworkMetadata: android.media.MediaMetadata? = null
    @Volatile private var lastAudioPath: String? = null
    private var artworkJob: kotlinx.coroutines.Job? = null
    private var publishedArtwork: Pair<String, String>? = null
    @Synchronized fun captureArtwork(metadata: android.media.MediaMetadata) {
        artworkMetadata = metadata
        artworkJob?.cancel()
        artworkJob = scope.launch { kotlinx.coroutines.delay(100); publishArtwork(metadata) }
    }
    private fun publishArtwork(metadata: android.media.MediaMetadata) {
        val app = context ?: return
        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
        if (trackKey(title, artist) != trackKey(trackMetadata.title, trackMetadata.artist)) return
        runCatching {
            val artwork = io.github.proify.lyricon.localprovider.artwork.ArtworkCapture.capture(app, metadata, lastAudioPath) ?: return
            val identity = trackKey(title, artist) to artwork
            if (publishedArtwork == identity) return
            val result = app.contentResolver.call(ProviderStore.URI, "artwork", null, Bundle().apply {
                putString("player", app.packageName); putString("title", title); putString("artist", artist); putString("artwork", artwork)
            })
            // A cover arriving before the track report must be retried after that report.
            if (result?.getBoolean("accepted") == true) publishedArtwork = identity
        }
    }
    private val scope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())
    @Volatile var settings = ProviderSettings()
        private set
    private var context: Context? = null
    val appContext: Context? get() = context
    private var retryVersion = -1L
    private var receiver: BroadcastReceiver? = null
    private var onReload: (() -> Unit)? = null
    @Volatile private var trackMetadata = io.github.proify.lyricon.localprovider.search.MatchTarget()
    fun setTrackMetadata(title: String?, artist: String?, album: String?, duration: Long) {
        if (trackKey(trackMetadata.title, trackMetadata.artist) != trackKey(title, artist)) {
            lastAudioPath = null; publishedArtwork = null
        }
        trackMetadata = io.github.proify.lyricon.localprovider.search.MatchTarget(title, artist, album, duration)
    }
    @Volatile private var lastPlayback: io.github.proify.lyricon.localprovider.preview.PlaybackSnapshot? = null
    @Volatile private var previewUntil = 0L
    fun reportPlayback(state: android.media.session.PlaybackState) {
        val app = context ?: return
        val metadata = trackMetadata
        if (metadata.title.isNullOrBlank()) return
        val next = io.github.proify.lyricon.localprovider.preview.PlaybackSnapshot(
            app.packageName, trackKey(metadata.title, metadata.artist), state.position,
            state.lastPositionUpdateTime, state.playbackSpeed,
            state.state == android.media.session.PlaybackState.STATE_PLAYING)
        if (!next.speed.isFinite()) return
        val now = android.os.SystemClock.elapsedRealtime()
        val old = lastPlayback
        if (old != null && old.track == next.track && old.playing == next.playing && old.speed == next.speed &&
            next.updatedElapsedMs - old.updatedElapsedMs in 0..499 &&
            kotlin.math.abs(old.positionAt(now) - next.positionAt(now)) < 200) return
        lastPlayback = next
        if (now < previewUntil) scope.launch { publishPlayback(next) }
    }
    private fun publishPlayback(value: io.github.proify.lyricon.localprovider.preview.PlaybackSnapshot) {
        val app = context ?: return
        if (android.os.SystemClock.elapsedRealtime() >= previewUntil) return
        runCatching { app.contentResolver.call(ProviderStore.URI, "playback", null, Bundle().apply {
            putString("json", ProviderStore.json.encodeToString(value))
        }) }
    }
    fun bindPlaybackIdentity(song: Song): Song = io.github.proify.lyricon.localprovider.search.PlaybackIdentity.bind(song, trackMetadata)
    fun initialize(app: Context, reload: () -> Unit) {
        if (receiver != null) return
        context = app.applicationContext
        onReload = reload
        refresh(false)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // A broadcast is only a wake-up signal; the authorized provider owns commands.
                val pending = goAsync()
                scope.launch { try { refresh(true) } finally { pending.finish() } }
            }
        }.also { ContextCompat.registerReceiver(app, it, IntentFilter(ProviderStore.ACTION_REFRESH), ContextCompat.RECEIVER_EXPORTED) }
    }
    @Synchronized private fun refresh(reload: Boolean) {
        val app = context ?: return
        runCatching {
            val result = app.contentResolver.call(ProviderStore.URI, "config", null, null) ?: return
            val next = ProviderStore.json.decodeFromString<ProviderSettings>(result.getString("json") ?: return)
            val version = result.getLong("retry")
            val changed = next.revision != settings.revision || version != retryVersion
            val wasPreviewing = android.os.SystemClock.elapsedRealtime() < previewUntil
            previewUntil = result.getLong("previewUntil")
            settings = next; retryVersion = version
            if (reload && !wasPreviewing) lastPlayback?.takeIf { it.track == trackKey(trackMetadata.title, trackMetadata.artist) }?.let(::publishPlayback)
            if (reload && changed) onReload?.invoke()
        }.onFailure { android.util.Log.w("LocalProvider", "Module settings unavailable: ${it.javaClass.simpleName}") }
    }
    fun find(title: String?, artist: String?, path: String?): Pair<Song, Boolean>? = runCatching {
        val result = context?.contentResolver?.call(ProviderStore.URI, "find", null, Bundle().apply {
            putString("title", title); putString("artist", artist); putString("path", path)
        }) ?: return null
        val song = ProviderStore.json.decodeFromString<Song>(result.getString("song") ?: return null)
        song to result.getBoolean("manual")
    }.getOrNull()
    fun report(song: Song?, source: String, path: String?, message: String, error: Boolean = false) {
        val app = context ?: return
        lastAudioPath = path
        val metadata = trackMetadata.takeIf { trackKey(it.title, it.artist) == trackKey(song?.name, song?.artist) }
        val report = RuntimeSnapshot(app.packageName, System.currentTimeMillis(), source, path, message,
            song?.copy(duration = metadata?.durationMs?.takeIf { it > 0 } ?: song.duration), error,
            metadata?.album.orEmpty())
        scope.launch {
            runCatching {
                val raw = ProviderStore.json.encodeToString(report)
                if (raw.length <= 380_000) app.contentResolver.call(ProviderStore.URI, "report", null,
                    Bundle().apply { putString("json", raw) })
                artworkMetadata?.let(::publishArtwork)
            }.onFailure { android.util.Log.w("LocalProvider", "Runtime report unavailable: ${it.javaClass.simpleName}") }
        }
    }
}
