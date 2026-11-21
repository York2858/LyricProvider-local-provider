package io.github.proify.lyricon.localprovider.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.proify.lyricon.localprovider.data.RuntimeBridge
import io.github.proify.lyricon.localprovider.lyrics.LyricInfoEncoder
import io.github.proify.lyricon.localprovider.lyrics.LocalTtml
import io.github.proify.lyricon.lyric.model.RichLyricLine
import java.util.UUID
import org.json.JSONObject

/** Publishes on Neutron's own session; Lyricon remains an independent consumer. */
internal object NeutronLyricPublisher {
    private const val TAG = "LocalProvider"
    private const val KEY = "lyricInfo"
    private const val MAX_METADATA_BYTES = 512 * 1024
    private const val MAX_TRANSLATION_BYTES = 128 * 1024
    private const val ORIGINAL_FALLBACK_DELAY_MS = 750L
    internal const val SOURCE_KEY = "io.github.proify.lyricon.localprovider.neutron_lyric_source"
    private const val WRITEBACK_ACTION = "io.github.proify.lyricon.localprovider.NEUTRON_LYRIC_WRITEBACK"
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val writingMetadata = ThreadLocal<Boolean>()
    private val writingPlaybackState = ThreadLocal<Boolean>()
    private var active: Active? = null
    private var actionSession: MediaSession? = null
    private var actionIcon = android.R.drawable.ic_menu_manage
    private var generation = 0L
    private var registered = false

