package io.github.proify.lyricon.localprovider

import android.content.Context
import android.content.ContextWrapper
import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.MatrixCursor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewAction
import androidx.test.espresso.UiController
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.WindowCompat
import com.kyant.taglib.TagLib
import io.github.proify.lyricon.localprovider.data.*
import io.github.proify.lyricon.localprovider.search.SearchCandidate
import io.github.proify.lyricon.localprovider.ui.ProviderTheme
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import kotlinx.serialization.encodeToString
import org.hamcrest.Matchers.allOf
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** End-to-end regressions which need a real window, Android resources or native audio metadata. */
class RelaxedLayoutDeviceTest {
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrument.targetContext
    private fun activate() = object : ViewAction {
        override fun getDescription() = "activate the visible control without OEM touch-injection restrictions"
        override fun getConstraints() = isDisplayed()
        override fun perform(controller: UiController, view: View) { check(view.performClick()); controller.loopMainThreadUntilIdle() }
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun screenshot(name: String) {
        instrument.waitForIdleSync()
        Thread.sleep(250) // Wait for SurfaceFlinger to present the new page/theme frame.
        val folder = File(context.getExternalFilesDir(null), "device-verification").apply { mkdirs() }
        val bitmap = instrument.uiAutomation.takeScreenshot()
        File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun coldLaunchShowsTheCombinedCardAndNavigationWithoutCrashing() {
        val prefs = context.getSharedPreferences("local_provider_ui", 0)
        val old = listOf("snapshot", "events", "choices").associateWith { prefs.getString(it, null) }
        val song = Song(name = "舒展卡片实机验证", artist = "测试歌手", lyrics = listOf(
            RichLyricLine(begin = 1000, end = 5000, text = "风轻轻地吹过", roma = "Kaze ga sotto tōrisugiru", translation = "实机验证译文")))
        ProviderStore(context).report(RuntimeSnapshot(player = "verification", updatedAt = System.currentTimeMillis(),
            song = song, source = "Embedded", message = "歌词已匹配"))
        val activity = launchTestActivity()
        try {
            instrument.runOnMainSync {
                val hero = activity.window.decorView.findViewWithTag<ViewGroup>("currentSongHero")
                assertNotNull(hero)
                assertTrue(hero.width > 0 && hero.height > 0)
                val content = views(hero).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(content.containsAll(listOf(song.name, "风轻轻地吹过", "实机验证译文")))
                val cover = hero.findViewWithTag<View>("songCover")
                assertEquals((62 * context.resources.displayMetrics.density).toInt(), cover.width)
            }
            screenshot("relaxed-home")
            onView(allOf(withText("搜索"), isAssignableFrom(Button::class.java))).perform(activate())
            screenshot("relaxed-search")
            onView(allOf(withText("歌词库"), isAssignableFrom(Button::class.java))).perform(activate())
            onView(allOf(withText("设置"), isAssignableFrom(Button::class.java))).perform(activate())
            screenshot("relaxed-settings")
        } finally {
            instrument.runOnMainSync { activity.finish() }; instrument.waitForIdleSync()
            prefs.edit().apply { old.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) } }.commit()
        }
    }
    @Test fun actualResourcesUseTheSelectedMonetSeedInLightAndDarkModes() {
        val prefs = context.getSharedPreferences("provider_appearance", 0)
        val previous = prefs.all.toMap()
        try {
            listOf("light", "dark").forEach { mode ->
                prefs.edit().putString("mode", mode).putBoolean("monet", true).putInt("seed", ProviderTheme.seeds.getValue("绿色")).commit()
                val activity = launchTestActivity()
                try {
                    instrument.runOnMainSync {
                        val colors = ProviderTheme.colors(activity)
                        assertEquals(colors.primary, activity.getColor(R.color.provider_primary))
                        assertEquals(colors.background, activity.getColor(R.color.provider_background))
                        assertEquals(colors.surface, activity.getColor(R.color.provider_surface))
                        assertEquals(colors.popup, activity.getColor(R.color.provider_popup))
                        assertEquals(colors.primary, com.google.android.material.color.MaterialColors.getColor(activity,
                            androidx.appcompat.R.attr.colorPrimary, 0))
                        assertEquals(mode == "light", WindowCompat.getInsetsController(activity.window,
                            activity.window.decorView).isAppearanceLightStatusBars)
                    }
                    screenshot("monet-green-$mode")
                } finally { instrument.runOnMainSync { activity.finish() }; instrument.waitForIdleSync() }
            }
        } finally {
            prefs.edit().clear().apply {
                previous.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value); is Boolean -> putBoolean(key, value); is Int -> putInt(key, value)
                } }
            }.commit()
        }
    }
    @Test fun sixthDirectoryIncludesNativeEmbeddedLyricsAndSidecarsButSeventhDoesNot() {
        val prefs = context.getSharedPreferences("local_provider_ui", 0)
        val oldSettings = prefs.getString("settings", null)
        val root = File.createTempFile("six-level-verification-", "", context.cacheDir).apply { delete(); mkdir() }
        try {
            var sixth = root
            repeat(6) { sixth = File(sixth, "level${it + 1}").apply { mkdir() } }
            val seventh = File(sixth, "level7").apply { mkdir() }
            File(sixth, "sidecar.lrc").writeText("[ti:六层独立歌词]\n[ar:Fixture]\n[00:01.000]第六层歌词\n[00:03.000]结束")
            File(seventh, "excluded.lrc").writeText("[ti:七层独立歌词]\n[00:01.000]不可索引")
            fun audio(file: File, title: String) {
                val buffer = ByteBuffer.allocate(44 + 16000).order(ByteOrder.LITTLE_ENDIAN)
                buffer.put("RIFF".toByteArray()).putInt(buffer.capacity() - 8).put("WAVEfmt ".toByteArray())
                    .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
                    .put("data".toByteArray()).putInt(16000)
                file.writeBytes(buffer.array())
                val tags = hashMapOf("TITLE" to arrayOf(title), "ARTIST" to arrayOf("Fixture"), "ALBUM" to arrayOf("Test Album"),
                    "LYRICS" to arrayOf("[00:01.000]<00:01.000>内<00:02.000>嵌<00:03.000>"))
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).use {
                    assertTrue("Failed to write the native audio fixture", TagLib.savePropertyMap(it.detachFd(), tags))
                }
            }
            audio(File(sixth, "embedded.wav"), "六层内嵌歌词")
            audio(File(seventh, "excluded.wav"), "七层内嵌歌词")
            val settings = ProviderStore(context).settings().copy(folders = listOf(LyricFolder(root.absolutePath, "Fixture")), files = emptyList())
            prefs.edit().putString("settings", ProviderStore.json.encodeToString(settings)).commit()
            val found = Catalog.scan(context, true)
            assertTrue(found.any { it.song.name == "六层独立歌词" })
            val embedded = found.single { it.song.name == "六层内嵌歌词" }
            assertTrue(embedded.wordLevel)
            assertEquals("Test Album", embedded.album)
            assertEquals("Embedded", SearchCandidate.local(embedded).source)
            assertFalse(found.any { it.song.name?.startsWith("七层") == true })

            // Exercise the document-tree path through Android's ContentResolver as well.
            // The test provider exposes only the temporary fixture, without changing user grants.
            val authority = "fixture.localprovider.documents"
            val provider = object : ContentProvider() {
                fun file(uri: Uri): File = File(root, DocumentsContract.getDocumentId(uri).removePrefix("root").trimStart('/'))
                override fun onCreate() = true
                override fun getType(uri: Uri) = if (file(uri).isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) =
                    MatrixCursor(requireNotNull(projection)).apply {
                        file(uri).listFiles().orEmpty().forEach { item ->
                            val id = "root/" + item.relativeTo(root).invariantSeparatorsPath
                            addRow(projection.map { column -> when (column) {
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME -> item.name
                                DocumentsContract.Document.COLUMN_MIME_TYPE -> if (item.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                                DocumentsContract.Document.COLUMN_LAST_MODIFIED -> item.lastModified()
                                DocumentsContract.Document.COLUMN_SIZE -> item.length()
                                else -> null
                            } })
                        }
                    }
                override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
                override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read-only fixture")
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("Read-only fixture")
                override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("Read-only fixture")
            }
            provider.attachInfo(context, ProviderInfo().apply { this.authority = authority; exported = false; grantUriPermissions = true })
            val resolver = ContentResolver.wrap(provider)
            val documentsContext = object : ContextWrapper(context) { override fun getContentResolver() = resolver }
            val tree = DocumentsContract.buildTreeDocumentUri(authority, "root")
            prefs.edit().putString("settings", ProviderStore.json.encodeToString(settings.copy(folders = listOf(LyricFolder(tree.toString(), "Fixture"))))).commit()
            val documents = Catalog.scan(documentsContext, true)
            assertTrue(documents.any { it.song.name == "六层独立歌词" })
            assertTrue(documents.single { it.song.name == "六层内嵌歌词" }.wordLevel)
            assertFalse(documents.any { it.song.name?.startsWith("七层") == true })
        } finally {
            prefs.edit().apply { if (oldSettings == null) remove("settings") else putString("settings", oldSettings) }.commit()
            Catalog.invalidate()
            check(root.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
            root.deleteRecursively()
        }
    }
}
