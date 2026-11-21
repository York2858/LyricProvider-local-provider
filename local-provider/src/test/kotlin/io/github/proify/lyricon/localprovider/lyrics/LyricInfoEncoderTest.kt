package io.github.proify.lyricon.localprovider.lyrics

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.localprovider.local.ExternalLrcProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LyricInfoEncoderTest {
    @get:Rule val files = TemporaryFolder()

    @Test
    fun writebackOnlyChangesTheTranslationLane() {
        val original = LyricInfoEncoder.encode("Song", "Artist", null, "42", 7, LocalTtml.bridgeLines(duet), duet)!!
        val updated = LyricInfoEncoder.withTranslation(original, "[00:01.000]AI translation")
        val originalFields = Json.parseToJsonElement(original).jsonObject
        val updatedFields = Json.parseToJsonElement(updated).jsonObject
        assertEquals("[00:01.000]AI translation", updatedFields["translationLyric"]?.jsonPrimitive?.content)
        assertEquals(originalFields - "translationLyric", updatedFields - "translationLyric")
    }

    @Test
    fun localTtmlSidecarProducesNativeLrcAndAnUnflattenedOverlay() = runBlocking {
        val audio = files.newFile("Song.flac")
        files.newFile("Song.ttml").writeText(duet)
        val match = ExternalLrcProvider().searchByAudioFile(audio.path, "Song", "Artist", null)!!
        val ttml = match.ttml!!
        val payload = Json.parseToJsonElement(
            LyricInfoEncoder.encode("Song", "Artist", null, "42", 7, LocalTtml.bridgeLines(ttml), ttml)!!
        ).jsonObject

        assertEquals("[00:01.000]Hello there\n[00:03.000]Again", payload["lyric"]?.jsonPrimitive?.content)
        assertEquals("[00:01.000]Translation", payload["translationLyric"]?.jsonPrimitive?.content)
        assertEquals(
            "[00:01.000]<00:01.000>Hello <00:02.000>there<00:02.900>\n[00:03.000]Again",
            payload["rawLyric"]?.jsonPrimitive?.content
        )
        assertEquals(
            duet.trim().replace(Regex(">\\s*\\n\\s*<"), "><"), payload["ttml"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun bridgeFindsTheCompanionTtmlEvenWhenNormalLookupSelectsLrc() = runBlocking {
        val audio = files.newFile("Song.flac")
        files.newFile("Song.lrc").writeText("[00:01.000]Existing lyrics")
        files.newFile("Song.TTML").writeText(duet)
        val provider = ExternalLrcProvider()
        val ordinary = provider.searchByAudioFile(audio.path, "Song", "Artist", null)!!

        assertEquals("Existing lyrics", ordinary.rich.single().text)
        assertNull(ordinary.ttml)
        assertEquals(LocalTtml.forBridge(duet), provider.bridgeTtmlByAudioFile(audio.path))
    }

    @Test
    fun unsafeOrUnalignedOverlayIsOmitted() {
        assertNotNull(LocalTtml.forBridge(duet))
        assertNull(LocalTtml.forBridge(duet.replace("<body>", "<body lyricOffset=\"1.5\">")))
        assertNull(LocalTtml.forBridge(duet.replace("http://www.w3.org/ns/ttml\"", "urn:wrong\"")))
        assertNull(LocalTtml.forBridge("<!DOCTYPE tt>" + duet))
        assertNull(LocalTtml.forBridge(duet + " ".repeat(120_000)))
    }

    private val duet = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
          <head><metadata><ttm:agent type="person" xml:id="v1"/><ttm:agent type="person" xml:id="v2"/></metadata></head>
          <body><div>
            <p begin="1.000" end="3.000" ttm:agent="v1"><span begin="1.000" end="2.000">Hello</span> <span begin="2.000" end="2.900">there</span><span ttm:role="x-translation">Translation</span><span ttm:role="x-roman">roma</span></p>
            <p begin="3.000" end="6.000" ttm:agent="v2"><span begin="3.000" end="5.000">Again</span><span ttm:role="x-bg"><span begin="5.000" end="6.000">(hi)</span><span ttm:role="x-translation">background translation</span></span></p>
          </div></body>
        </tt>
    """.trimIndent()
    @Test
    fun keepsOriginalWordsAndTranslationInSeparateLanes() {
        val timed = RichLyricLine(
            begin = 10_025, end = 12_800, text = "第一句", roma = "diyi ju", translation = "First line",
            words = listOf(
                LyricWord(begin = 10_025, end = 10_700, text = "第一"),
                LyricWord(begin = 10_700, end = 12_800, text = "句")
            )
        )
        val plain = RichLyricLine(begin = 14_500, text = "第二句")
        val payload = Json.parseToJsonElement(
            LyricInfoEncoder.encode("Current track", "Artist", null, "42", 7, listOf(plain, timed))!!
        ).jsonObject

        assertEquals("42", payload["songId"]?.jsonPrimitive?.content)
        assertEquals("7", payload["sessionGeneration"]?.jsonPrimitive?.content)
        assertEquals("[00:10.025]第一句\n[00:14.500]第二句", payload["lyric"]?.jsonPrimitive?.content)
        assertEquals(
            "[00:10.025]<00:10.025>第一<00:10.700>句<00:12.800>\n[00:14.500]第二句",
            payload["rawLyric"]?.jsonPrimitive?.content
        )
        assertEquals("[00:10.025]First line", payload["translationLyric"]?.jsonPrimitive?.content)
    }

    @Test
    fun lineOnlyInputDoesNotInventWordTimingOrTreatRomanizationAsTranslation() {
        val line = RichLyricLine(
            begin = 0, end = 2_000, roma = "konnichiwa",
            words = listOf(LyricWord(begin = 0, end = 2_000, text = "こんにちは"))
        )
        val payload = Json.parseToJsonElement(
            LyricInfoEncoder.encode("Track", null, null, null, 1, listOf(line))!!
        ).jsonObject

        assertEquals("[00:00.000]こんにちは", payload["lyric"]?.jsonPrimitive?.content)
        assertFalse("rawLyric" in payload)
        assertFalse("translationLyric" in payload)
        assertNull(LyricInfoEncoder.encode("Track", null, null, null, 1, emptyList()))
    }
}
