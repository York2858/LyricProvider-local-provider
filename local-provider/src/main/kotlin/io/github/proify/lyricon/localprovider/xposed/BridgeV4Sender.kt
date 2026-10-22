package io.github.proify.lyricon.localprovider.xposed

import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import java.util.Locale

/** Sends local-provider captures to the Bridge without replacing the Lyricon Binder path. */
internal object BridgeV4Sender {
    private const val ACTION =
        "io.github.andrealtb.lockscreenlyrics.action.EXTERNAL_LYRIC_DIRECT_V4"
    private const val SYSTEM_UI = "com.android.systemui"
    private const val PROTOCOL_VERSION = 4
    private const val EVENT_TRACK_CHANGED = "trackChanged"
    private const val EVENT_LYRIC_READY = "lyricReady"
    @Volatile private var generation: Long = 0L

    fun sendTrack(context: Context, playerPackage: String, song: Song) {
        generation += 1L
        send(context, playerPackage, song, EVENT_TRACK_CHANGED, null, null)
    }

    fun sendLyrics(context: Context, playerPackage: String, song: Song) {
        val lines = song.lyrics.orEmpty().filter { !it.text.isNullOrBlank() || !it.words.isNullOrEmpty() }
        if (lines.isEmpty()) return
        send(
            context,
            playerPackage,
            song,
            EVENT_LYRIC_READY,
            toLrc(lines),
            toEnhancedLrc(lines),
            toTranslationLrc(lines)
        )
    }

    fun sendPlaybackState(context: Context, playerPackage: String, state: PlaybackState) {
        if (!isSupportedPlayer(playerPackage)) return
        val intent = baseIntent(context, playerPackage, EVENT_TRACK_CHANGED)
            .putExtra("playbackState", state.state)
            .putExtra("playbackPosition", state.position)
            .putExtra("playbackSpeed", state.playbackSpeed)
            .putExtra("playbackLastPositionUpdateTime", state.lastPositionUpdateTime)
        context.sendBroadcast(intent)
    }

    private fun send(
        context: Context,
        playerPackage: String,
        song: Song,
        eventType: String,
        lyric: String?,
        rawLyric: String?,
        translationLyric: String? = null
    ) {
        if (!isSupportedPlayer(playerPackage)) return
        val title = song.name.orEmpty()
        if (title.isBlank()) return
        val artist = song.artist.orEmpty()
        val intent = baseIntent(context, playerPackage, eventType)
            .putExtra("trackGeneration", generation)
            .putExtra("mediaId", song.id.orEmpty())
            .putExtra("songName", title)
            .putExtra("artist", artist)
            .putExtra("duration", song.duration)
            .putExtra("trackKey", trackKey(title, artist))
        if (!lyric.isNullOrBlank()) intent.putExtra("lyric", lyric)
        if (!rawLyric.isNullOrBlank()) intent.putExtra("rawLyric", rawLyric)
        if (!translationLyric.isNullOrBlank()) intent.putExtra("translationLyric", translationLyric)
        context.sendBroadcast(intent)
        android.util.Log.d("LocalProvider", "Bridge v4 sent event=$eventType source=${sourceFor(playerPackage)} player=$playerPackage title=$title rawChars=${rawLyric?.length ?: 0}")
    }

    private fun baseIntent(context: Context, playerPackage: String, eventType: String): Intent =
        Intent(ACTION).setPackage(SYSTEM_UI)
            .putExtra("protocolVersion", PROTOCOL_VERSION)
            .putExtra("source", sourceFor(playerPackage))
            .putExtra("playerPackage", playerPackage)
            .putExtra("senderPackage", playerPackage)
            .putExtra("senderKind", "provider")
            .putExtra("capabilities", "playbackState trackGeneration translationToggle")
            .putExtra("matchPolicy", "mediaId,trackKey,titleArtist")
            .putExtra("identityConfidence", "currentTrack")
            .putExtra("eventType", eventType)
            .putExtra("capturedAt", System.currentTimeMillis())

    private fun isSupportedPlayer(packageName: String): Boolean =
        packageName == Constants.YOS_PLAYER_PACKAGE || packageName == Constants.NEUTRON_PLAYER_PACKAGE

    private fun sourceFor(packageName: String): String =
        if (packageName == Constants.NEUTRON_PLAYER_PACKAGE) Constants.NEUTRON_BRIDGE_SOURCE
        else Constants.BRIDGE_SOURCE
    private fun trackKey(title: String, artist: String): String =
        title.trim().lowercase(Locale.ROOT) + "|" + artist.trim().lowercase(Locale.ROOT)

    private fun toLrc(lines: List<RichLyricLine>): String = lines.joinToString("\n") { line ->
        "[${formatTime(line.begin)}]${line.text.orEmpty().trim()}"
    }

    private fun toEnhancedLrc(lines: List<RichLyricLine>): String = lines.joinToString("\n") { line ->
        val words = line.words.orEmpty()
        if (words.isEmpty()) {
            "[${formatTime(line.begin)}]${line.text.orEmpty().trim()}"
        } else {
            "[${formatTime(line.begin)}]" + words.joinToString("") { word ->
                "<${formatTime(word.begin)}>${word.text.orEmpty()}"
            }
        }
    }

    private fun toTranslationLrc(lines: List<RichLyricLine>): String? {
        val translated = lines.mapNotNull { line ->
            val text = line.translation.orEmpty().trim()
            if (text.isBlank() || text == line.text.orEmpty().trim()) null
            else "[${formatTime(line.begin)}]$text"
        }
        return translated.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun formatTime(milliseconds: Long): String {
        val totalCentiseconds = (milliseconds.coerceAtLeast(0L) + 5L) / 10L
        val minutes = totalCentiseconds / 6000L
        val seconds = (totalCentiseconds % 6000L) / 100L
        val centiseconds = totalCentiseconds % 100L
        return "%02d:%02d.%02d".format(Locale.ROOT, minutes, seconds, centiseconds)
    }
}
