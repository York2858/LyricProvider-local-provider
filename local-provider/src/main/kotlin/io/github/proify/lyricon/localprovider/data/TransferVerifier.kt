package io.github.proify.lyricon.localprovider.data

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song

/** Compares the received auxiliary fields, rather than relying on outbound counters. */
object TransferVerifier {
    const val PACKAGE = "io.github.proify.lyricon.localprovider"
    fun compare(sent: RuntimeSnapshot, received: Song?, provider: String, player: String): String {
        if (provider.isBlank()) return "等待词幕返回当前来源"
        if (provider != PACKAGE) return "当前歌词由其他来源提供，尚未切换到本地插件"
        if (received?.lyrics.isNullOrEmpty()) return "词幕尚未收到整首歌词"
        val expected = sent.song ?: return "插件尚未提交歌词"
        if (sent.player != player || trackKey(expected.name, expected.artist) != trackKey(received?.name, received?.artist))
            return "歌曲不一致，等待当前歌曲同步"
        val actual = received!!.lyrics.orEmpty().associateBy(::key)
        val expectedLines = expected.lyrics.orEmpty()
        if (expectedLines.isEmpty()) return "插件尚未提交整首歌词"
        val different = expectedLines.count { line ->
            val other = actual[key(line)]
            other == null || normalized(line.roma) != normalized(other.roma)
                || normalized(line.translation) != normalized(other.translation)
        }
        return if (different == 0 && actual.size == expectedLines.size) "字段一致：罗马音和翻译均已正确传递"
        else "字段不一致：${different.coerceAtLeast(kotlin.math.abs(actual.size - expectedLines.size))} 行需要检查"
    }
    private fun key(line: RichLyricLine): String = "${line.begin}|${line.isAlignedRight}|${normalized(line.text)}"
    private fun normalized(value: String?): String = value.orEmpty().filterNot { it.isWhitespace() }
}
