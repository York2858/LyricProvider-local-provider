package io.github.proify.lyricon.localprovider

import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.cloudlyric.CatalogProvider
import io.github.proify.cloudlyric.LyricsProvider
import io.github.proify.cloudlyric.provider.LyricsConfig
import io.github.proify.cloudlyric.provider.apple.AppleMusicConfig
import io.github.proify.cloudlyric.provider.apple.AppleMusicProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** External services are reported individually; outages are not labelled as app passes. */
class SupplementalLiveDeviceTest {
    private fun report(message: String) {
        Log.i("SupplementalCheck", message)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", "\n$message\n") })
    }
    @Test fun sourcePagingLyricsAndCoverMetadataFromPhone() = runBlocking {
        val providers: List<LyricsProvider> = LyricsConfig.ALL_PROVIDERS + AppleMusicProvider(AppleMusicConfig())
        var successful = 0
        for (source in providers) {
            val catalog = source as CatalogProvider
            val first = try { withTimeout(15000) { catalog.searchCatalog("Yellow Coldplay", 2) } }
            catch (e: Exception) { report("UNAVAILABLE source=${source.id} stage=search reason=${e.javaClass.simpleName}"); continue }
            if (first.tracks.isEmpty()) { report("UNAVAILABLE source=${source.id} stage=search empty catalog"); continue }
            successful++
            assertTrue(first.tracks.all { it.metadata.rich.isEmpty() && !it.metadata.trackName.isNullOrBlank() })
            report("LIVE source=${source.id} first=${first.tracks.size} catalog-before-lyrics=true covers=${first.tracks.count { it.metadata.artworkUrl.isNotBlank() }} versions=${first.tracks.sumOf { it.versions.size }}")
            first.nextCursor?.let { cursor ->
                try {
                    val second = withTimeout(15000) { catalog.searchCatalog("Yellow Coldplay", 2, cursor) }
                    val overlap = first.tracks.map { it.key }.intersect(second.tracks.map { it.key }.toSet()).size
                    report("LIVE source=${source.id} second=${second.tracks.size} overlap=$overlap cursor-advanced=${second.nextCursor != cursor}")
                    assertEquals("Repeated catalog page from ${source.id}", 0, overlap)
                } catch (e: Exception) { report("UNAVAILABLE source=${source.id} stage=next-page reason=${e.javaClass.simpleName}") }
            }
            if (source is AppleMusicProvider) { report("SKIP AppleMusic lyrics: public-catalog check only; no account token used"); continue }
            try {
                val lyric = withTimeout(15000) { catalog.loadLyrics(first.tracks.first()) }
                report("LIVE source=${source.id} lyric-lines=${lyric?.rich?.size ?: 0} timed=${lyric?.rich?.count { it.end > it.begin } ?: 0}")
                lyric?.rich?.forEach { assertTrue(it.end >= it.begin) }
            } catch (e: Exception) { report("UNAVAILABLE source=${source.id} stage=lyrics reason=${e.javaClass.simpleName}") }
        }
        report("LIVE catalog sources responding=$successful/${providers.size}; see per-source availability above")
        assertTrue("No source returned any catalog on phone network", successful > 0)
    }
}
