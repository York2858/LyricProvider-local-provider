package io.github.proify.lyricon.localprovider.lyrics

import io.github.proify.cloudlyric.provider.apple.AppleMusicTtmlParser
import io.github.proify.lyricon.localprovider.data.LyricFile
import io.github.proify.lyricon.localprovider.search.SearchCandidate
import io.github.proify.lyricon.localprovider.search.MatchRules
import io.github.proify.lyricon.localprovider.search.MatchTarget
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.junit.Assert.*
import org.junit.Test

class LyricTimingTest {
    private fun word(text: String, begin: Long, end: Long) = LyricWord(text = text, begin = begin, end = end)
    private val apple = RichLyricLine(begin = 7649, end = 12255, text = "Eu vou torcer pela paz",
        words = listOf(word("Eu vou torcer pela paz", 7649, 12255)))
    private val kugou = RichLyricLine(begin = 7165, end = 11645, text = "Eu vou torcer pela paz",
        words = listOf(word("Eu ", 7165, 7425), word("vou ", 7425, 7735), word("torcer ", 7735, 8875),
            word("pela ", 8875, 9155), word("paz", 9155, 11645)))
    private fun timed(vararg lines: RichLyricLine) = LyricTiming.hasWordTiming(lines.toList())

    @Test fun capturedAppleSentenceIsLineTimedAndKugouWordsAreWordTimed() {
        assertFalse(timed(apple))
        assertTrue(timed(kugou))
        assertTrue(timed(apple, kugou))
    }

    @Test fun lineOnlyAppleTtmlDoesNotAcquireWordClassificationFromParserFallback() {
        val parsed = AppleMusicTtmlParser.parse("""<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="00:07.649" end="00:12.255">Eu vou torcer pela paz</p>
            <p begin="00:12.255" end="00:18.570">Pela alegria, pelo amor</p>
            </div></body></tt>""")
        assertEquals(2, parsed.size)
        assertTrue(parsed.all { it.words?.size == 1 })
        assertFalse(LyricTiming.hasWordTiming(parsed))
    }

    @Test fun repeatedWholeLineTimestampsAndWhitespaceDoNotCreateWordTiming() {
        assertFalse(timed(apple.copy(words = listOf(word("Eu", 7649, 12255), word("vou", 7649, 12255)))))
        assertFalse(timed(apple.copy(words = listOf(word("Eu vou torcer pela paz", 7649, 12000), word(" ", 12000, 12255)))))
        assertFalse(timed(apple.copy(words = emptyList())))
        assertFalse(LyricTiming.hasWordTiming(emptyList()))
    }

    @Test fun invalidWordTimestampsDoNotUpgradeALine() {
        listOf(word("invalid", -1, 8000), word("invalid", 8000, 8000),
            word("invalid", 8000, 13000), word("invalid", 7000, 8000)).forEach { invalid ->
            assertFalse(timed(apple.copy(words = listOf(word("valid", 7649, 8000), invalid))))
        }
        assertFalse(timed(kugou.copy(begin = -1)))
    }

    @Test fun durationFallbackAndOverlappingRealWordsRemainSupported() {
        assertTrue(timed(kugou.copy(end = 0, duration = 4480, words = listOf(
            LyricWord(text = "Eu ", begin = 7165, end = 0, duration = 260), word("vou", 7425, 7735)))))
        assertTrue(timed(apple.copy(words = listOf(word("hello ", 7649, 9000), word("world", 8500, 10000)))))
        assertFalse(timed(apple.copy(begin = Long.MAX_VALUE - 1, end = 0, duration = 10)))
    }

    @Test fun translationAndRomanizationTimingDoNotMislabelTheOriginal() {
        assertFalse(timed(apple.copy(secondaryWords = kugou.words, translationWords = kugou.words)))
    }

    @Test fun catalogAndSearchUseTheSameClassificationAndPreferRealWordsAtEqualScores() {
        fun song(line: RichLyricLine) = Song(name = "Eu Vou Torcer", artist = "Jorge Ben Jor", duration = 195000, lyrics = listOf(line))
        val line = SearchCandidate("apple", "AppleMusic", song(apple))
        val word = SearchCandidate("kugou", "KuGou", song(kugou))
        assertFalse(line.wordLevel)
        assertTrue(word.wordLevel)
        assertFalse(LyricFile("test", "apple.ttml", "Music", 0, line.song).wordLevel)
        assertTrue(LyricFile("test", "kugou.lrc", "Music", 0, word.song).wordLevel)
        assertEquals("kugou", listOf(line, word).sortedWith(SearchCandidate.ranking(
            MatchTarget("Eu Vou Torcer", "Jorge Ben Jor", "", 195000), MatchRules())).first().id)
    }
}
