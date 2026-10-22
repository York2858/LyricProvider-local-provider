package io.github.proify.lyricon.localprovider.xposed

import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalProviderTripleLineTest {

    @Test
    fun normalizeForTripleLine_mergesMainRomaAndTranslationFromRawLrc() {
        val raw = listOf(
            "[00:02.256] <00:02.256>\u300c<00:02.425>\u304a<00:02.568>\u306f<00:02.720>\u3088<00:03.896>\u671d<00:04.210>\u3060<00:04.352>\u3088 <00:06.152>",
            "[00:02.256] <00:02.256>\u300c <00:02.424>o <00:02.568>ha <00:02.720>yo <00:03.895>a <00:04.056>sa <00:04.210>da <00:04.352>yo  <00:06.151>",
            "[00:02.256]\u201c\u65e9\u4e0a\u597d \u5230\u65e9\u4e0a\u4e86\u54e6[00:06.110]",
            "[00:06.153] <00:06.153>\u671d<00:06.429>\u3063<00:06.632>\u3066<00:06.763>\u3044<00:06.911>\u3046<00:07.063>\u304b<00:07.152>\u3082<00:07.296>\u3046<00:07.440>\u663c<00:07.763>\u3060<00:07.921>\u3051<00:08.081>\u3069 <00:12.062>",
            "[00:06.153] <00:06.152>a <00:06.290>sa <00:06.428>'t <00:06.631>te <00:06.763>i <00:06.910>u <00:07.063>ka <00:07.151>mo <00:07.296>u <00:07.440>hi <00:07.599>ru <00:07.763>da <00:07.921>ke <00:08.081>do  <00:12.060>",
            "[00:06.153]\u8bf4\u662f\u65e9\u4e0a\u5176\u5b9e\u5df2\u7ecf\u65e5\u4e0a\u4e09\u7aff\u5566[00:12.080]"
        ).joinToString("\n")

        val parsed = EnhanceLrcParser.parse(raw).lines
        val normalized = io.github.proify.lyricon.localprovider.lyrics.LyricNormalizer.normalizeForTripleLine(parsed)

        assertEquals(2, normalized.size)

        val line1 = normalized[0]
        assertEquals(2256L, line1.begin)
        assertEquals(6152L, line1.end)
        assertEquals("\u300c\u304a\u306f\u3088\u671d\u3060\u3088", compact(line1.text))
        assertEquals("\u300cohayoasadayo", compact(line1.secondary))
        assertEquals("\u201c\u65e9\u4e0a\u597d\u5230\u65e9\u4e0a\u4e86\u54e6", compact(line1.translation))
        assertNotNull(line1.secondaryWords)
        assertNull(line1.translationWords)
        assertTrue(line1.words.orEmpty().isNotEmpty())
        assertEquals("\u300c\u304a\u306f\u3088\u671d\u3060\u3088", compact(wordsText(line1.words)))
        assertEquals("\u300cohayoasadayo", compact(wordsText(line1.secondaryWords)))

        val line2 = normalized[1]
        assertEquals(6153L, line2.begin)
        assertEquals(12080L, line2.end)
        assertEquals("\u671d\u3063\u3066\u3044\u3046\u304b\u3082\u3046\u663c\u3060\u3051\u3069", compact(line2.text))
        assertEquals("asa'tteiukamouhirudakedo", compact(line2.secondary))
        assertEquals("\u8bf4\u662f\u65e9\u4e0a\u5176\u5b9e\u5df2\u7ecf\u65e5\u4e0a\u4e09\u7aff\u5566", compact(line2.translation))
        assertNotNull(line2.secondaryWords)
        assertNull(line2.translationWords)
        assertTrue(line2.words.orEmpty().isNotEmpty())
        assertEquals(
            "\u671d\u3063\u3066\u3044\u3046\u304b\u3082\u3046\u663c\u3060\u3051\u3069",
            compact(wordsText(line2.words))
        )
        assertEquals("asa'tteiukamouhirudakedo", compact(wordsText(line2.secondaryWords)))
    }

    @Test
    fun normalizeForTripleLine_usesSecondLineWhenOnlyTranslationExists() {
        val lines = listOf(
            RichLyricLine(
                begin = 1000,
                end = 2000,
                duration = 1000,
                text = "\u671d\u3063\u3066\u3044\u3046\u304b\u3082\u3046\u663c\u3060\u3051\u3069"
            ),
            RichLyricLine(
                begin = 1000,
                end = 2000,
                duration = 1000,
                text = "\u8bf4\u662f\u65e9\u4e0a\u5176\u5b9e\u5df2\u7ecf\u65e5\u4e0a\u4e09\u7aff\u5566"
            )
        )

        val normalized = io.github.proify.lyricon.localprovider.lyrics.LyricNormalizer.normalizeForTripleLine(lines)

        assertEquals(1, normalized.size)

        val line = normalized.first()
        assertEquals("\u671d\u3063\u3066\u3044\u3046\u304b\u3082\u3046\u663c\u3060\u3051\u3069", line.text)
        assertEquals("\u8bf4\u662f\u65e9\u4e0a\u5176\u5b9e\u5df2\u7ecf\u65e5\u4e0a\u4e09\u7aff\u5566", line.translation)
        assertNull(line.roma)
        assertNull(line.secondaryWords)
        assertNull(line.translationWords)
    }

    @Test
    fun normalizeForTripleLine_keepsSpacesForEnglishWordLyricsWithTranslation() {
        val raw = listOf(
            "[00:07.027] <00:07.027>Some<00:07.353>days<00:07.616>you're<00:07.833>the<00:08.039>only<00:08.355>thing<00:08.646>I<00:08.860>know <00:09.330>",
            "[00:07.027]\u6709\u4e9b\u65e5\u5b50 \u4f60\u662f\u6211\u552f\u4e00\u4e86\u89e3\u7684\u4e8b\u60c5[00:09.330]",
            "[00:10.566] <00:10.566>Only<00:10.948>thing<00:11.248>that's<00:11.476>burning<00:11.927>when<00:12.128>the<00:12.358>nights<00:12.782>grow<00:13.177>cold <00:13.566>",
            "[00:10.566]\u5f53\u591c\u665a\u6e10\u51b7 \u4f60\u662f\u552f\u4e00\u71c3\u70e7\u7684\u5149\u8292[00:13.566]",
            "[00:14.026] <00:14.026>Can't<00:14.437>look<00:14.712>away,<00:15.340>can't<00:15.765>look<00:16.046>away <00:16.706>",
            "[00:14.026]\u65e0\u6cd5\u79fb\u5f00\u89c6\u7ebf \u65e0\u6cd5\u79fb\u5f00\u89c6\u7ebf[00:16.706]"
        ).joinToString("\n")

        val parsed = EnhanceLrcParser.parse(raw).lines
        val normalized = io.github.proify.lyricon.localprovider.lyrics.LyricNormalizer.normalizeForTripleLine(parsed)

        assertEquals(3, normalized.size)

        val line1 = normalized[0]
        assertEquals("Some days you're the only thing I know", line1.text)
        assertEquals("\u6709\u4e9b\u65e5\u5b50 \u4f60\u662f\u6211\u552f\u4e00\u4e86\u89e3\u7684\u4e8b\u60c5", line1.translation)
        assertNull(line1.roma)
        assertTrue(line1.words.orEmpty().isNotEmpty())

        val line2 = normalized[1]
        assertEquals("Only thing that's burning when the nights grow cold", line2.text)
        assertEquals("\u5f53\u591c\u665a\u6e10\u51b7 \u4f60\u662f\u552f\u4e00\u71c3\u70e7\u7684\u5149\u8292", line2.translation)
        assertNull(line2.roma)
        assertTrue(line2.words.orEmpty().isNotEmpty())

        val line3 = normalized[2]
        assertEquals("Can't look away, can't look away", line3.text)
        assertEquals("\u65e0\u6cd5\u79fb\u5f00\u89c6\u7ebf \u65e0\u6cd5\u79fb\u5f00\u89c6\u7ebf", line3.translation)
        assertNull(line3.roma)
        assertTrue(line3.words.orEmpty().isNotEmpty())
    }

    @Test
    fun normalizeForTripleLine_preservesDuetAlignmentForTimedRolePrefixes() {
        val raw = listOf(
            "[01:20.946] <01:20.946>v2:Ooh,<01:22.199>ooh <01:23.426>",
            "[01:20.946]v2:\u5662 \u5662[01:23.426]",
            "[01:25.193] <01:25.193>v1:Ooh,<01:26.734>lost,<01:28.826>lost<01:29.293>in<01:29.960>the<01:30.128>words<01:30.461>that<01:30.861>we<01:31.226>scream <01:32.431>",
            "[01:25.193]v1:\u5662 \u8ff7\u5931 \u8ff7\u5931\u5728\u6211\u4eec\u5450\u558a\u7684\u8a00\u8bed\u4e2d[01:32.431]"
        ).joinToString("\n")

        val parsed = EnhanceLrcParser.parse(raw).lines
        val normalized = io.github.proify.lyricon.localprovider.lyrics.LyricNormalizer.normalizeForTripleLine(parsed)

        assertEquals(2, normalized.size)

        val first = normalized[0]
        assertEquals("Ooh, ooh", first.text)
        assertEquals("\u5662 \u5662", first.translation)
        assertNull(first.roma)
        assertTrue(first.words.orEmpty().isNotEmpty())
        assertTrue(!first.isAlignedRight)

        val second = normalized[1]
        assertEquals("Ooh, lost, lost in the words that we scream", second.text)
        assertEquals("\u5662 \u8ff7\u5931 \u8ff7\u5931\u5728\u6211\u4eec\u5450\u558a\u7684\u8a00\u8bed\u4e2d", second.translation)
        assertNull(second.roma)
        assertTrue(second.words.orEmpty().isNotEmpty())
        assertTrue(second.isAlignedRight)
    }

    @Test
    fun normalizeForTripleLine_preservesDuetAlignmentWhenRomaAndTranslationTravelTogether() {
        val raw = listOf(
            "[00:08.000]v1:\u3042\u3093\u304b\u30fc[00:09.000]",
            "[00:10.000] <00:10.000>v2:\u3053<00:10.400>\u3053 <00:11.000>",
            "[00:10.000]v2:\u8fd9\u91cc[00:11.000]",
            "[00:10.000]v2:koko[00:11.000]",
            "[00:12.000] <00:12.000>v1:\u3055<00:12.400>\u304f <00:13.000>",
            "[00:12.000]v1:\u54ed\u6ccc[00:13.000]",
            "[00:12.000]v1:saku[00:13.000]"
        ).joinToString("\n")

        val parsed = EnhanceLrcParser.parse(raw).lines
        val normalized = io.github.proify.lyricon.localprovider.lyrics.LyricNormalizer.normalizeForTripleLine(parsed)

        assertEquals(3, normalized.size)

        val anchor = normalized[0]
        assertEquals("\u3042\u3093\u304b\u30fc", compact(anchor.text))
        assertTrue(!anchor.isAlignedRight)

        val first = normalized[1]
        assertEquals("\u3053\u3053", compact(first.text))
        assertEquals("koko", compact(first.secondary))
        assertEquals("\u8fd9\u91cc", compact(first.translation))
        assertTrue(first.isAlignedRight)

        val second = normalized[2]
        assertEquals("\u3055\u304f", compact(second.text))
        assertEquals("saku", compact(second.secondary))
        assertEquals("\u54ed\u6ccc", compact(second.translation))
        assertTrue(!second.isAlignedRight)
    }

    @Test
    fun normalizeForTripleLine_doesNotTreatSpacedEnglishDisplayTextAsRoma() {
        val raw = listOf(
            "[00:09.862] <00:09.862>I<00:09.962>promise<00:10.392>I'm<00:11.016>not<00:12.310>trying<00:12.643>to<00:12.815>scare<00:13.681>you<00:14.311>",
            "[00:09.862]\u6211\u4fdd\u8bc1\u5e76\u975e\u60f3\u8981\u5413\u5230\u4f60[00:14.311]",
            "[00:14.880] <00:14.880>I<00:15.323>know<00:15.672>this<00:16.006>life<00:16.288>can<00:16.507>be<00:16.758>a<00:16.903>lot<00:17.731>",
            "[00:14.880]\u6211\u77e5\u9053\u751f\u6d3b\u6709\u65f6\u6c89\u91cd\u4e0d\u582a[00:17.731]"
        ).joinToString("\n")

        val parsed = EnhanceLrcParser.parse(raw).lines
        val normalized = io.github.proify.lyricon.localprovider.lyrics.LyricNormalizer.normalizeForTripleLine(parsed)

        assertEquals(2, normalized.size)

        val first = normalized[0]
        assertEquals("IpromiseI'mnottryingtoscareyou", compact(first.text))
        assertEquals("\u6211\u4fdd\u8bc1\u5e76\u975e\u60f3\u8981\u5413\u5230\u4f60", first.translation)
        assertNull(first.roma)

        val second = normalized[1]
        assertEquals("Iknowthislifecanbealot", compact(second.text))
        assertEquals("\u6211\u77e5\u9053\u751f\u6d3b\u6709\u65f6\u6c89\u91cd\u4e0d\u582a", second.translation)
        assertNull(second.roma)
    }

    private fun compact(text: String?): String = text.orEmpty().replace("\\s+".toRegex(), "")

    private fun wordsText(words: List<LyricWord>?): String =
        words.orEmpty().joinToString(separator = "") { it.text.orEmpty() }
}
