package io.github.proify.lyricon.localprovider

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import io.github.proify.lyricon.localprovider.data.*
import io.github.proify.lyricon.localprovider.preview.PlaybackSnapshot
import io.github.proify.lyricon.localprovider.ui.MainActivity
import io.github.proify.lyricon.lyric.model.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PerformanceBehaviorTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private fun waitUntil(check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5000
        while (!check()) { assertTrue("Timed out", SystemClock.elapsedRealtime() < end); Thread.sleep(30) }
    }
    @Test fun previewSubscribesOnlyWhileVisibleAndPauseSeekAndWordsStayCorrect() {
        val prefs = context.getSharedPreferences("local_provider_ui", 0)
        val previous = listOf("snapshot", "events", "choices").associateWith { prefs.getString(it, null) }
        val store = ProviderStore(context)
        val song = Song(name = "省电验证", artist = "Fixture", duration = 6000, lyrics = listOf(
            RichLyricLine(begin = 1000, end = 5000, text = "甲乙丙", roma = "a b c", translation = "测试译文",
                words = listOf(LyricWord(begin = 1000, end = 2000, text = "甲"),
                    LyricWord(begin = 2000, end = 3000, text = "乙"), LyricWord(begin = 4000, end = 5000, text = "丙")))))
        store.report(RuntimeSnapshot(player = "preview-fixture", song = song))
        fun restore() {
            prefs.edit().apply { previous.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) } }.commit()
            context.contentResolver.notifyChange(ProviderStore.URI, null)
        }
        val activity = try {
            launchTestActivity()
        } catch (error: Throwable) { restore(); throw error }
        fun configUntil() = context.contentResolver.call(ProviderStore.URI, "config", null, null)!!.getLong("previewUntil")
        fun emit(position: Long, playing: Boolean) {
            val state = PlaybackSnapshot("preview-fixture", trackKey(song.name, song.artist), position,
                SystemClock.elapsedRealtime(), 1f, playing)
            context.contentResolver.call(ProviderStore.URI, "playback", null, Bundle().apply { putString("json", ProviderStore.json.encodeToString(state)) })
            instrument.waitForIdleSync()
        }
        fun allViews(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()
        fun highlightedStart(): Int {
            var value = -1
            instrument.runOnMainSync {
                val text = allViews(activity.window.decorView).filterIsInstance<TextView>().firstOrNull { it.text.toString() == "甲乙丙" }?.text as? Spanned
                val span = text?.getSpans(0, text.length, BackgroundColorSpan::class.java)?.firstOrNull()
                if (span != null) value = text.getSpanStart(span)
            }
            return value
        }
        try {
            assertEquals(0L, configUntil())
            onView(withText("查看歌词")).perform(click())
            waitUntil { configUntil() > SystemClock.elapsedRealtime() }
            emit(1500, false); waitUntil { highlightedStart() == 0 }
            val tickField = MainActivity::class.java.getDeclaredField("previewTick").apply { isAccessible = true }
            val handlerField = MainActivity::class.java.getDeclaredField("main").apply { isAccessible = true }
            instrument.runOnMainSync { assertFalse((handlerField.get(activity) as Handler).hasCallbacks(tickField.get(activity) as Runnable)) }
            emit(2500, false); waitUntil { highlightedStart() == 1 }
            emit(1500, true); waitUntil { highlightedStart() == 0 }; waitUntil { highlightedStart() == 1 }
            instrument.runOnMainSync { activity.moveTaskToBack(true) }
            waitUntil { configUntil() == 0L }
            val previousPosition = store.playback().positionMs
            emit(4500, false)
            assertEquals(previousPosition, store.playback().positionMs)
        } finally {
            instrument.runOnMainSync { activity.finish() }; instrument.waitForIdleSync()
            restore()
        }
    }
    @Test fun directoryEditsRemovalsAndAmbiguousArtistsRefreshCorrectly() {
        val prefs = context.getSharedPreferences("local_provider_ui", 0)
        val previous = prefs.getString("settings", null)
        val folder = File(context.cacheDir, "performance-catalog-fixture").apply { mkdirs() }
        try {
            val settings = ProviderStore(context).settings().copy(folders = listOf(LyricFolder(folder.absolutePath, "Fixture")), files = emptyList())
            prefs.edit().putString("settings", ProviderStore.json.encodeToString(settings)).commit()
            val file = File(folder, "test.lrc")
            file.writeText("[ti:目录省电验证]\n[ar:A]\n[00:01.00]原句\n[00:03.00]结尾")
            Catalog.invalidate()
            assertEquals("原句", Catalog.find(context, "目录省电验证", "A", null)!!.song.lyrics!!.first().text)
            file.writeText("[ti:目录省电验证]\n[ar:A]\n[00:01.00]改过的句子\n[00:03.00]结尾")
            file.setLastModified(System.currentTimeMillis() + 2000); Catalog.invalidate()
            assertEquals("改过的句子", Catalog.find(context, "目录省电验证", "A", null)!!.song.lyrics!!.first().text)
            File(folder, "other.lrc").writeText("[ti:目录省电验证]\n[ar:B]\n[00:01.00]另一个歌手\n[00:03.00]结尾")
            Catalog.invalidate()
            assertNull(Catalog.find(context, "目录省电验证", null, null))
            assertEquals("B", Catalog.find(context, "目录省电验证", "B", null)!!.song.artist)
            file.delete(); Catalog.invalidate()
            assertNull(Catalog.find(context, "目录省电验证", "A", null))
        } finally {
            folder.listFiles().orEmpty().forEach { it.delete() }; folder.delete()
            prefs.edit().apply { if (previous == null) remove("settings") else putString("settings", previous) }.commit()
            Catalog.invalidate()
        }
    }
}
