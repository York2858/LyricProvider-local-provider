package io.github.proify.lyricon.localprovider.search

import io.github.proify.lyricon.localprovider.lyrics.LyricTiming
import io.github.proify.cloudlyric.LyricsResult
import io.github.proify.cloudlyric.CatalogTrack
import io.github.proify.lyricon.localprovider.data.LyricFile
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

enum class LyricState { UNKNOWN, CHECKING, READY, NONE, ERROR }

data class SearchCandidate(val id: String, val source: String, val song: Song,
                           val album: String = "", val localUri: String? = null, val artworkUrl: String = "",
                           val remote: CatalogTrack? = null, val lyricState: LyricState = LyricState.READY,
                           val versions: List<SearchCandidate> = emptyList()) {
    val target get() = MatchTarget(song.name, song.artist, album, song.duration)
    val wordLevel get() = LyricTiming.hasWordTiming(song.lyrics.orEmpty())
    val roma get() = song.lyrics.orEmpty().any { !it.roma.isNullOrBlank() }
    val translation get() = song.lyrics.orEmpty().any { !it.translation.isNullOrBlank() }

    companion object {
        fun ranking(reference: MatchTarget, rules: MatchRules): Comparator<SearchCandidate> =
            compareByDescending<SearchCandidate> { MatchScorer.evaluate(reference, it.target, rules).total }
                .thenByDescending { it.wordLevel }.thenBy { it.source }.thenBy { it.album }
        fun catalog(source: String, track: CatalogTrack): SearchCandidate = SearchCandidate(
            digest("catalog:$source:${track.key}"), source,
            Song(name = track.metadata.trackName, artist = track.metadata.artistName, duration = track.metadata.durationMs),
            track.metadata.albumName.orEmpty(), artworkUrl = track.metadata.artworkUrl,
            remote = track, lyricState = LyricState.UNKNOWN,
            versions = track.versions.filter { it.key != track.key }.distinctBy { it.key }
                .map { catalog(source, it.copy(versions = emptyList())) })
        fun online(source: String, result: LyricsResult): SearchCandidate {
            val untimed = result.rich.all { it.begin == 0L && it.end == 0L && it.duration == 0L && it.words.isNullOrEmpty() }
            val normalized = if (untimed) result.rich.map { it.copy() }
                else io.github.proify.lyricon.localprovider.lyrics.LyricNormalizer.normalizeForTripleLine(result.rich)
            val canonical = result.copy(rich = normalized)
            val id = digest(source + Json.encodeToString(canonical.copy(artworkUrl = "")))
            return SearchCandidate(id, source, Song(name = result.trackName, artist = result.artistName,
                duration = result.durationMs, lyrics = normalized), result.albumName.orEmpty(), artworkUrl = result.artworkUrl)
        }
        fun local(file: LyricFile) = SearchCandidate(digest(file.uri), if (file.embedded) "Embedded" else "本地", file.song,
            album = file.album, localUri = file.uri)
        fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
        fun distinct(items: List<SearchCandidate>) = items.groupBy { it.id }.values.map { versions ->
            val chosen = versions.firstOrNull { it.localUri != null } ?: versions.lastOrNull { it.lyricState == LyricState.READY } ?: versions.last()
            chosen.copy(versions = versions.lastOrNull { it.versions.isNotEmpty() }?.versions ?: chosen.versions)
        }
    }
}
