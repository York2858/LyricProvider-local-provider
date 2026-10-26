package io.github.proify.lyricon.localprovider

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.platform.app.InstrumentationRegistry
import io.github.proify.lyricon.localprovider.search.MatchRules
import io.github.proify.lyricon.localprovider.search.MatchTarget
import io.github.proify.lyricon.localprovider.search.SearchCandidate
import io.github.proify.lyricon.localprovider.ui.MatchScoreDialog
import io.github.proify.lyricon.lyric.model.Song
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MatchScoreDialogLayoutTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun screenshot(name: String) {
        Thread.sleep(300) // Wait for SurfaceFlinger to present the dialog or scroll change.
        val bitmap = instrument.uiAutomation.takeScreenshot()
        val folder = File(instrument.targetContext.getExternalFilesDir(null), "device-verification").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun narrowDialogKeepsLabelsReadableAndDismissVisibleWhenExpanded() {
        val activity = launchTestActivity()
        var dialog: AlertDialog? = null
        try {
            for (fontScale in listOf(1f, 1.5f)) {
                instrument.runOnMainSync {
                    val configuration = Configuration(activity.resources.configuration).apply { this.fontScale = fontScale }
                    val context = ContextThemeWrapper(activity, R.style.Theme_LyricProvider).apply {
                        applyOverrideConfiguration(configuration)
                    }
                    val candidate = SearchCandidate("layout", "fixture", Song(name = "A long song title (Live Version)",
                        artist = "Artist", duration = 245000), album = "A long album name")
                    dialog = MatchScoreDialog.show(context, MatchTarget("A long song title", "Artist", "Album", 240000),
                        candidate, "Apple Music", MatchRules())
                    dialog.window!!.setLayout((320 * context.resources.displayMetrics.density).toInt(), -2)
                }
                instrument.waitForIdleSync()
                screenshot("match-score-$fontScale")
                instrument.runOnMainSync {
                    val decor = dialog!!.window!!.decorView
                    for (index in 0..3) {
                        val section = decor.findViewWithTag<ViewGroup>("matchScoreSection:$index")
                        val label = descendants(section).filterIsInstance<TextView>().first()
                        assertEquals("Score field labels must not wrap at font scale $fontScale", 1, label.lineCount)
                        assertTrue(label.width >= label.paint.measureText(label.text.toString()))
                    }
                    val header = decor.findViewWithTag<ViewGroup>("matchScoreSection:3")
                    header.requestRectangleOnScreen(android.graphics.Rect(0, 0, header.width, header.height), true)
                }
                instrument.waitForIdleSync()
                screenshot("match-score-fields-$fontScale")
                instrument.runOnMainSync {
                    val decor = dialog!!.window!!.decorView
                    val header = decor.findViewWithTag<ViewGroup>("matchScoreSection:3")
                    val bounds = android.graphics.Rect()
                    assertTrue(header.getGlobalVisibleRect(bounds))
                    assertEquals(header.height, bounds.height())
                    header.performClick()
                    assertEquals(View.VISIBLE, decor.findViewWithTag<View>("matchScorePartBody:3").visibility)
                }
                instrument.waitForIdleSync()
                instrument.runOnMainSync {
                    val decor = dialog!!.window!!.decorView
                    val dismiss = descendants(decor).filterIsInstance<TextView>().single { it.text.toString() == "知道了" }
                    val bounds = android.graphics.Rect()
                    assertTrue("Dismiss button must remain fully visible", dismiss.getGlobalVisibleRect(bounds))
                    assertEquals(dismiss.height, bounds.height())
                    decor.findViewWithTag<View>("matchScoreSection:rules").performClick()
                    assertEquals(View.GONE, decor.findViewWithTag<View>("matchScorePartBody:3").visibility)
                    assertEquals(View.VISIBLE, decor.findViewWithTag<View>("matchScoreRulesBody").visibility)
                    dialog.dismiss()
                }
            }
        } finally {
            instrument.runOnMainSync { dialog?.dismiss(); activity.finish() }
            instrument.waitForIdleSync()
        }
    }
}
