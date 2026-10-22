package io.github.proify.lyricon.localprovider.search

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.junit.Assert.*
import org.junit.Test

class PlaybackIdentityTest {
    @Test fun selectingAVersionKeepsThePlayersTrackKeyAndActualDuration() {
        val song = Song(name = "Title", artist = "Artist Alias", duration = 210000,
            lyrics = listOf(RichLyricLine(begin = 0, end = 210000, text = "原文", roma = "romaji", translation = "翻译")))
        val playback = MatchTarget("Title (中文别名)", "Artist", "Album", 240000)
        val bound = PlaybackIdentity.bind(song, playback)
        assertEquals(playback.title, bound.name); assertEquals(playback.artist, bound.artist)
        assertEquals(240000, bound.duration)
        assertEquals(song.lyrics, bound.lyrics)
    }
}
