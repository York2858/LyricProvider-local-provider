package io.github.proify.lyricon.localprovider.xposed

import io.github.proify.lyricon.localprovider.data.EmbeddedCatalog
import io.github.proify.lyricon.localprovider.search.SearchCandidate
import org.junit.Assert.*
import org.junit.Test

class EmbeddedCatalogTest {
    @Test fun musicTagsSupplySearchableIdentityAndEmbeddedSource() {
        val file = EmbeddedCatalog.parse("file:///Music/a/b/c/d/e/f/01.flac", "01.flac", "f", 12,
            mapOf("TITLE" to arrayOf("真正的歌名"), "ARTIST" to arrayOf("真正的歌手"), "ALBUM" to arrayOf("专辑"),
                "LYRICS" to arrayOf("[00:01.000]第一句\n[00:03.000]第二句")))!!
        assertEquals("01.flac", file.filename)
        assertTrue(file.embedded)
        assertEquals("真正的歌名", file.song.name)
        assertEquals("真正的歌手", file.song.artist)
        val result = SearchCandidate.local(file)
        assertEquals("Embedded", result.source)
        assertEquals("专辑", result.album)
        assertEquals(file.uri, result.localUri)
        assertEquals(2, result.song.lyrics!!.size)
    }
    @Test fun fallsBackToLyricMetadataAndKeepsEnhancedTiming() {
        val file = EmbeddedCatalog.parse("test", "track.mp3", "Music", 1,
            mapOf("lyrics1" to arrayOf("[ti:标题]\n[ar:歌手]\n[00:01.000]<00:01.000>逐<00:02.000>字<00:03.000>")))!!
        assertEquals("标题", file.song.name)
        assertEquals("歌手", file.song.artist)
        assertTrue(file.wordLevel)
    }
    @Test fun missingOrInvalidLyricsDoNotCreateAFalseSearchResult() {
        assertNull(EmbeddedCatalog.parse("test", "song.flac", "Music", 1, mapOf("TITLE" to arrayOf("标题"))))
        assertNull(EmbeddedCatalog.parse("test", "song.mp3", "Music", 1, mapOf("LYRICS" to arrayOf(""))))
    }
    @Test fun unsynchronizedEmbeddedLyricsRemainSeparateReadableLines() {
        val file = EmbeddedCatalog.parse("test", "song.mp3", "Music", 1,
            mapOf("TITLE" to arrayOf("歌名"), "USLT" to arrayOf("第一行\n\n第二行")))!!
        assertEquals(listOf("第一行", "第二行"), file.song.lyrics!!.map { it.text })
        assertEquals(0L, file.song.duration)
        assertFalse(file.wordLevel)
    }
    @Test fun brokenTimingIsNotShownAsPlainLyricsAndWordTimingWinsOverPlainText() {
        assertNull(EmbeddedCatalog.parse("test", "song.mp3", "Music", 1,
            mapOf("LYRICS" to arrayOf("[00:00.000] <00:00.000>v1:<00:00.000><00:00.000>"))))
        val file = EmbeddedCatalog.parse("test", "song.flac", "Music", 1,
            mapOf("USLT" to arrayOf("未同步文本"), "LYRICS" to arrayOf("[00:01.000]<00:01.000>逐<00:02.000>字<00:03.000>")))!!
        assertTrue(file.wordLevel)
    }
}
