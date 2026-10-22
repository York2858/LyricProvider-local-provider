/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.apple

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import io.github.proify.cloudlyric.CatalogAccessException
import io.github.proify.extensions.net.HttpStatusException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import java.io.IOException

class AppleMusicProviderTest {

    private val catalog = """{"results":{"songs":{"data":[{"id":"123","attributes":{"name":"Eu Vou Torcer","artistName":"Jorge Ben Jor"}}]}}}"""
    private fun tokens(onLoad: () -> Unit) = AppleMusicDeveloperTokenProvider { url ->
        onLoad()
        if (url.endsWith(".js")) "const token=\"eyJhbGciOiJFUzI1NiJ9.eyJpc3MiOiJhcHBsZSJ9.c2ln\";"
        else "<script src=\"/assets/index-fixture.js\"></script>"
    }

    @Test fun separateSearchSessionsReuseTokenAndDoNotDisableCompression() = runBlocking {
        var tokenLoads = 0
        val token = tokens { tokenLoads++ }
        repeat(2) {
            val source = AppleMusicProvider(AppleMusicConfig(), token) { _, headers ->
                assertTrue("Accept-Encoding" !in headers)
                catalog
            }
            assertEquals("123", source.searchCatalog("Eu Vou Torcer", 5).tracks.single().key)
        }
        assertEquals(2, tokenLoads) // Homepage + script once for both sessions.
    }

    @Test fun onlyAuthenticationErrorsRefreshTheDeveloperToken() = runBlocking {
        var loads = 0
        var calls = 0
        val source = AppleMusicProvider(AppleMusicConfig(), tokens { loads++ }) { _, _ ->
            if (++calls == 1) throw HttpStatusException(401)
            catalog
        }
        assertEquals(1, source.searchCatalog("song", 1).tracks.size)
        assertEquals(2, calls)
        assertEquals(4, loads)
    }

    @Test fun unavailableServerAndConnectionDoNotRestartTokenDownloads() = runBlocking {
        listOf(IOException("fixture"), HttpStatusException(503)).forEach { failure ->
            var loads = 0
            var calls = 0
            val source = AppleMusicProvider(AppleMusicConfig(), tokens { loads++ }) { _, _ ->
                calls++; throw failure
            }
            assertFailsWith<CatalogAccessException> { source.searchCatalog("song", 1) }
            assertEquals(1, calls)
            assertEquals(2, loads)
        }
    }

    @Test fun manualTokenAndCancellationAreNotRetried() = runBlocking<Unit> {
        var calls = 0
        val source = AppleMusicProvider(AppleMusicConfig(developerToken = "manual"), tokens { error("must not fetch") }) { _, _ ->
            calls++; throw HttpStatusException(403)
        }
        assertFailsWith<CatalogAccessException> { source.searchCatalog("song", 1) }
        assertEquals(1, calls)
        val cancelled = AppleMusicProvider(AppleMusicConfig(developerToken = "manual")) { _, _ ->
            throw CancellationException("fixture")
        }
        assertFailsWith<CancellationException> { cancelled.searchCatalog("song", 1) }
    }

    private val provider = AppleMusicProvider(
        AppleMusicConfig(
            storefront = "us",
            mediaUserToken = "test-token"
        )
    )

    @Test
    fun selectPreferredTtml_prefersLocalizationForChineseLanguage() {
        val selected = provider.selectPreferredTtml(
            ttml = "<tt>original</tt>",
            localizedTtml = "<tt>translated</tt>",
            language = "zh-Hans-CN"
        )

        assertEquals("<tt>translated</tt>", selected)
    }

    @Test
    fun selectPreferredTtml_prefersOriginalForEnglishLanguage() {
        val selected = provider.selectPreferredTtml(
            ttml = "<tt>original</tt>",
            localizedTtml = "<tt>translated</tt>",
            language = "en-US"
        )

        assertEquals("<tt>original</tt>", selected)
    }

    @Test
    fun selectPreferredTtml_fallsBackToOriginalWhenLocalizationMissing() {
        val selected = provider.selectPreferredTtml(
            ttml = "<tt>original</tt>",
            localizedTtml = "",
            language = "zh-Hans-CN"
        )

        assertEquals("<tt>original</tt>", selected)
    }
}
