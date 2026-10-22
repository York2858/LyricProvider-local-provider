package io.github.proify.lyricon.localprovider.search

import io.github.proify.cloudlyric.provider.lrclib.LrcLibProvider
import io.github.proify.cloudlyric.provider.netease.NetEaseProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Collections

/** Explicit opt-in only; normal unit tests never contact lyric services. */
class OnlineSearchLiveTest {
    @Test fun appleCatalogLoadsDistinctPagesBeforeAnyLyricDownload() = runBlocking {
        assumeTrue(System.getenv("LOCAL_LYRICS_LIVE") == "1")
        val provider = io.github.proify.cloudlyric.provider.apple.AppleMusicProvider(
            io.github.proify.cloudlyric.provider.apple.AppleMusicConfig())
        val first = provider.searchCatalog("Yellow Coldplay", 2)
        assertEquals(2, first.tracks.size)
        assertNotNull(first.nextCursor)
        val second = provider.searchCatalog("Yellow Coldplay", 2, first.nextCursor)
        assertEquals(2, second.tracks.size)
        assertTrue(first.tracks.map { it.key }.intersect(second.tracks.map { it.key }.toSet()).isEmpty())
        assertTrue((first.tracks + second.tracks).all { it.metadata.rich.isEmpty() && !it.metadata.trackName.isNullOrBlank() })
        println("LIVE paging source=AppleMusic first=2 second=2 distinct=true lyricsDownloaded=0")
    }
    @Test fun netEaseResultsExposeDownloadableAlbumArtwork() = runBlocking {
        assumeTrue(System.getenv("LOCAL_LYRICS_LIVE") == "1")
        val results = NetEaseProvider().searchCatalog(query = "Yellow Coldplay", pageSize = 1).tracks.map { it.metadata }
        assertTrue("NetEase returned no lyric candidates", results.isNotEmpty())
        val cover = results.first().artworkUrl
        assertTrue("NetEase artwork missing", cover.startsWith("https://"))
        val connection = java.net.URI.create(cover).toURL().openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 10000; connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        try {
            assertEquals(200, connection.responseCode)
            assertTrue(connection.contentType.orEmpty().startsWith("image/"))
            val bytes = connection.inputStream.use { io.github.proify.lyricon.localprovider.artwork.ArtworkBytes.read(it) }
            assertTrue("Cover is empty", bytes.size > 100)
            println("LIVE artwork source=NetEase bytes=${bytes.size} type=${connection.contentType}")
        } finally { connection.disconnect() }
    }
    @Test fun publicSearchReturnsRealTimedCandidates() = runBlocking {
        assumeTrue(System.getenv("LOCAL_LYRICS_LIVE") == "1")
        val batches = Collections.synchronizedList(mutableListOf<SearchBatch>())
        val session = OnlineSearch()
        try {
        session.search(listOf(LrcLibProvider(), NetEaseProvider()), "Yellow Coldplay", 2) { batches += it }
        batches.forEach { println("LIVE source=${it.provider} candidates=${it.results.size} error=${it.error.orEmpty()}") }
        val results = batches.flatMap { it.results }
        assertTrue("No public source returned a playable lyric; check service/network availability", results.isNotEmpty())
        results.forEach { assertFalse(it.song.name.isNullOrBlank()); assertTrue(it.song.lyrics.isNullOrEmpty()) }
        val lyrics = results.map { session.resolve(it) }
        assertTrue("No candidate returned timed lyrics", lyrics.any { it.song.lyrics.orEmpty().any { line -> line.end > line.begin } })
        } finally { session.close() }
    }
    @Test fun netEaseLoadsDistinctSecondPages() = runBlocking {
        assumeTrue(System.getenv("LOCAL_LYRICS_LIVE") == "1")
        verifyPaging(NetEaseProvider())
    }
    @Test fun lrcLibLoadsDistinctSecondPages() = runBlocking {
        assumeTrue(System.getenv("LOCAL_LYRICS_LIVE") == "1")
        verifyPaging(LrcLibProvider())
    }
    private suspend fun verifyPaging(source: io.github.proify.cloudlyric.LyricsProvider) {
        val session = OnlineSearch()
        try {
            val first = mutableListOf<SearchCandidate>(); val second = mutableListOf<SearchCandidate>()
            session.search(listOf(source), "Yellow", 2) { assertNull(it.error); first += it.results }
            assertEquals(2, first.size)
            session.search(listOf(source), "Yellow", 2, more = true) { assertNull(it.error); second += it.results }
            assertEquals(2, second.size)
            assertTrue(first.map { it.id }.intersect(second.map { it.id }.toSet()).isEmpty())
            println("LIVE paging source=${source.id} first=${first.size} second=${second.size} distinct=true")
        } finally { session.close() }
    }
}
