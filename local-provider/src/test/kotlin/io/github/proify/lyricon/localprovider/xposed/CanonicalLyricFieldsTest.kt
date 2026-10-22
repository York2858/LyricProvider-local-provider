package io.github.proify.lyricon.localprovider.xposed

import io.github.proify.lyricon.localprovider.lyrics.LyricNormalizer
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CanonicalLyricFieldsTest {
    @Test fun canonicalFieldsSurviveTheActualWireSerializer() {
        val source = RichLyricLine(begin = 1000, end = 3000, text = "あなたの心へ今すぐ",
            secondary = "a na ta no ko ko ro he i ma su gu", translation = "现在就想赶往你的心中")
        val normalized = LyricNormalizer.normalizeForTripleLine(listOf(source))
        val wire = Json.encodeToString(Song(name = "WINDY SUMMER", artist = "杏里", lyrics = normalized))
        val received = Json.decodeFromString<Song>(wire).lyrics!!.single()
        assertEquals(source.text, received.text)
        assertEquals(source.secondary, received.roma)
        assertEquals(source.translation, received.translation)
        assertTrue(wire.contains("\"roma\""))
        assertNull(source.roma)
    }
    @Test fun translatingToEnglishDoesNotTurnItIntoRomanization() {
        val source = RichLyricLine(text = "こんにちは", roma = "konnichiwa", translation = "Hello")
        val line = LyricNormalizer.normalizeForTripleLine(listOf(source)).single()
        assertEquals("konnichiwa", line.roma)
        assertEquals("Hello", line.translation)
    }
    @Test fun oldLocalProviderSwappedFieldsAreRepaired() {
        val line = LyricNormalizer.normalizeForTripleLine(listOf(RichLyricLine(
            text = "こんにちは", secondary = "konnichiwa", roma = "你好"
        ))).single()
        assertEquals("konnichiwa", line.roma)
        assertEquals("你好", line.translation)
    }
    @Test fun normalizationIsIdempotent() {
        val source = listOf(RichLyricLine(text = "こんにちは", roma = "konnichiwa", translation = "你好"))
        val once = LyricNormalizer.normalizeForTripleLine(source)
        assertEquals(once, LyricNormalizer.normalizeForTripleLine(once))
    }
    @Test fun translationOnlyRemainsTranslationOnly() {
        val line = LyricNormalizer.normalizeForTripleLine(listOf(RichLyricLine(
            text = "Hello", secondary = "你好"
        ))).single()
        assertNull(line.roma)
        assertEquals("你好", line.translation)
    }
    @Test fun romanizationOnlyRemainsRomanizationOnly() {
        val line = LyricNormalizer.normalizeForTripleLine(listOf(RichLyricLine(
            text = "こんにちは", secondary = "konnichiwa"
        ))).single()
        assertEquals("konnichiwa", line.roma)
        assertNull(line.translation)
    }
    @Test fun koreanRomanizationIsKeptSeparatelyFromTranslation() {
        val line = LyricNormalizer.normalizeForTripleLine(listOf(RichLyricLine(
            text = "안녕하세요", secondary = "annyeonghaseyo", translation = "你好"
        ))).single()
        assertEquals("annyeonghaseyo", line.roma)
        assertEquals("你好", line.translation)
    }
    @Test fun explicitRomanizationWinsOverOtherLatinSecondaryText() {
        val line = LyricNormalizer.normalizeForTripleLine(listOf(RichLyricLine(
            text = "こんにちは", secondary = "Oh yeah", roma = "konnichiwa", translation = "你好"
        ))).single()
        assertEquals("konnichiwa", line.roma)
        assertEquals("你好", line.translation)
    }
}
