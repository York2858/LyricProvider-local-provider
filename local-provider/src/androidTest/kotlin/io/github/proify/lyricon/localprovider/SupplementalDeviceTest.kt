package io.github.proify.lyricon.localprovider

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadata
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.cloudlyric.*
import io.github.proify.extensions.net.awaitText
import io.github.proify.lyricon.localprovider.artwork.*
import io.github.proify.lyricon.localprovider.data.*
import io.github.proify.lyricon.localprovider.search.*
import io.github.proify.lyricon.lyric.model.RichLyricLine
import kotlinx.coroutines.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Physical Android checks for the parts that were previously exercised only on the JVM. */
class SupplementalDeviceTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private fun report(message: String) {
        Log.i("SupplementalCheck", message)
        instrument.sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
    }
    private suspend fun until(check: () -> Boolean) = withTimeout(6000) { while (!check()) delay(25) }
    private fun shell(command: String) { instrument.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }

    @Test fun cancellationClosesRealAndroidSocketDuringBodyRead() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val received = CompletableDeferred<Unit>(); val disconnected = CompletableDeferred<Unit>()
        val client = OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).dns(Dns { listOf(InetAddress.getByName("127.0.0.1")) }).build()
        val serving = launch(Dispatchers.IO) {
            try { server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream()
                var tail = ""
                while (!tail.endsWith("\r\n\r\n")) { val c = input.read(); check(c >= 0); tail = (tail + c.toChar()).takeLast(4) }
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 100000\r\n\r\nx".toByteArray()); flush() }
                received.complete(Unit)
                if (input.read() == -1) disconnected.complete(Unit)
            } } catch (e: java.net.SocketException) { if (!server.isClosed) throw e }
        }
        try {
            // This exact host is already allowed by the app's cleartext policy; DNS is test-local.
            val call = client.newCall(Request.Builder().url("http://lyrics.kugou.com:${server.localPort}/cancel-fixture").build())
            val request = launch(Dispatchers.IO) {
                try { call.awaitText() }
                catch (e: java.io.IOException) { received.completeExceptionally(e); throw e }
            }
            withTimeout(5000) { received.await() }
            delay(100)
            val started = SystemClock.elapsedRealtime()
            request.cancelAndJoin()
            withTimeout(2500) { disconnected.await() }
            assertTrue(call.isCanceled())
            report("PASS socket cancellation: peer EOF after ${SystemClock.elapsedRealtime() - started}ms while response body incomplete")
        } finally {
            server.close(); serving.cancelAndJoin(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    private class Fake : LyricsProvider, CatalogProvider {
        override val id = "device-fixture"
        val active = AtomicInteger(); val peak = AtomicInteger(); val loads = AtomicInteger(); val cancelled = AtomicInteger()
        var gate = CompletableDeferred<Unit>()
        val cursors = Collections.synchronizedList(mutableListOf<String?>())
        override suspend fun searchCatalog(query: String, pageSize: Int, cursor: String?): CatalogPage {
            cursors += cursor
            val start = if (cursor == null) 0 else 10
            return CatalogPage((start until start + 10).map { CatalogTrack(it.toString(), LyricsResult(trackName = "Fixture $it")) }, if (cursor == null) "10" else null)
        }
        override suspend fun loadLyrics(track: CatalogTrack): LyricsResult {
            loads.incrementAndGet(); val now = active.incrementAndGet(); peak.updateAndGet { maxOf(it, now) }
            try { gate.await(); return track.metadata.copy(rich = listOf(RichLyricLine(begin = 0, end = 1000, text = "test", roma = "roma", translation = "translation"))) }
            catch (e: CancellationException) { cancelled.incrementAndGet(); throw e }
            finally { active.decrementAndGet() }
        }
        override suspend fun search(query: String?, trackName: String?, artistName: String?, albumName: String?, limit: Int): List<LyricsResult> = error("Must use catalog")
    }

    @Test fun detectionStopsQueuedDownloadsAndRetainsNextPageOnAndroid() = runBlocking {
        val source = Fake(); val session = OnlineSearch()
        try {
            var rows = emptyList<SearchCandidate>()
            session.search(listOf(source), "Fixture", 10) { rows = it.results }
            assertEquals(0, source.loads.get()); assertEquals(10, rows.size)
            session.detect(rows) {}
            until { source.active.get() == 3 }
            session.pauseDetection(); until { source.active.get() == 0 }
            delay(150)
            assertEquals(3, source.loads.get()); assertEquals(3, source.cancelled.get()); assertEquals(3, source.peak.get())
            session.detect(rows) {}; delay(100); assertEquals(3, source.loads.get())
            session.search(listOf(source), "Fixture", 10, more = true) { assertEquals(10, it.results.size) }
            assertEquals(listOf(null, "10"), source.cursors.toList())
            assertFalse(session.canLoadMore(listOf(source.id)))
            source.gate.complete(Unit); session.resumeDetection()
            assertEquals(LyricState.READY, session.resolve(rows.first()).lyricState)
            report("PASS detection: peak=3; 3 active calls cancelled; 7 queued calls never started; pause retains cursor=10; resume retries")
        } finally { session.close() }
    }

    @Test fun explicitPreviewSurvivesStoppingAutomaticDetectionOnAndroid() = runBlocking {
        val source = Fake(); val session = OnlineSearch()
        try {
            var row: SearchCandidate? = null
            session.search(listOf(source), "Fixture", 10) { row = it.results.first() }
            val explicit = async(start = CoroutineStart.UNDISPATCHED) { session.resolve(row!!) }
            until { source.active.get() == 1 }
            session.detect(listOf(row!!)) {}; delay(100)
            session.pauseDetection(); source.gate.complete(Unit)
            val ready = withTimeout(3000) { explicit.await() }
            assertEquals(LyricState.READY, ready.lyricState); assertEquals(1, source.loads.get()); assertEquals(0, source.cancelled.get())
            assertEquals("roma", ready.song.lyrics!!.single().roma)
            assertEquals("translation", ready.song.lyrics!!.single().translation)
            report("PASS shared preview: automatic stop preserves explicit waiter; one download retains original/roma/translation")
        } finally { session.close() }
    }

    @Test fun realBitmapCaptureReusesCacheAndRefreshesLateAndChangedArtwork() {
        val bitmap = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888)
        val builder = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "device-${SystemClock.elapsedRealtime()}")
        assertNull(ArtworkCapture.capture(context, builder.build(), null))
        bitmap.eraseColor(Color.RED)
        val metadata = builder.putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap).build()
        try {
            val first = ArtworkCapture.capture(context, metadata, null)!!
            repeat(20) { assertSame(first, ArtworkCapture.capture(context, metadata, null)) }
            bitmap.eraseColor(Color.BLUE)
            val second = ArtworkCapture.capture(context, metadata, null)!!
            assertNotEquals(first, second)
            val bytes = android.util.Base64.decode(second.removePrefix("base64:"), android.util.Base64.DEFAULT)
            val decoded = ArtworkCapture.decode(bytes)!!
            assertEquals(256, decoded.width); assertEquals(128, decoded.height)
            assertTrue(Color.blue(decoded.getPixel(50, 50)) > 200)
            decoded.recycle()
            report("PASS Android artwork: late bitmap bypasses negative cache; repeat captures reuse result; generation change refreshes; thumbnail=256x128")
        } finally { bitmap.recycle() }
    }

    @Test fun artworkLoaderSharesDecodedBitmapBetweenViews() = runBlocking {
        val bitmap = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(12, 78, 131)) }
        val source = ArtworkCapture.capture(context, MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "loader-${SystemClock.elapsedRealtime()}").putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap).build(), null)!!
        lateinit var first: ImageView; lateinit var second: ImageView
        try {
            instrument.runOnMainSync { first = ImageView(context); second = ImageView(context); ArtworkLoader.bind(first, source); ArtworkLoader.bind(second, source) }
            until { first.drawable is BitmapDrawable && second.drawable is BitmapDrawable }
            instrument.runOnMainSync {
                assertSame((first.drawable as BitmapDrawable).bitmap, (second.drawable as BitmapDrawable).bitmap)
                assertTrue((first.drawable as BitmapDrawable).bitmap.width <= 256)
                assertEquals(0, first.paddingLeft)
            }
            report("PASS artwork loader: simultaneous views share one Bitmap and use bounded thumbnails")
        } finally {
            bitmap.recycle()
            File(context.cacheDir, "artwork-thumbnails/${SearchCandidate.digest(source)}.jpg").delete()
        }
    }

    @Test fun realHookedConePlayerDeliversThreeFieldsAndPreviewDemandDoesNotReloadLyrics() = runBlocking {
        val store = ProviderStore(context)
        val sent = store.snapshot()
        assertEquals("ink.trantor.coneplayer", sent.player)
        assertTrue(sent.song?.lyrics.orEmpty().isNotEmpty())
        assertTrue(sent.song!!.lyrics!!.any { !it.roma.isNullOrBlank() && !it.translation.isNullOrBlank() })
        val receiver = ReceiverCheck(context) {}
        instrument.runOnMainSync { receiver.start() }
        try {
            until { receiver.state.song != null && receiver.state.provider.isNotBlank() }
            val state = receiver.state
            val check = TransferVerifier.compare(sent, state.song, state.provider, state.player)
            assertTrue(check, check.startsWith("字段一致"))
            assertEquals(sent.song!!.lyrics!!.flatMap { it.words.orEmpty() }, state.song!!.lyrics!!.flatMap { it.words.orEmpty() })
            val lines = sent.song!!.lyrics!!
            report("PASS real player -> plugin -> Lyricon: ${sent.song!!.name}; original=${lines.count { !it.text.isNullOrBlank() }} roma=${lines.count { !it.roma.isNullOrBlank() }} translation=${lines.count { !it.translation.isNullOrBlank() }}; word timings equal; cover=${sent.artwork.isNotBlank()}")
        } finally { instrument.runOnMainSync { receiver.stop() } }

        val activity = launchTestActivity()
        fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
        try {
            val eventsBefore = store.events().size
            val updatedBefore = store.snapshot().updatedAt
            val retryBefore = store.retryVersion()
            val revisionBefore = store.settings().revision
            instrument.runOnMainSync { assertTrue(all(activity.window.decorView).filterIsInstance<TextView>().first { it.text.toString() == "查看歌词" }.performClick()) }
            until { store.previewUntil() > SystemClock.elapsedRealtime() && store.playback().player == sent.player }
            shell("cmd media_session dispatch play")
            until { store.playback().playing }
            delay(1000)
            val running = store.playback()
            assertTrue(running.positionAt(SystemClock.elapsedRealtime()) > running.positionMs)
            shell("cmd media_session dispatch pause")
            until { !store.playback().playing }
            val paused = store.playback()
            delay(300)
            assertEquals(paused.positionMs, paused.positionAt(SystemClock.elapsedRealtime()))
            instrument.runOnMainSync { activity.moveTaskToBack(true) }
            until { store.previewUntil() == 0L }
            val frozen = store.playback()
            shell("cmd media_session dispatch play"); delay(1200); shell("cmd media_session dispatch pause"); delay(300)
            assertEquals(frozen, store.playback())
            assertEquals(eventsBefore, store.events().size); assertEquals(updatedBefore, store.snapshot().updatedAt)
            assertEquals(retryBefore, store.retryVersion()); assertEquals(revisionBefore, store.settings().revision)
            report("PASS hooked player preview: visible-only IPC; play/pause progress correct; background stops progress publication; demand changes create no lyric reload/event/settings writes")
        } finally { shell("cmd media_session dispatch pause"); instrument.runOnMainSync { activity.finish() } }
    }
}
