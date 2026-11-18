package io.github.proify.lyricon.localprovider.xposed

import io.github.proify.lyricon.localprovider.lyrics.LyricInfoEncoder
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NeutronLockscreenPolicyTest {
    private val neutron = "com.neutroncode.mp"
    private val lyrics = requireNotNull(LyricInfoEncoder.encode("Track", null, null, null, 1,
        listOf(RichLyricLine(begin = 1500, end = 2000, text = "Example"))))

    @Test fun enablesOnlyLocalProviderLyricsForNeutron() {
        fun supports(value: String?) = NeutronLockscreenPolicy.support(false, neutron) { value }
        assertTrue(supports(lyrics))
        assertFalse(supports(lyrics.replace(LyricInfoEncoder.SOURCE, "different-source")))
        assertFalse(supports(lyrics.replace("Example", "   ")))
        assertFalse(supports(lyrics.replace("\"noLyric\":false", "\"noLyric\":true")))
        assertFalse(supports("{broken"))
        assertFalse(supports(null))
        assertFalse(NeutronLockscreenPolicy.support(false, "com.neutroncode.mpeval") { error("Other player") })
        assertTrue(NeutronLockscreenPolicy.support(true, neutron) { error("Already handled") })
    }

    @Test fun unavailableEntranceIsEnabledOnlyForNeutron() {
        assertEquals(52, NeutronLockscreenPolicy.entrance(0, neutron) { lyrics })
        assertEquals(0, NeutronLockscreenPolicy.entrance(0, neutron) { null })
        assertEquals(0, NeutronLockscreenPolicy.entrance(0, "other.player") { error("Other player") })
        assertEquals(7, NeutronLockscreenPolicy.entrance(7, neutron) { error("Already handled") })
    }
}
