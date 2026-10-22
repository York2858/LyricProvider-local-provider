package io.github.proify.lyricon.localprovider

import android.view.View
import android.widget.Button
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.localprovider.ui.SongResultCard
import io.github.proify.lyricon.localprovider.ui.SpacedButtonLayout
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import org.junit.Assert.*
import org.junit.Test

class SongCardLayoutTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun dp(value: Int) = (context.resources.displayMetrics.density * value).toInt()
    private fun measure(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    @Test fun resultAndPlayingCardsShareMetadataButOnlyResultsHaveAScore() = instrumentation.runOnMainSync {
        val song = Song(name = "10年後の君へ (致10年后的你)", artist = "板野友美", duration = 248000,
            lyrics = listOf(RichLyricLine(begin = 0, end = 1000, text = "原文", roma = "romaji", translation = "翻译")))
        val result = SongResultCard(context, song, "Album", "网易云音乐", score = 98, onPreview = {}, onScore = {})
        val playing = SongResultCard(context, song, "Album", "网易云音乐", onPreview = {})
        listOf(result, playing).forEach { card ->
            measure(card, 288)
            assertEquals(dp(56), card.findViewWithTag<View>("songCover").width)
            assertTrue(card.findViewWithTag<View>("songTitle").width > 0)
            assertNotNull(card.findViewWithTag<View>("songArtistAlbum"))
            assertNotNull(card.findViewWithTag<View>("songFeatures"))
            assertNotNull(card.findViewWithTag<View>("songDuration"))
            assertTrue(card.findViewWithTag<View>("songDuration").right <= card.findViewWithTag<View>("songFeatures").left)
            assertEquals(android.text.TextUtils.TruncateAt.MARQUEE,
                card.findViewWithTag<android.widget.TextView>("songTitle").ellipsize)
        }
        assertNull(playing.findViewWithTag<View>("songMatchScore"))
        val score = result.findViewWithTag<View>("songMatchScore")
        assertTrue(score.isClickable)
        assertTrue(result.findViewWithTag<View>("songInfo").right + dp(8) <= score.left)
    }

    @Test fun toolbarKeepsTouchGapsAndWrapsTheThirdButtonOnNarrowScreens() = instrumentation.runOnMainSync {
        val toolbar = SpacedButtonLayout(context)
        repeat(3) { index -> toolbar.addView(Button(context).apply { text = "按钮 $index" }, ViewGroup.MarginLayoutParams(dp(120), dp(48))) }
        measure(toolbar, 288)
        val first = toolbar.getChildAt(0); val second = toolbar.getChildAt(1); val third = toolbar.getChildAt(2)
        assertEquals(first.top, second.top)
        assertTrue(second.left - first.right >= dp(10))
        assertTrue(third.top - first.bottom >= dp(8))
        assertTrue(third.right <= toolbar.width)
    }

    @Test fun metadataOnlyCardShowsDetectionStateAndStillHasAScoreAndPreviewAction() = instrumentation.runOnMainSync {
        val song = Song(name = "搜索歌曲", artist = "歌手", duration = 240000)
        var opened = false
        val card = SongResultCard(context, song, "专辑", "Apple Music", score = 96,
            onPreview = { opened = true }, lyricStatus = "获取失败 · 点击重试")
        measure(card, 288)
        assertEquals("获取失败 · 点击重试", card.findViewWithTag<android.widget.TextView>("songFeatures").text.toString())
        assertNotNull(card.findViewWithTag<View>("songMatchScore"))
        card.performClick(); assertTrue(opened)
    }
}
