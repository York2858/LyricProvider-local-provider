package io.github.proify.lyricon.localprovider.preview

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.junit.Assert.*
import org.junit.Test

class PreviewTimelineTest {
    private fun line(begin: Long, end: Long, text: String) = RichLyricLine(begin = begin, end = end, text = text)
    @Test fun followsLinesBeforeFirstDuringGapsAndAfterLastWithoutFakeSinging() {
        val timeline = PreviewTimeline(listOf(line(1000, 2000, "one"), line(4000, 6000, "two")))
        assertEquals(PreviewTimeline.Position(0, singing = false), timeline.locate(0))
        assertEquals(PreviewTimeline.Position(0, singing = true), timeline.locate(1000))
        assertEquals(PreviewTimeline.Position(0, singing = false), timeline.locate(3000))
        assertEquals(PreviewTimeline.Position(1, singing = true), timeline.locate(4000))
        assertEquals(PreviewTimeline.Position(1, singing = false), timeline.locate(9000))
        assertNull(timeline.locate(-1))
        assertNull(PreviewTimeline(emptyList()).locate(100))
    }
    @Test fun repeatedWordsAndSpacesKeepCorrectCharacterRangesAndBoundaries() {
        val timeline = PreviewTimeline(listOf(line(1000, 5000, "go go 君へ").copy(words = listOf(
            LyricWord(begin = 1000, end = 2000, text = "go"),
            LyricWord(begin = 2200, end = 3000, text = "go"),
            LyricWord(begin = 3100, end = 3500, text = "君"),
            LyricWord(begin = 3500, end = 4000, text = "へ")))))
        assertEquals(listOf(0, 3, 6, 7), timeline.display.single().words.map { it.start })
        assertEquals(0, timeline.locate(1000)?.word)
        assertEquals(-1, timeline.locate(2100)?.word)
        assertEquals(1, timeline.locate(2200)?.word)
        assertEquals(3, timeline.locate(3500)?.word)
        assertEquals(-1, timeline.locate(4000)?.word)
        assertEquals(1, timeline.locate(2500, offsetMs = 300)?.word)
        assertEquals(7, timeline.anchorOffset(timeline.locate(4200)!!, 4200))
        assertEquals(0, timeline.anchorOffset(timeline.locate(2100)!!, 2100))
        assertEquals(3, timeline.anchorOffset(timeline.locate(3050)!!, 3050))
    }
    @Test fun usesTimedTextIfOriginalTextDoesNotMatchAndDoesNotInventWordTimings() {
        val timeline = PreviewTimeline(listOf(line(0, 2000, "wrong").copy(words = listOf(
            LyricWord(begin = 0, end = 1000, text = "你"), LyricWord(begin = 1000, end = 2000, text = "好")))))
        assertEquals("你好", timeline.display.single().text)
        assertEquals(1, timeline.locate(1000)?.word)
        val untimed = PreviewTimeline(listOf(line(0, 2000, "你好").copy(words = listOf(LyricWord(text = "你好")))))
        assertEquals(-1, untimed.locate(1000)?.word)
    }
    @Test fun handlesMissingLineEndsAndUnsortedLines() {
        val timeline = PreviewTimeline(listOf(line(4000, 6000, "last"), line(1000, 0, "first")))
        assertEquals(1, timeline.locate(3000)?.line)
        assertEquals(true, timeline.locate(3000)?.singing)
        assertEquals(0, timeline.locate(4000)?.line)
        assertNull(PreviewTimeline(listOf(line(0, 0, "没有时间轴"))).locate(1000))
        val wordOnly = PreviewTimeline(listOf(line(0, 0, "字").copy(words = listOf(LyricWord(begin = 0, end = 1000, text = "字")))))
        assertEquals(0, wordOnly.locate(500)?.word)
    }
    @Test fun schedulingSleepsUntilTheNextWordOrLineAndStopsWhenPaused() {
        val timeline = PreviewTimeline(listOf(line(1000, 5000, "ab").copy(words = listOf(
            LyricWord(begin = 1000, end = 2000, text = "a"), LyricWord(begin = 2500, end = 5000, text = "b"))),
            line(9000, 12000, "next")))
        assertEquals(1000L, timeline.nextBoundary(0))
        assertEquals(2000L, timeline.nextBoundary(1000))
        assertEquals(500L, timeline.nextDelay(1000, playing = true, speed = 2f))
        assertEquals(9000L, timeline.nextBoundary(5000))
        assertEquals(1300L, timeline.nextBoundary(1000, 300))
        assertNull(timeline.nextDelay(1000, playing = false, speed = 1f))
        assertNull(timeline.nextDelay(1000, playing = true, speed = 0f))
        assertNull(timeline.nextBoundary(12000))
    }
    @Test fun binaryLookupPreservesUnsortedOverlappingWordsAndEqualLineStarts() {
        val words = listOf(LyricWord(begin = 1500, end = 4000, text = "a"),
            LyricWord(begin = 1000, end = 2000, text = "b"), LyricWord(begin = 2500, end = 3000, text = "c"))
        val timeline = PreviewTimeline(listOf(line(1000, 5000, "abc").copy(words = words)))
        for (time in 0L..6000L step 37) {
            val singing = time in 1000 until 5000
            val expected = if (singing) words.indexOfLast { time >= it.begin && time < it.end } else -1
            val position = timeline.locate(time)!!
            assertEquals("at $time", expected, position.word)
            val anchor = if (expected >= 0) expected else words.indexOfLast { it.begin <= time }
            assertEquals("anchor at $time", anchor.coerceAtLeast(0), timeline.anchorOffset(position, time))
        }
        val equal = PreviewTimeline(listOf(line(1000, 2000, "one"), line(1000, 3000, "two")))
        assertEquals(0, equal.locate(0)?.line)
        assertEquals(1, equal.locate(1000)?.line)
    }
    @Test fun playbackInterpolationHonorsPauseSeekSpeedClockAndDuration() {
        val state = PlaybackSnapshot(positionMs = 3000, updatedElapsedMs = 1000, speed = 1.5f, playing = true)
        assertEquals(4500, state.positionAt(2000))
        assertEquals(3000, state.positionAt(500))
        assertEquals(4000, state.positionAt(2000, durationMs = 4000))
        assertEquals(3000, state.copy(playing = false).positionAt(2000))
        assertEquals(1500, state.copy(positionMs = 0).positionAt(2000))
        assertEquals(-1, state.copy(positionMs = -1).positionAt(2000))
    }
}
