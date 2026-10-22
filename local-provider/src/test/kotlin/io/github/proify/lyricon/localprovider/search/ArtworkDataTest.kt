package io.github.proify.lyricon.localprovider.search

import io.github.proify.cloudlyric.CoverUrls
import io.github.proify.cloudlyric.LyricsResult
import io.github.proify.lyricon.localprovider.artwork.ArtworkBytes
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ArtworkDataTest {
    @Test fun normalizesSupportedCoverUrlsAndProviderTemplates() {
        assertEquals("https://cover/a300x300.jpg", CoverUrls.normalize("http://cover/a{w}x{h}.jpg"))
        assertEquals("https://cover/300x300.jpg", CoverUrls.normalize("https://cover/{w}x{h}.{f}"))
        assertEquals("https://cover/a.jpg", CoverUrls.normalize("//cover/a.jpg"))
        assertEquals("", CoverUrls.normalize("content://private/art"))
        assertTrue(CoverUrls.qq("Abc123").endsWith("M000Abc123.jpg"))
        assertEquals("", CoverUrls.qq("../../bad"))
        assertEquals("https://cover/path~c5_300x300.jpg", CoverUrls.soda("https://cover/", "path"))
    }
    @Test fun changingCoverNeverChangesLyricIdentity() {
        val result = LyricsResult(trackName = "Title", artistName = "Artist", rich = listOf(RichLyricLine(begin = 0, end = 1000, text = "歌词")))
        val old = SearchCandidate.online("NetEase", result)
        val withArt = SearchCandidate.online("NetEase", result.copy(artworkUrl = "https://cover/a.jpg"))
        assertEquals(old.id, withArt.id)
        assertEquals("https://cover/a.jpg", withArt.artworkUrl)
        assertEquals(1, SearchCandidate.distinct(listOf(old, withArt)).size)
    }
    @Test fun limitsImagesEvenWhenServerOmitsContentLength() {
        assertArrayEquals(byteArrayOf(1, 2), ArtworkBytes.read(ByteArrayInputStream(byteArrayOf(1, 2)), 2))
        try { ArtworkBytes.read(ByteArrayInputStream(ByteArray(9000)), 8999); fail("oversized stream accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
