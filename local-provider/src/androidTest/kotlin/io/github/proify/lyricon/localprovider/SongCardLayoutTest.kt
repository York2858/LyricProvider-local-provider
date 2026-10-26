package io.github.proify.lyricon.localprovider

import android.view.View
import android.widget.TextView
import android.widget.LinearLayout
import android.content.res.Configuration
import android.graphics.Rect
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
            assertEquals(dp(52), card.findViewWithTag<View>("songCover").width)
            assertTrue(card.findViewWithTag<View>("songTitle").width > 0)
            assertNotNull(card.findViewWithTag<View>("songArtistAlbum"))
            assertNotNull(card.findViewWithTag<View>("songFeatures"))
            assertTrue(card.contentDescription.toString().contains("04:08"))
            assertTrue("Compact result row grew too tall",card.height <= dp(84))
            assertNotNull(card.findViewWithTag<View>("songSource"))
            assertEquals(android.text.TextUtils.TruncateAt.END,
                card.findViewWithTag<android.widget.TextView>("songTitle").ellipsize)
        }
        assertNull(playing.findViewWithTag<View>("songMatchScore"))
        val score = result.findViewWithTag<View>("songMatchScore")
        assertTrue(score.isClickable)
        assertTrue(result.findViewWithTag<View>("songInfo").right + dp(8) <= score.left)
    }

    private fun bounds(root: ViewGroup,view: View): Rect = Rect(0,0,view.width,view.height).also { root.offsetDescendantRectToMyCoords(view,it) }

    @Test fun recentAndLibraryRowsKeepExactlyTwoTextLinesAndTrailingMetadata() = instrumentation.runOnMainSync {
        val song=Song(name="很长的歌曲名称仍然只占一行 Long track name",artist="测试歌手",lyrics=listOf(
            RichLyricLine(begin=0,end=5000,text="原文",roma="romanization",translation="翻译")))
        for(width in listOf(288,368)) for(scale in listOf(1f,1.5f)) for(style in listOf(SongResultCard.LayoutStyle.RECENT,SongResultCard.LayoutStyle.LIBRARY)) {
            val c=context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale=scale })
            val card=SongResultCard(c,song,"测试专辑","网易云音乐",layoutStyle=style,versionCount=2,onPreview={})
            measure(card,width)
            val info=card.findViewWithTag<LinearLayout>("songInfo")
            val side=card.findViewWithTag<View>("songSideMetadata")
            assertEquals("A third metadata line crept back into $style",2,info.childCount)
            assertTrue("Trailing badges overlap song text at $width / $scale",info.right<=side.left)
            assertTrue("Trailing badges overflow the card",side.right<=card.findViewWithTag<View>("songChevron").left)
            assertTrue("Title area was squeezed too small",info.width>=dp(72))
            for(i in 0 until info.childCount) {
                val label=info.getChildAt(i) as TextView
                assertEquals(1,label.maxLines); assertEquals(android.text.TextUtils.TruncateAt.END,label.ellipsize)
            }
            if(scale==1f) assertEquals("Two-line row must retain its compact height",dp(52)+dp(8)*2,card.height)
            val features=card.findViewWithTag<View>("songFeatures")
            assertTrue("Capabilities must stay to the right of the text",bounds(card,features).left>=bounds(card,info).right)
            if(style==SongResultCard.LayoutStyle.LIBRARY) {
                assertEquals("2 份",card.findViewWithTag<TextView>("songVersionCount").text.toString())
                assertEquals(2,(features as ViewGroup).childCount)
                val last=features.getChildAt(features.childCount-1)
                assertTrue("Badge clipped at $width / $scale",last.right<=features.width)
                if(scale==1f) assertTrue("Version count collides with a badge",bounds(card,last).right<=bounds(card,card.findViewWithTag<View>("songVersionCount")).left)
            } else assertNotNull(card.findViewWithTag<View>("songSourcePill"))
        }
    }

    @Test fun resultRowUsesSeparateBadgesAnUpperScoreAndIndependentActions() = instrumentation.runOnMainSync {
        val song=Song(name="晚风与海",artist="林间",lyrics=listOf(RichLyricLine(begin=0,end=1000,text="原文",roma="roma",translation="翻译")))
        var preview=0; var scoring=0
        val card=SongResultCard(context,song,"蓝色手记","QQ 音乐",score=96,onPreview={ preview++ },onScore={ scoring++ })
        measure(card,368)
        val features=card.findViewWithTag<ViewGroup>("songFeatures")
        assertEquals(2,features.childCount)
        val score=card.findViewWithTag<TextView>("songMatchScore")
        val source=card.findViewWithTag<View>("songSourcePill")
        assertTrue("Score must be in the upper trailing area",score.top<card.height/3)
        assertTrue("Source capsule should fit its contents",source.width<dp(115))
        assertTrue(bounds(card,source).left>=bounds(card,features).right)
        score.performClick(); assertEquals(1,scoring); assertEquals(0,preview)
        card.performClick(); assertEquals(1,preview)
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
