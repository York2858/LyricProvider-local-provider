package io.github.proify.lyricon.localprovider

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

class CloudLyricsReleaseExportTest {
    @Test fun installedExporterSavesRealWordsAndRepairsBrokenFilesWithObfuscatedModels() {
        verify(InstrumentationRegistry.getInstrumentation().targetContext)
    }
    fun verify(context: android.content.Context) {
        val entry = context.classLoader.loadClass("io.github.proify.lyricon.localprovider.lyrics.CloudLyricsFileSaver")
        val method = entry.methods.single { it.name == "save" && it.parameterTypes.size == 3 && Modifier.isStatic(it.modifiers) }
        val model = method.parameterTypes[1]
        val companionField = model.declaredFields.single { it.name == "Companion" }.apply { isAccessible = true }
        val companion = companionField.get(null)
        val serializer = companion.javaClass.methods.single {
            it.parameterTypes.isEmpty() && KSerializer::class.java.isAssignableFrom(it.returnType)
        }.invoke(companion) as DeserializationStrategy<Any>
        val json = """{"trackName":"正式包导出验证","artistName":"Fixture","rich":[{"begin":7027,"end":9330,"text":"君へ","roma":"kimi e","translation":"致你","words":[{"begin":7027,"end":8100,"text":"君"},{"begin":8100,"end":9330,"text":"へ"}]}]}"""
        val result = Json { ignoreUnknownKeys = true }.decodeFromString(serializer, json)
        // Exercise both internal storage and the emulated external filesystem used by Music.
        val bases = listOf(context.cacheDir, requireNotNull(context.getExternalFilesDir(null)))
        bases.forEach { base ->
        val folder = File(base, "release-lyric-export-fixture").apply { mkdirs() }
        val audio = File(folder, "song.flac").apply { writeText("fixture") }
        val target = File(folder, "song.lrc")
        try {
            target.writeText("[ti:旧文件]\n[00:00.000] <00:00.000><00:00.000>")
            assertEquals(true, method.invoke(null, audio, result, false))
            val raw = target.readText()
            assertTrue(raw.contains("[00:07.027]<00:07.027>君<00:08.100>へ<00:09.330>"))
            assertTrue(raw.contains("kimi e")); assertTrue(raw.contains("致你"))
            assertFalse(raw.contains("[00:00.000]"))
            target.writeText("[00:01.000]用户已有歌词")
            assertEquals(false, method.invoke(null, audio, result, false))
            assertEquals("[00:01.000]用户已有歌词", target.readText())
            target.writeText("")
            assertEquals(true, method.invoke(null, audio, result, false))
            assertTrue(target.readText().contains("君"))
            assertTrue(folder.listFiles()!!.none { it.name.endsWith(".tmp") })
        } finally { folder.listFiles().orEmpty().forEach { it.delete() }; folder.delete() }
        }
    }
}
