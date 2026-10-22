package io.github.proify.cloudlyric.provider.apple

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppleMusicDeveloperTokenProviderTest {
    private val provider = AppleMusicDeveloperTokenProvider()
    @Test fun acceptsTypedJwtHeadersAndStopsAtTheClosingQuote() {
        val token = "eyJ0eXAiOiJKV1QifQ.eyJpc3MiOiJhcHBsZSJ9.c2lnbmF0dXJl"
        assertEquals(token, provider.extractToken("const token=\"$token\";const next=\"eyJhOther.payload.signature\";"))
    }
    @Test fun ignoresIncompleteJwtFragments() {
        assertNull(provider.extractToken("text eyJhIncomplete; not a token"))
    }
}
