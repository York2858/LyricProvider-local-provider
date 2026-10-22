package io.github.proify.lyricon.localprovider

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.cloudlyric.provider.apple.AppleMusicConfig
import io.github.proify.cloudlyric.provider.apple.AppleMusicDeveloperTokenProvider
import io.github.proify.cloudlyric.provider.apple.AppleMusicProvider
import io.github.proify.lyricon.localprovider.data.ProviderStore
import io.github.proify.lyricon.localprovider.search.OnlineSearch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in device check. Credentials stay in the app; only timings and counts are reported. */
class AppleMusicSearchDeviceTest {
    private fun report(value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "\nAPPLE_SEARCH $value\n")
        })
    }

    @Test fun currentSongCatalogAndLyrics() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("checkAppleSearch") == "true")
        val settings = ProviderStore(InstrumentationRegistry.getInstrumentation().targetContext).settings()
        val config = AppleMusicConfig(storefront = settings.appleStorefront,
            language = settings.appleLanguage, mediaUserToken = settings.appleUserToken,
            developerToken = settings.appleDeveloperToken)
        val provider = AppleMusicProvider(config)
        val session = OnlineSearch()
        val started = System.nanoTime()
        try {
            session.search(listOf(provider), "Eu Vou Torcer Jorge Ben Jor", 5) { batch ->
                report("catalog elapsedMs=${(System.nanoTime() - started) / 1_000_000} tracks=${batch.results.size} error=${batch.error.orEmpty()}")
                assertTrue("Apple catalog failed: ${batch.error}", batch.error == null)
                assertTrue("No matching catalog tracks", batch.results.isNotEmpty())
                if (config.mediaUserToken.isNotBlank()) {
                    val item = batch.results.first { it.song.artist.orEmpty().contains("Jorge", true) }
                    val lyrics = withTimeout(45_000) { session.resolve(item) }
                    report("lyrics state=${lyrics.lyricState} lines=${lyrics.song.lyrics.orEmpty().size}")
                    assertTrue("Apple lyrics unavailable", lyrics.song.lyrics.orEmpty().isNotEmpty())
                }
            }
        } finally { session.close() }
    }

    @Test fun developerTokenStage() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("checkAppleSearch") == "true")
        val started = System.nanoTime()
        report("token starting")
        val token = withTimeout(30_000) { AppleMusicDeveloperTokenProvider().getToken() }
        report("token elapsedMs=${(System.nanoTime() - started) / 1_000_000} present=${token.isNotBlank()}")
        assertTrue(token.isNotBlank())
    }
}
