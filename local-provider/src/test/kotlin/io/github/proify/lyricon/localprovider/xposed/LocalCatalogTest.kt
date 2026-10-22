package io.github.proify.lyricon.localprovider.xposed

import io.github.proify.lyricon.localprovider.data.Catalog
import io.github.proify.lyricon.localprovider.data.ProviderSettings
import io.github.proify.lyricon.localprovider.data.trackKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LocalCatalogTest {
    @Test fun importedEnhancedLyricsExposeSeparateRomaAndTranslation() {
        val raw = """
            [ti:WINDY SUMMER]
            [ar:杏里]
            [00:01.000]<00:01.000>あ<00:01.500>なた<00:03.000>
            [00:01.000]a na ta[00:03.000]
            [00:01.000]你[00:03.000]
        """.trimIndent()
        val file = Catalog.parse("test", "test.lrc", "Music", 1, raw)!!
        assertEquals("WINDY SUMMER", file.song.name)
        assertEquals("杏里", file.song.artist)
        assertTrue(file.wordLevel)
        assertTrue(file.romanization)
        assertTrue(file.translated)
        assertEquals("你", file.song.lyrics!!.single().translation)
    }
    @Test fun settingsRoundTripPreservesCloudOffAndChosenDirectories() {
        val original = ProviderSettings(cloudEnabled = false, folders = emptyList(), saveCloud = false)
        val decoded = Json.decodeFromString<ProviderSettings>(Json.encodeToString(original))
        assertFalse(decoded.cloudEnabled)
        assertFalse(decoded.saveCloud)
        assertTrue(decoded.folders.isEmpty())
    }
    @Test fun differentArtistsKeepIndependentManualSelections() {
        assertNotEquals(trackKey("Home", "Artist A"), trackKey("Home", "Artist B"))
        assertEquals(trackKey(" HOME ", " Artist A "), trackKey("home", "artist a"))
    }
    @Test fun legacyBlankWordMarkersDoNotBlockOnlineRecovery() {
        assertNull(Catalog.parse("test", "old.lrc", "Music", 1,
            "[ti:测试歌曲]\n[00:00.000] <00:00.000>v1:<00:00.000><00:00.000>"))
        assertNotNull(Catalog.parse("test", "good.lrc", "Music", 1, "[00:00.000]真正从零开始的歌词"))
    }
    @Test fun malformedLyricsDoNotAppearAsUsableFiles() {
        assertNull(Catalog.parse("test", "empty.lrc", "Music", 1, "no timing information"))
    }
}
