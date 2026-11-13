package io.github.proify.lyricon.localprovider.lyrics

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LyricInfoEncoderTest {
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
