package io.github.proify.lyricon.localprovider.search

import io.github.proify.cloudlyric.*
import io.github.proify.lyricon.lyric.model.RichLyricLine
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class OnlineSearchTest {
    private fun lyric(album: String = "Album") = LyricsResult(trackName = "Song", artistName = "Singer",
        albumName = album, durationMs = 240000, rich = listOf(RichLyricLine(begin = 0, end = 1000,
            text = "原文", roma = "romaji", translation = "翻译")))
    private fun track(id: String) = CatalogTrack(id, lyric("Album $id").copy(rich = emptyList()))
    private class Fake(override val id: String = "source") : LyricsProvider, CatalogProvider {
        val calls = Collections.synchronizedList(mutableListOf<Pair<Int, String?>>())
        val loads = AtomicInteger()
        var page: suspend (Int, String?) -> CatalogPage = { _, _ -> CatalogPage(emptyList()) }
        var load: suspend (CatalogTrack) -> LyricsResult? = { null }
        override suspend fun searchCatalog(query: String, pageSize: Int, cursor: String?): CatalogPage {
            calls += pageSize to cursor; return page(pageSize, cursor)
        }
        override suspend fun loadLyrics(track: CatalogTrack): LyricsResult? { loads.incrementAndGet(); return load(track) }
        override suspend fun search(query: String?, trackName: String?, artistName: String?, albumName: String?, limit: Int): List<LyricsResult> =
            error("Catalog browsing must never invoke the legacy eager lyric search")
    }
    private suspend fun initial(session: OnlineSearch, source: Fake): List<SearchCandidate> {
        var results = emptyList<SearchCandidate>()
        session.search(listOf(source), "Song", 6) { results = it.results }
        return results
    }
    @Test fun metadataIsVisibleWithoutDownloadingAndWithoutDiscardingUnavailableLyrics() = runBlocking {
        val source = Fake().apply { page = { _, _ -> CatalogPage(listOf(track("1"), track("2"))) } }
        val session = OnlineSearch()
        try {
            val results = initial(session, source)
            assertEquals(2, results.size); assertEquals(0, source.loads.get())
            assertTrue(results.all { it.lyricState == LyricState.UNKNOWN && it.song.lyrics.isNullOrEmpty() })
            val missing = session.resolve(results.first())
            assertEquals(LyricState.NONE, missing.lyricState); assertEquals(results.first().id, missing.id)
            assertEquals(2, SearchCandidate.distinct(results + missing).size)
        } finally { session.close() }
    }
    @Test fun aFailedSourceDoesNotEraseSuccessfulSources() = runBlocking {
        val good = Fake("good").apply { page = { _, _ -> CatalogPage(listOf(track("1"))) } }
        val bad = Fake("bad").apply { page = { _, _ -> error("offline") } }
        val session = OnlineSearch()
        try {
            val batches = Collections.synchronizedList(mutableListOf<SearchBatch>())
            session.search(listOf(good, bad), "Song", 6) { batches += it }
            assertEquals(1, batches.single { it.provider == "good" }.results.size)
            assertNotNull(batches.single { it.provider == "bad" }.error)
        } finally { session.close() }
    }
    @Test fun serviceVerificationIsShownAsAnErrorRatherThanAnEmptyCatalog() = runBlocking {
        val source = Fake().apply { page = { _, _ -> throw CatalogAccessException("来源要求验证，请稍后重试") } }
        val session = OnlineSearch()
        try {
            session.search(listOf(source), "Song", 6) {
                assertEquals("来源要求验证，请稍后重试", it.error)
                assertTrue(it.results.isEmpty())
            }
            assertTrue(session.canLoadMore(listOf(source.id)))
        } finally { session.close() }
    }
    @Test fun nextPageUsesTheReturnedCursorAndFixedPageSizeAndStopsAtTheEnd() = runBlocking {
        val source = Fake().apply { page = { size, cursor ->
            val offset = cursor?.toInt() ?: 0
            CatalogPage((offset until offset + size).map { track(it.toString()) }, if (offset == 0) "6" else null)
        } }
        val session = OnlineSearch()
        try {
            val first = initial(session, source)
            var more = emptyList<SearchCandidate>()
            session.search(listOf(source), "Song", 6, more = true) { more = it.results }
            assertEquals(listOf(6 to null, 6 to "6"), source.calls.toList())
            assertEquals(12, SearchCandidate.distinct(first + more).size)
            assertFalse(session.canLoadMore(listOf(source.id)))
            session.search(listOf(source), "Song", 6, more = true) { assertTrue(it.results.isEmpty()) }
            assertEquals(2, source.calls.size)
        } finally { session.close() }
    }
    @Test fun failedNextPageCanBeRetriedWithoutSkippingOrClearingPreviousResults() = runBlocking {
        var fail = true
        val source = Fake().apply { page = { _, cursor ->
            if (cursor == null) CatalogPage(listOf(track("1")), "next")
            else if (fail) { fail = false; error("offline") } else CatalogPage(listOf(track("2")))
        } }
        val session = OnlineSearch()
        try {
            val all = initial(session, source).toMutableList()
            session.search(listOf(source), "Song", 6, more = true) { assertNotNull(it.error) }
            session.search(listOf(source), "Song", 6, more = true) { all += it.results }
            assertEquals(listOf(null, "next", "next"), source.calls.map { it.second })
            assertEquals(2, all.size)
        } finally { session.close() }
    }
    @Test fun duplicateServerPagesDoNotCreateAnEndlessLoadMoreLoop() = runBlocking {
        val source = Fake().apply { page = { _, cursor -> CatalogPage(listOf(track("1")), if (cursor == null) "2" else "3") } }
        val session = OnlineSearch()
        try {
            initial(session, source)
            session.search(listOf(source), "Song", 6, more = true) { assertTrue(it.results.isEmpty()) }
            assertFalse(session.canLoadMore(listOf(source.id)))
        } finally { session.close() }
    }
    @Test fun simultaneousPreviewRequestsShareOneDownloadAndPreserveThreeLines() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val source = Fake().apply {
            page = { _, _ -> CatalogPage(listOf(track("1"))) }
            load = { gate.await(); lyric() }
        }
        val session = OnlineSearch()
        try {
            val row = initial(session, source).single()
            val a = async { session.resolve(row) }; val b = async { session.resolve(row) }
            gate.complete(Unit)
            val result = a.await(); assertEquals(result, b.await()); assertEquals(1, source.loads.get())
            assertEquals(row.id, result.id); assertEquals(LyricState.READY, result.lyricState)
            val line = result.song.lyrics!!.single()
            assertEquals("原文", line.text); assertEquals("romaji", line.roma); assertEquals("翻译", line.translation)
        } finally { session.close() }
    }
    @Test fun platformVersionsKeepTheirOwnDownloadIdentityAndAreNotFetchedWithTheMainSong() = runBlocking {
        val keys = Collections.synchronizedList(mutableListOf<String>())
        val source = Fake().apply {
            page = { _, _ -> CatalogPage(listOf(track("root").copy(versions = listOf(track("version"))))) }
            load = { entry -> keys += entry.key; lyric() }
        }
        val session = OnlineSearch()
        try {
            val parent = initial(session, source).single()
            val child = parent.versions.single()
            assertEquals("version", child.remote!!.key)
            val loaded = session.resolve(parent)
            assertEquals(listOf("root"), keys.toList())
            assertEquals(LyricState.UNKNOWN, loaded.versions.single().lyricState)
            val version = session.resolve(child)
            assertEquals(listOf("root", "version"), keys.toList())
            assertEquals(LyricState.READY, version.lyricState)
            assertEquals(child.id, version.id)
        } finally { session.close() }
    }
    @Test fun lyricFailureKeepsMetadataAndCanBeExplicitlyRetried() = runBlocking {
        val source = Fake().apply { page = { _, _ -> CatalogPage(listOf(track("1"))) }; load = { error("offline") } }
        val session = OnlineSearch()
        try {
            val row = initial(session, source).single()
            val failed = session.resolve(row)
            assertEquals(LyricState.ERROR, failed.lyricState); assertEquals(row.song, failed.song)
            source.load = { lyric() }
            val ready = session.resolve(failed, retry = true)
            assertEquals(LyricState.READY, ready.lyricState); assertEquals(2, source.loads.get())
        } finally { session.close() }
    }
    @Test fun backgroundDetectionHasAtMostThreeConcurrentDownloads() = runBlocking {
        val active = AtomicInteger(); val maximum = AtomicInteger(); val completed = AtomicInteger()
        val done = CompletableDeferred<Unit>()
        val source = Fake().apply {
            page = { _, _ -> CatalogPage((1..12).map { track(it.toString()) }) }
            load = { active.incrementAndGet().also { now -> maximum.updateAndGet { maxOf(it, now) } }; delay(20); active.decrementAndGet(); lyric() }
        }
        val session = OnlineSearch()
        try {
            session.detect(initial(session, source)) { if (it.lyricState == LyricState.READY && completed.incrementAndGet() == 12) done.complete(Unit) }
            withTimeout(5000) { done.await() }
            assertTrue(maximum.get() <= 3); assertEquals(12, source.loads.get())
        } finally { session.close() }
    }
    @Test fun pausingDetectionCancelsUnneededRequestsAndPreservesPagination() = runBlocking {
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val source = Fake().apply {
            page = { _, _ -> CatalogPage(listOf(track("1")), "next") }
            load = { started.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        }
        val session = OnlineSearch()
        try {
            val row = initial(session, source).single()
            session.detect(listOf(row)) { }
            withTimeout(3000) { started.await() }
            session.pauseDetection()
            withTimeout(3000) { cancelled.await() }
            assertTrue(session.canLoadMore(listOf(source.id)))
            source.load = { lyric() }
            session.resumeDetection()
            assertEquals(LyricState.READY, session.resolve(row).lyricState)
            assertEquals(2, source.loads.get())
        } finally { session.close() }
    }
    @Test fun stoppingAutomaticDetectionDoesNotCancelAnExplicitSharedPreview() = runBlocking {
        val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val source = Fake().apply {
            page = { _, _ -> CatalogPage(listOf(track("1"))) }
            load = { started.complete(Unit); gate.await(); lyric() }
        }
        val session = OnlineSearch()
        try {
            val row = initial(session, source).single()
            val explicit = async(start = CoroutineStart.UNDISPATCHED) { session.resolve(row) }
            session.detect(listOf(row)) { }
            withTimeout(3000) { started.await() }
            session.pauseDetection()
            gate.complete(Unit)
            assertEquals(LyricState.READY, explicit.await().lyricState)
            assertEquals(1, source.loads.get())
        } finally { session.close() }
    }
    @Test fun leavingAnExplicitPreviewCancelsItsUnsharedDownload() = runBlocking {
        val started = CompletableDeferred<Unit>(); val cancelled = CompletableDeferred<Unit>()
        val source = Fake().apply {
            page = { _, _ -> CatalogPage(listOf(track("1"))) }
            load = { started.complete(Unit); try { awaitCancellation() } finally { cancelled.complete(Unit) } }
        }
        val session = OnlineSearch()
        try {
            val row = initial(session, source).single()
            val job = launch { session.resolve(row) }
            withTimeout(3000) { started.await() }
            job.cancelAndJoin()
            withTimeout(3000) { cancelled.await() }
        } finally { session.close() }
    }
    @Test fun queryChangeResetsSourceCursors() = runBlocking {
        val source = Fake().apply { page = { _, _ -> CatalogPage(listOf(track("1")), "next") } }
        val session = OnlineSearch()
        try {
            initial(session, source)
            session.search(listOf(source), "Another", 6, more = true) { assertEquals(1, it.results.size) }
            assertEquals(listOf(null, null), source.calls.map { it.second })
        } finally { session.close() }
    }
    @Test fun canceledDeliveryDoesNotSkipTheUndeliveredPage() = runBlocking {
        val source = Fake().apply { page = { _, _ -> CatalogPage(listOf(track("1")), "next") } }
        val session = OnlineSearch()
        try {
            try { session.search(listOf(source), "Song", 6) { throw CancellationException("stopped") } }
            catch (_: CancellationException) { }
            session.search(listOf(source), "Song", 6, more = true) { assertEquals(1, it.results.size) }
            assertEquals(listOf(null, null), source.calls.map { it.second })
        } finally { session.close() }
    }
    @Test fun matchScoreStillRanksUnavailableAndUncheckedSongsNormally() {
        val reference = MatchTarget("Song", "Singer", "Album", 240000)
        val exact = SearchCandidate.catalog("one", CatalogTrack("1", lyric().copy(rich = emptyList())))
        val other = SearchCandidate.online("two", lyric().copy(trackName = "Other", artistName = "Other", durationMs = 120000))
        val ranked = listOf(other, exact.copy(lyricState = LyricState.ERROR)).sortedWith(SearchCandidate.ranking(reference, MatchRules()))
        assertEquals(exact.id, ranked.first().id)
    }
    @Test fun plainLyricsKeepSeparateLinesInsteadOfMergingThemAsTranslationRows() {
        val lines = listOf("first line", "second line", "third line")
        val result = SearchCandidate.online("LrcLib", lyric().copy(rich = lines.map { RichLyricLine(text = it) }))
        assertEquals(lines, result.song.lyrics!!.map { it.text })
        assertTrue(result.song.lyrics!!.all { it.translation.isNullOrBlank() && it.roma.isNullOrBlank() })
    }
    @Test fun duplicateDownloadsCollapseButSourceAndTimingVariantsRemain() {
        val a = SearchCandidate.online("one", lyric())
        assertEquals(1, SearchCandidate.distinct(listOf(a, a)).size)
        assertNotEquals(a.id, SearchCandidate.online("two", lyric()).id)
        assertNotEquals(a.id, SearchCandidate.online("one", lyric().copy(rich = listOf(lyric().rich[0].copy(begin = 100)))).id)
        assertEquals("saved:${a.id}", SearchCandidate.distinct(listOf(a, a.copy(localUri = "saved:${a.id}"))).single().localUri)
    }
}