    fun initialize(context: Context) {
        if (registered || context.packageName != Constants.NEUTRON_PLAYER_PACKAGE) return
        actionIcon = context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_menu_manage
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val source = intent.getStringExtra("source") ?: return
                val translation = intent.getStringExtra("translation")
                val revision = intent.getLongExtra("revision", 0)
                if (translation != null && (translation.isBlank() || translation.length > MAX_TRANSLATION_BYTES ||
                        translation.toByteArray().size > MAX_TRANSLATION_BYTES)) return
                runCatching { applyWriteback(source, translation, revision) }.onFailure(::logFailure)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(WRITEBACK_ACTION),
            "android.permission.STATUS_BAR_SERVICE", null, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun isWritingMetadata(): Boolean = writingMetadata.get() == true

    fun isWritingPlaybackState(): Boolean = writingPlaybackState.get() == true

    @Synchronized
    fun preparePlaybackState(session: MediaSession, state: PlaybackState): PlaybackState =
        NeutronTranslationAction.update(state, actionSession === session &&
            active?.request?.session === session && active?.payload?.translated == true &&
            Constants.NEUTRON_PLAYER_PACKAGE in RuntimeBridge.settings.players, actionIcon)

    @Synchronized
    fun onMetadataPublished(session: MediaSession, metadata: MediaMetadata?) {
        val payload = active?.takeIf { it.request.session === session }?.payload
        val published = metadata?.getString(KEY)
        if (payload?.translated == true && published != null &&
            (published == payload.compatible || published == payload.overlay)) {
            updateActionSession(session)
        } else if (actionSession === session) {
            updateActionSession(null)
        }
    }

    private fun updateActionSession(next: MediaSession?) {
        val previous = actionSession
        if (previous === next) return
        actionSession = next
        mainHandler.post {
            previous?.let(::refreshPlaybackAction)
            next?.let(::refreshPlaybackAction)
        }
    }

    @Synchronized
    private fun refreshPlaybackAction(session: MediaSession) {
        runCatching {
            val current = session.controller.playbackState ?: return
            val updated = preparePlaybackState(session, current)
            if (current === updated) return
            writingPlaybackState.set(true)
            try {
                session.setPlaybackState(updated)
                val available = session.controller.playbackState?.customActions.orEmpty()
                    .any { it.action == NeutronTranslationAction.ACTION }
                log(Log.INFO, "Neutron translation action published=$available")
            } finally {
                writingPlaybackState.remove()
            }
        }.onFailure(::logFailure)
    }

    /** Runs before the host write, so a same-track update also carries the cached payload. */
    @Synchronized
    fun prepare(session: MediaSession, metadata: MediaMetadata?): MediaMetadata? {
        val previous = active
        if (previous != null && previous.request.session !== session &&
            previous.request.session.isActive && !session.isActive
        ) return metadata

        val track = metadata?.let(Track::from)
        if (track == null) {
            if (previous?.request?.session === session) active = null
            return metadata?.let { withLyrics(it, null) }
        }
        val state = previous?.takeIf { it.request.session === session && it.request.track == track }
            ?: Active(Request(session, track, ++generation)).also { active = it }
        return withPayload(metadata, state.payload)
    }

    @Synchronized
    fun capture(session: MediaSession?, metadata: MediaMetadata): Request? =
        active?.request?.takeIf {
            (session == null || it.session === session) && it.track == Track.from(metadata)
        }

    fun publish(request: Request, lines: List<RichLyricLine>, ttml: String?): String? =
        runCatching {
            val track = request.track
            val ttmlLines = ttml?.let(LocalTtml::bridgeLines).orEmpty()
            val renderedLines = ttmlLines.ifEmpty { lines }
            val compatible = LyricInfoEncoder.encode(
                track.title, track.artist, track.album, track.mediaId, request.generation, renderedLines
            ) ?: return null
            val overlay = ttml?.takeIf { ttmlLines.isNotEmpty() }?.let {
                LyricInfoEncoder.encode(
                    track.title, track.artist, track.album, track.mediaId, request.generation, renderedLines, it
                )
            }
            val payload = Payload(compatible, overlay, JSONObject(compatible).optString("translationLyric").isNotBlank())
            val source = synchronized(this) {
                val state = active?.takeIf { it.request === request } ?: return null
                UUID.randomUUID().toString().also {
                    state.source = it
                    state.original = payload
                    state.payload = payload
                    state.translation = null
                    state.revision = 0
                }
            }
            mainHandler.postDelayed({ publishOnMain(request, source) }, ORIGINAL_FALLBACK_DELAY_MS)
            source
        }.onFailure(::logFailure).getOrNull()

    @Synchronized
    private fun applyWriteback(source: String, translation: String?, revision: Long) {
        val state = active?.takeIf { it.source == source } ?: return
        val original = state.original ?: return
        if (Constants.NEUTRON_PLAYER_PACKAGE !in RuntimeBridge.settings.players ||
            revision <= state.revision ||
            state.request.session.controller.metadata?.let(Track::from) != state.request.track) return
        state.revision = revision
        if (translation != null) {
            if (state.translation == translation) return
            state.payload = Payload(
                LyricInfoEncoder.withTranslation(original.compatible, translation),
                original.overlay?.let { LyricInfoEncoder.withTranslation(it, translation) },
                translated = true
            )
            state.translation = translation
        }
        publishOnMain(state.request, source)
    }

    @Synchronized
    fun clear() {
        val request = active?.request ?: return
        active = null
        updateActionSession(null)
        mainHandler.post { clearOnMain(request) }
    }

    @Synchronized
    private fun publishOnMain(request: Request, source: String) {
        val state = active?.takeIf { it.request === request && it.source == source } ?: return
        val payload = state.payload ?: return
        if (Constants.NEUTRON_PLAYER_PACKAGE !in RuntimeBridge.settings.players) return
        runCatching {
            val metadata = request.session.controller.metadata ?: return
            if (Track.from(metadata) != request.track) return
            val updated = withPayload(metadata, payload)
            if (updated !== metadata) {
                write(request.session, updated)
                val appliedMetadata = request.session.controller.metadata
                onMetadataPublished(request.session, appliedMetadata)
                val applied = appliedMetadata?.getString(KEY)
                val format = when (applied) {
                    null -> "missing"
                    payload.overlay -> "TTML+LRC"
                    payload.compatible -> "LRC"
                    else -> "missing"
                }
                val writeback = state.translation != null && applied != null &&
                    (applied == payload.overlay || applied == payload.compatible)
                log(Log.INFO, "Neutron lyricInfo published: generation=${request.generation}, format=$format, writeback=$writeback")
            } else {
                onMetadataPublished(request.session, metadata)
                if (metadata.getString(KEY).isNullOrBlank()) {
                    log(Log.WARN, "Neutron lyricInfo skipped: metadata exceeds parcel budget")
                }
            }
        }.onFailure { logFailure(it) }
    }

    @Synchronized
    private fun clearOnMain(request: Request) {
        if (active != null || Constants.NEUTRON_PLAYER_PACKAGE in RuntimeBridge.settings.players) return
        runCatching {
            val metadata = request.session.controller.metadata ?: return
            if (Track.from(metadata) != request.track) return
            val updated = withLyrics(metadata, null)
            if (updated !== metadata) write(request.session, updated)
        }.onFailure { logFailure(it) }
    }

    private fun withPayload(metadata: MediaMetadata, payload: Payload?): MediaMetadata {
        if (payload == null) return withLyrics(metadata, null)
        if (payload.overlay != null && metadata.getString(KEY) == payload.overlay) return metadata
        val withOverlay = payload.overlay?.let { withLyrics(metadata, it) }
        return withOverlay?.takeIf { it !== metadata } ?: withLyrics(metadata, payload.compatible)
    }

    private fun withLyrics(metadata: MediaMetadata, payload: String?): MediaMetadata {
        val existing = metadata.getString(KEY)
        if (existing == payload || (existing.isNullOrBlank() && payload == null)) return metadata
        if (!existing.isNullOrBlank() && !owns(existing)) return metadata

        // Copy the host fields, including unknown keys, artwork and the original bitmap size limit.
        val updated = MediaMetadata.Builder(metadata).putString(KEY, payload).build()
        if (payload == null) return updated
        val parcel = Parcel.obtain()
        return try {
            updated.writeToParcel(parcel, 0)
            if (parcel.dataSize() <= MAX_METADATA_BYTES) updated else metadata
        } finally {
            parcel.recycle()
        }
    }

    private fun owns(value: String): Boolean = runCatching {
        JSONObject(value).optString("source") == LyricInfoEncoder.SOURCE
    }.getOrDefault(false)

    private fun write(session: MediaSession, metadata: MediaMetadata) {
        writingMetadata.set(true)
        try {
            session.setMetadata(metadata)
        } finally {
            writingMetadata.remove()
        }
    }

    fun logFailure(error: Throwable) {
        log(Log.WARN, "Neutron lyricInfo update skipped: ${error.javaClass.simpleName}")
    }

    private fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }

    private data class Payload(val compatible: String, val overlay: String?, val translated: Boolean)
    private class Active(val request: Request) {
        var source: String? = null
        var original: Payload? = null
        var payload: Payload? = null
        var translation: String? = null
        var revision = 0L
    }

    class Request internal constructor(
        val session: MediaSession,
        val track: Track,
        val generation: Long
    )

    data class Track(val mediaId: String?, val title: String, val artist: String?, val album: String?) {
        companion object {
            fun from(metadata: MediaMetadata): Track? {
                val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: return null
                return Track(
                    metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                    title,
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
                )
            }
        }
    }
}
