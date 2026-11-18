package io.github.proify.lyricon.localprovider.xposed

import android.media.MediaMetadata
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.util.Log
import io.github.proify.lyricon.localprovider.data.RuntimeBridge
import io.github.proify.lyricon.localprovider.lyrics.LyricInfoEncoder
import io.github.proify.lyricon.localprovider.lyrics.LocalTtml
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.json.JSONObject

/** Publishes on Neutron's own session; Lyricon remains an independent consumer. */
internal object NeutronLyricPublisher {
    private const val TAG = "LocalProvider"
    private const val KEY = "lyricInfo"
    private const val MAX_METADATA_BYTES = 512 * 1024
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val writingMetadata = ThreadLocal<Boolean>()
    private var active: Active? = null
    private var generation = 0L

    fun isWritingMetadata(): Boolean = writingMetadata.get() == true

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

    fun publish(request: Request, lines: List<RichLyricLine>, ttml: String?) {
        runCatching {
            val track = request.track
            val ttmlLines = ttml?.let(LocalTtml::bridgeLines).orEmpty()
            val renderedLines = ttmlLines.ifEmpty { lines }
            val compatible = LyricInfoEncoder.encode(
                track.title, track.artist, track.album, track.mediaId, request.generation, renderedLines
            ) ?: return
            val overlay = ttml?.takeIf { ttmlLines.isNotEmpty() }?.let {
                LyricInfoEncoder.encode(
                    track.title, track.artist, track.album, track.mediaId, request.generation, renderedLines, it
                )
            }
            mainHandler.post { publishOnMain(request, Payload(compatible, overlay)) }
        }.onFailure { logFailure(it) }
    }

    @Synchronized
    fun clear() {
        val request = active?.request ?: return
        active = null
        mainHandler.post { clearOnMain(request) }
    }

    @Synchronized
    private fun publishOnMain(request: Request, payload: Payload) {
        val state = active?.takeIf { it.request === request } ?: return
        if (Constants.NEUTRON_PLAYER_PACKAGE !in RuntimeBridge.settings.players) return
        runCatching {
            val metadata = request.session.controller.metadata ?: return
            if (Track.from(metadata) != request.track) return
            state.payload = payload
            val updated = withPayload(metadata, payload)
            if (updated !== metadata) {
                write(request.session, updated)
                val applied = request.session.controller.metadata?.getString(KEY)
                val format = when (applied) {
                    null -> "missing"
                    payload.overlay -> "TTML+LRC"
                    payload.compatible -> "LRC"
                    else -> "missing"
                }
                log(Log.INFO, "Neutron lyricInfo published: generation=${request.generation}, format=$format")
            } else if (metadata.getString(KEY).isNullOrBlank()) {
                log(Log.WARN, "Neutron lyricInfo skipped: metadata exceeds parcel budget")
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

    private data class Payload(val compatible: String, val overlay: String?)
    private class Active(val request: Request, var payload: Payload? = null)

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
