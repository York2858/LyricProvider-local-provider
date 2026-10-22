/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lrckit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class EnhanceLrcParserTest {

    @Test
    @DisplayName("解析标准LRC格式 - 验证基础时间轴和文本")
    fun testParseStandardLrc() {
        val lrc = """
            [ti:测试歌曲]
            [00:10.20]第一行歌词
            [00:12.50]第二行歌词
        """.trimIndent()

        val doc = EnhanceLrcParser.parse(lrc)

        assertEquals("测试歌曲", doc.metadata["ti"])
        assertEquals(2, doc.lines.size)
        assertEquals(10200L, doc.lines[0].begin)
        assertEquals("第一行歌词", doc.lines[0].text)
        assertEquals(12500L, doc.lines[1].begin)
    }

    @Test
    @DisplayName("解析复杂正文 - 验证包含[]和/的内容")
    fun testParseTextWithBrackets() {
        val lrc = """
            [00:32.99]【/Prologue】
            [00:33.11]何時[人類]向天空築起了[巴別塔]
            [01:15.54][天才]咬下了「苹果」
        """.trimIndent()

        val doc = EnhanceLrcParser.parse(lrc)

        assertEquals(3, doc.lines.size)
        assertEquals("【/Prologue】", doc.lines[0].text)
        assertEquals("何時[人類]向天空築起了[巴別塔]", doc.lines[1].text)
        assertEquals("[天才]咬下了「苹果」", doc.lines[2].text)
        assertEquals(75540L, doc.lines[2].begin)
    }

    @Test
    @DisplayName("解析多时间戳行 - 验证同一行歌词对应多个时间点")
    fun testParseMultipleTags() {
        val lrc = "[00:10.00][00:20.00]重复的歌词"
        val doc = EnhanceLrcParser.parse(lrc)

        // 应该解析为两条独立的行，时间不同但文本相同
        assertEquals(2, doc.lines.size)
        assertEquals(10000L, doc.lines[0].begin)
        assertEquals(20000L, doc.lines[1].begin)
        assertEquals("重复的歌词", doc.lines[0].text)
        assertEquals("重复的歌词", doc.lines[1].text)
    }

    @Test
    @DisplayName("解析逐字时间轴 - 验证LyricWord解析")
    fun testParseKaraokeWords() {
        val lrc = "[00:10.00]<00:10.00>Hello <00:10.50>World"
        val doc = EnhanceLrcParser.parse(lrc)

        val line = doc.lines[0]
        assertNotNull(line.words)
        assertEquals(2, line.words?.size)
        assertEquals("Hello ", line.words?.get(0)?.text)
        assertEquals(500L, line.words?.get(0)?.duration)
        assertEquals("World", line.words?.get(1)?.text)
    }

    @Test
    @DisplayName("解析角色标签 - 验证主唱与背景音逻辑")
    fun testParseRolesAndAlignment() {
        val lrc = """
            [00:10.00]v1: 主唱内容
            [00:12.00]bg: 背景伴唱
        """.trimIndent()

        val doc = EnhanceLrcParser.parse(lrc)

        // 第一行作为主角色，默认不靠右
        assertEquals("主唱内容", doc.lines[0].text)
        assertFalse(doc.lines[0].isAlignedRight)

        // bg 角色应该被识别为靠右
        assertEquals("背景伴唱", doc.lines[1].text)
        assertTrue(doc.lines[1].isAlignedRight)
    }

    @Test
    @DisplayName("解析独立bg元数据 - 验证与上一行合并")
    fun testHandleBackgroundMeta() {
        val lrc = """
            [00:10.00]Hello World
            [bg: (Background Voice)]
        """.trimIndent()

        val doc = EnhanceLrcParser.parse(lrc)

        assertEquals(1, doc.lines.size)
        val line = doc.lines[0]
        assertEquals("Hello World", line.text)
        assertEquals("(Background Voice)", line.secondary)
    }

    @Test
    @DisplayName("验证时间补全 - 确保最后一行有duration")
    fun testFinalizeDuration() {
        val totalDuration = 60000L
        val lrc = "[00:50.00]最后一行"

        val doc = EnhanceLrcParser.parse(lrc, duration = totalDuration)

        val lastLine = doc.lines.last()
        assertEquals(50000L, lastLine.begin)
        assertEquals(60000L, lastLine.end)
        assertEquals(10000L, lastLine.duration)
    }

    @Test
    @DisplayName("验证Offset偏移量 - 验证整体时间移动")
    fun testApplyOffset() {
        val lrc = """
            [offset:1000]
            [00:10.00]歌词
        """.trimIndent()

        val doc = EnhanceLrcParser.parse(lrc)

        // offset 为正表示整体延迟，即时间戳变大
        assertEquals(11000L, doc.lines[0].begin)
    }

    @Test
    @DisplayName("解析分段双语逐字歌词 - 同时间戳应合并主副轨")
    fun testParseSeparatedBilingualWordLyrics() {
        val lrc = """
            [00:09.16]<00:09.16>Did <00:09.54>you <00:10.26>look <00:10.84>outside <00:11.84>the <00:12.20>window?<00:14.45>
            [00:17.44]<00:17.44>Did <00:17.82>it <00:18.42>make <00:18.93>you <00:19.37>think <00:20.08>of <00:20.42>me?<00:22.61>
            [00:09.16]<00:09.16>你可曾望向窗外？<00:14.45>
            [00:17.44]<00:17.44>那景象是否让你想起了我？<00:22.61>
        """.trimIndent()

        val doc = EnhanceLrcParser.parse(lrc)

        assertEquals(2, doc.lines.size)
        assertEquals("Did you look outside the window?", doc.lines[0].text)
        assertEquals("你可曾望向窗外？", doc.lines[0].secondary)
        assertEquals("Did it make you think of me?", doc.lines[1].text)
        assertEquals("那景象是否让你想起了我？", doc.lines[1].secondary)
    }
    @Test
    @DisplayName("English words keep spaces and CJK stays compact")
    fun testWordSpacingByLanguage() {
        val lrc = listOf(
            "[00:10.00]<00:10.00>Do<00:10.20>you<00:10.40>love<00:10.60>the<00:10.80>rain,<00:11.00>does<00:11.20>it<00:11.40>make<00:11.60>you<00:11.80>dance, <00:12.00>",
            "[00:20.00]<00:20.00>\u4f60<00:20.20>\u597d<00:20.40>\u554a<00:20.60>",
            "[00:30.00]<00:30.00>\uc548<00:30.20>\ub155<00:30.40>\ud574<00:30.60>"
        ).joinToString("\n")

        val doc = EnhanceLrcParser.parse(lrc)

        assertEquals("Do you love the rain, does it make you dance,", doc.lines[0].text)
        assertEquals("\u4f60\u597d\u554a", doc.lines[1].text)
        assertEquals("\uc548\ub155\ud574", doc.lines[2].text)
    }

    @Test
    @DisplayName("Two-line English word lyrics keep spaces after translation merge")
    fun testTwoLineEnglishWordLyricsKeepSpacesAfterMerge() {
        val lrc = listOf(
            "[00:07.027] <00:07.027>Some<00:07.353>days<00:07.616>you're<00:07.833>the<00:08.039>only<00:08.355>thing<00:08.646>I<00:08.860>know <00:09.330>",
            "[00:07.027]\u6709\u4e9b\u65e5\u5b50 \u4f60\u662f\u6211\u552f\u4e00\u4e86\u89e3\u7684\u4e8b\u60c5[00:09.330]",
            "[00:10.566] <00:10.566>Only<00:10.948>thing<00:11.248>that's<00:11.476>burning<00:11.927>when<00:12.128>the<00:12.358>nights<00:12.782>grow<00:13.177>cold <00:13.566>",
            "[00:10.566]\u5f53\u591c\u665a\u6e10\u51b7 \u4f60\u662f\u552f\u4e00\u71c3\u70e7\u7684\u5149\u8292[00:13.566]",
            "[00:14.026] <00:14.026>Can't<00:14.437>look<00:14.712>away,<00:15.340>can't<00:15.765>look<00:16.046>away <00:16.706>",
            "[00:14.026]\u65e0\u6cd5\u79fb\u5f00\u89c6\u7ebf \u65e0\u6cd5\u79fb\u5f00\u89c6\u7ebf[00:16.706]"
        ).joinToString("\n")

        val doc = EnhanceLrcParser.parse(lrc)

        assertEquals(3, doc.lines.size)
        assertEquals("Some days you're the only thing I know", doc.lines[0].text)
        assertEquals("\u6709\u4e9b\u65e5\u5b50 \u4f60\u662f\u6211\u552f\u4e00\u4e86\u89e3\u7684\u4e8b\u60c5", doc.lines[0].secondary)
        assertEquals("Only thing that's burning when the nights grow cold", doc.lines[1].text)
        assertEquals("\u5f53\u591c\u665a\u6e10\u51b7 \u4f60\u662f\u552f\u4e00\u71c3\u70e7\u7684\u5149\u8292", doc.lines[1].secondary)
        assertEquals("Can't look away, can't look away", doc.lines[2].text)
        assertEquals("\u65e0\u6cd5\u79fb\u5f00\u89c6\u7ebf \u65e0\u6cd5\u79fb\u5f00\u89c6\u7ebf", doc.lines[2].secondary)
    }

    @Test
    @DisplayName("Only the first line in a same-time group participates in duet role detection")
    fun testSameTimeAuxiliaryLinesDoNotAffectRoleDetection() {
        val lrc = """
            [00:10.00]v1: Main lyric
            [00:10.00]v2: Roma lyric
            [00:10.00]v2: Translation lyric
            [00:12.00]Still main lyric
        """.trimIndent()

        val doc = EnhanceLrcParser.parse(lrc)

        assertEquals(2, doc.lines.size)
        assertEquals("Main lyric", doc.lines[0].text)
        assertEquals("Roma lyric", doc.lines[0].secondary)
        assertEquals("Translation lyric", doc.lines[0].translation)
        assertFalse(doc.lines[0].isAlignedRight)
        assertEquals("Still main lyric", doc.lines[1].text)
        assertFalse(doc.lines[1].isAlignedRight)
    }

    @Test
    @DisplayName("Same-time translation-first groups still keep roma in secondary and translation in translation")
    fun testSameTimeTranslationFirstKeepsSemanticAuxiliarySlots() {
        val lrc = """
            [00:08.00]v1: Anchor lyric
            [00:10.00]v2: Main lyric
            [00:10.00]v2: 翻译歌词
            [00:10.00]v2: roma lyric
            [00:12.00]v1: Main lyric two
        """.trimIndent()

        val doc = EnhanceLrcParser.parse(lrc)

        assertEquals(3, doc.lines.size)
        assertEquals("Anchor lyric", doc.lines[0].text)
        assertFalse(doc.lines[0].isAlignedRight)
        assertEquals("Main lyric", doc.lines[1].text)
        assertEquals("roma lyric", doc.lines[1].secondary)
        assertEquals("翻译歌词", doc.lines[1].translation)
        assertTrue(doc.lines[1].isAlignedRight)
        assertEquals("Main lyric two", doc.lines[2].text)
        assertFalse(doc.lines[2].isAlignedRight)
    }

    @Test
    @DisplayName("Timed invisible duet role prefixes are stripped from word-level lyrics")
    fun testTimedInvisibleRolePrefixIsRemovedFromWordLyrics() {
        val lrc = """
            [01:20.946] <01:20.946>v2:Ooh,<01:22.199>ooh <01:23.426>
            [01:20.946]v2:噢 噢[01:23.426]
            [01:25.193] <01:25.193>v1:Ooh,<01:26.734>lost,<01:28.826>lost<01:29.293>in<01:29.960>the<01:30.128>words<01:30.461>that<01:30.861>we<01:31.226>scream <01:32.431>
            [01:25.193]v1:噢 迷失 迷失在我们呐喊的言语中[01:32.431]
        """.trimIndent()

        val doc = EnhanceLrcParser.parse(lrc)

        assertEquals(2, doc.lines.size)

        val first = doc.lines[0]
        assertEquals("Ooh, ooh", first.text)
        assertEquals("噢 噢", first.secondary)
        assertFalse(first.isAlignedRight)
        assertEquals("Ooh,ooh ", first.words?.joinToString(separator = "") { it.text.orEmpty() })

        val second = doc.lines[1]
        assertEquals("Ooh, lost, lost in the words that we scream", second.text)
        assertEquals("噢 迷失 迷失在我们呐喊的言语中", second.secondary)
        assertTrue(second.isAlignedRight)
        assertEquals(
            "Ooh,lost,lostinthewordsthatwescream ",
            second.words?.joinToString(separator = "") { it.text.orEmpty() }
        )
    }

    @Test
    @DisplayName("Plain duet role prefixes like 合: are stripped from non-word lyrics")
    fun testPlainInvisibleRolePrefixIsRemoved() {
        val lrc = """
            [01:37.994]合:因为你已知晓你对我有多重要[01:41.240]
            [01:42.000]合：我们一起唱
        """.trimIndent()

        val doc = EnhanceLrcParser.parse(lrc)

        assertEquals(2, doc.lines.size)
        assertEquals("因为你已知晓你对我有多重要", doc.lines[0].text)
        assertFalse(doc.lines[0].isAlignedRight)
        assertEquals("我们一起唱", doc.lines[1].text)
        assertFalse(doc.lines[1].isAlignedRight)
    }
}
