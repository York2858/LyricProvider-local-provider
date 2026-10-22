package io.github.proify.lyricon.localprovider.search

import io.github.proify.lyricon.lyric.model.Song

/** A selected lyric version must not rename the playing track or replace its real duration. */
object PlaybackIdentity {
    fun bind(song: Song, reference: MatchTarget): Song = if (reference.title.isNullOrBlank()) song else song.copy(
        name = reference.title, artist = reference.artist,
        duration = reference.durationMs.takeIf { it > 0 } ?: song.duration
    )
}
