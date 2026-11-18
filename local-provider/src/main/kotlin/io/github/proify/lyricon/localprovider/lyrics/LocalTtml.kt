package io.github.proify.lyricon.localprovider.lyrics

import io.github.proify.cloudlyric.provider.apple.AppleMusicTtmlParser
import io.github.proify.lyricon.lyric.model.RichLyricLine

internal object LocalTtml {
    private const val MAX_BRIDGE_CHARS = 120_000
    private val doctype = Regex("<!DOCTYPE", RegexOption.IGNORE_CASE)
    private val offset = Regex("\\blyricOffset\\s*=")
    private val indentation = Regex(">[ \\t]*[\\r\\n][ \\t\\r\\n]*<")

    fun parse(raw: String): List<RichLyricLine> =
        if (doctype.containsMatchIn(raw)) emptyList() else AppleMusicTtmlParser.parse(raw.removePrefix("\uFEFF"))

    fun forBridge(raw: String): String? {
        if (raw.length > MAX_BRIDGE_CHARS || doctype.containsMatchIn(raw) || offset.containsMatchIn(raw)) return null
        val document = raw.removePrefix("\uFEFF").trim()
        if (!AppleMusicTtmlParser.isTtmlDocument(document)) return null
        return document.replace(indentation, "><")
    }

    fun bridgeLines(ttml: String): List<RichLyricLine> = LyricNormalizer.normalizeForTripleLine(
        AppleMusicTtmlParser.parse(ttml, separateAuxiliaryRoles = true)
    )
}
