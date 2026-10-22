package io.github.proify.lyricon.localprovider

import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.cloudlyric.LyricsResult
import io.github.proify.lyricon.localprovider.data.SavedLyrics
import io.github.proify.lyricon.localprovider.search.SearchCandidate
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import org.junit.Assert.*
import org.junit.Test

class SavedLyricsTest {
    @Test fun selectionPersistsAllThreeTracksAndWordTimingsAcrossStoreInstances() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = LyricsResult(trackName = "本地歌词持久化测试", artistName = "Test", albumName = "Test Album", durationMs = 3000,
            artworkUrl = "https://cover.example/test.jpg",
            rich = listOf(RichLyricLine(begin = 1000, end = 3000, text = "原文", roma = "romaji", translation = "翻译",
                words = listOf(LyricWord(begin = 1000, end = 3000, text = "原文")),
                translationWords = listOf(LyricWord(begin = 1000, end = 3000, text = "翻译")))))
        val candidate = SearchCandidate.online("test", source)
        val uri = SavedLyrics(context).save(candidate)
        try {
            val restored = SavedLyrics(context).get(uri)!!
            assertEquals(candidate.song, restored.song)
            assertEquals("Test Album", restored.album)
            assertEquals(source.artworkUrl, restored.artworkUrl)
            assertEquals("romaji", restored.song.lyrics!!.single().roma)
            assertEquals("翻译", restored.song.lyrics!!.single().translation)
            assertEquals(1000, restored.song.lyrics!!.single().words!!.single().begin)
            assertNull(SavedLyrics(context).get("saved:../../anything"))
        } finally {
            java.io.File(context.filesDir, "selected-lyrics/${candidate.id}.json").delete()
        }
    }
}
