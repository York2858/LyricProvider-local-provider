package io.github.proify.lyricon.localprovider.lyrics

import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.localprovider.model.LyricsResult
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Locale

class CloudLyricsExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun lyrics() = LyricsResult(trackName = "测试歌曲", artistName = "歌手", albumName = "专辑", rich = listOf(
        RichLyricLine(begin = 7027, end = 9330, text = "君へ", roma = "kimi e", translation = "致你",
            words = listOf(LyricWord(begin = 7027, end = 8100, text = "君"), LyricWord(begin = 8100, end = 9330, text = "へ")))))
    private fun audio() = temporary.newFile("song.flac")
    @Test fun realTextAndMillisecondTimingsRoundTripWithRomaAndTranslation() {
        val encoded = EnhancedLrcEncoder.encode(lyrics())!!
        assertTrue(encoded.contains("[00:07.027]<00:07.027>君<00:08.100>へ<00:09.330>"))
        val parsed = LyricNormalizer.normalizeForTripleLine(EnhanceLrcParser.parse(encoded).lines).single()
        assertEquals("君へ", parsed.text); assertEquals("kimi e", parsed.roma); assertEquals("致你", parsed.translation)
        assertEquals(7027L, parsed.begin); assertEquals(9330L, parsed.end)
        assertEquals(listOf(7027L, 8100L), parsed.words!!.map { it.begin })
    }
    @Test fun timestampsAlwaysUseAsciiDigitsRegardlessOfPhoneLocale() {
        val previous = Locale.getDefault()
        try { Locale.setDefault(Locale("ar", "EG")); assertTrue(EnhancedLrcEncoder.encode(lyrics())!!.contains("[00:07.027]")) }
        finally { Locale.setDefault(previous) }
    }
    @Test fun wordsWithoutLineTextStillProduceOriginalLyrics() {
        val data = lyrics().copy(rich = lyrics().rich.map { it.copy(text = null) })
        assertTrue(EnhancedLrcEncoder.encode(data)!!.contains("君<00:08.100>へ"))
    }
    @Test fun missingWordTextUsesTheOriginalLineInsteadOfEmptyWordMarkers() {
        val data = lyrics().copy(rich = listOf(RichLyricLine(begin = 1000, end = 3000, text = "原文还在",
            words = listOf(LyricWord(begin = 1000, end = 3000, text = "")))))
        assertTrue(EnhancedLrcEncoder.encode(data)!!.contains("[00:01.000]原文还在[00:03.000]"))
    }
    @Test fun metadataOnlyOrAuxiliaryOnlyResultsCannotCreateALyricFile() {
        val file = audio()
        for (data in listOf(LyricsResult(trackName = "标题"), LyricsResult(rich = listOf(
            RichLyricLine(begin = 1000, end = 3000, roma = "roma", translation = "只有翻译"))))) {
            assertFalse(CloudLyricsFileSaver.save(file, data, false))
            assertFalse(File(file.parentFile, "song.lrc").exists())
        }
    }
    @Test fun emptyAndLegacyTimestampOnlyFilesAreRepairedWithOverwriteDisabled() {
        val file = audio(); val target = File(file.parentFile, "song.lrc")
        for (bad in listOf("", "  ", "[ti:测试歌曲]\n[ar:歌手]", "[ti:测试歌曲]\n[00:00.000] <00:00.000>v1:<00:00.000>")) {
            target.writeText(bad)
            assertTrue(CloudLyricsFileSaver.save(file, lyrics(), false))
            assertTrue(target.readText().contains("君"))
        }
    }
    @Test fun validOrUnknownExistingContentIsPreservedUnlessOverwriteIsEnabled() {
        val file = audio(); val target = File(file.parentFile, "song.lrc")
        for (valid in listOf("[00:00.000]有效原文", "用户手工保存的内容", "[00:01.000]♪", "[00:01.000]<brackets>")) {
            target.writeText(valid)
            assertFalse(CloudLyricsFileSaver.save(file, lyrics(), false)); assertEquals(valid, target.readText())
        }
        assertTrue(CloudLyricsFileSaver.save(file, lyrics(), true))
        assertTrue(target.readText().contains("君"))
    }
    @Test fun failedOrIncompleteWriteKeepsOldFileAndRemovesTemporaryFiles() {
        val folder = temporary.newFolder(); val target = File(folder, "song.lrc").apply { writeText("[00:01.000]旧歌词") }
        for (writer in listOf<(File, ByteArray) -> Unit>(
            { file, _ -> file.writeText("partial"); throw IOException("disk full") },
            { file, _ -> file.writeText("partial") })) {
            try { AtomicLyricFile.save(target, EnhancedLrcEncoder.encode(lyrics())!!, true, writer); fail("must reject failed write") }
            catch (_: IOException) { }
            assertEquals("[00:01.000]旧歌词", target.readText())
            assertEquals(listOf("song.lrc"), folder.list()!!.toList())
        }
    }
    @Test fun failedNewWriteDoesNotLeaveAnEmptyDestination() {
        val folder = temporary.newFolder(); val target = File(folder, "new.lrc")
        try { AtomicLyricFile.save(target, "[00:01.000]原文", false) { _, _ -> throw IOException("write denied") }; fail() }
        catch (_: IOException) { }
        assertFalse(target.exists()); assertTrue(folder.list()!!.isEmpty())
    }
    @Test fun validFileCreatedDuringPreparationIsNotOverwritten() {
        val folder = temporary.newFolder(); val target = File(folder, "song.lrc")
        assertFalse(AtomicLyricFile.save(target, "[00:01.000]新歌词", false) { file, bytes ->
            file.writeBytes(bytes); target.writeText("[00:01.000]其他请求保存的歌词")
        })
        assertEquals("[00:01.000]其他请求保存的歌词", target.readText())
        assertEquals(listOf("song.lrc"), folder.list()!!.toList())
    }
    @Test fun duetAndWordGapsKeepTheirRoleAndActualWordEnd() {
        val data = lyrics().copy(rich = listOf(
            RichLyricLine(begin = 1000, end = 4000, text = "甲乙", words = listOf(
                LyricWord(begin = 1000, end = 1500, text = "甲"), LyricWord(begin = 2500, end = 4000, text = "乙"))),
            RichLyricLine(begin = 5000, end = 7000, text = "丙丁", isAlignedRight = true,
                words = listOf(LyricWord(begin = 5000, end = 7000, text = "丙丁")))))
        val raw = EnhancedLrcEncoder.encode(data)!!
        assertTrue(raw.contains("<00:01.000>v1:甲<00:01.500><00:02.500>乙"))
        val parsed = EnhanceLrcParser.parse(raw).lines
        assertEquals(1500L, parsed.first().words!!.first().end)
        assertTrue(parsed.last().isAlignedRight)
    }
}
