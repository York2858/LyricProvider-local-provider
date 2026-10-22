/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.localprovider.xposed

import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.lyric.model.RichLyricLine

data class LyricsHeaderCleanupOutcome(
    val lines: List<RichLyricLine>,
    val removedLineCount: Int
)

object LyricsHeaderCleanup {

    private val blockedLyricLineKeywords = listOf(
        "\u4ee5\u4e0b\u6b4c\u8bcd\u7ffb\u8bd1\u7531\u6587\u66f2\u5927\u6a21\u578b\u63d0\u4f9b"
    )

    fun cleanup(lines: List<RichLyricLine>): LyricsHeaderCleanupOutcome {
        if (lines.isEmpty()) {
            return LyricsHeaderCleanupOutcome(lines = lines, removedLineCount = 0)
        }

        var removedLineCount = 0
        val filteredLines = lines.filterNot { line ->
            val text = line.text?.trim().orEmpty()
            val matched = shouldRemoveLine(text)
            if (matched) {
                removedLineCount += 1
            }
            matched
        }

        return LyricsHeaderCleanupOutcome(
            lines = filteredLines,
            removedLineCount = removedLineCount
        )
    }

    private fun shouldRemoveLine(text: String): Boolean {
        if (text.isBlank()) return false
        if (shouldRemoveBlockedLine(text)) return true
        return EnhanceLrcParser.matchesStaffHeaderLine(text)
    }

    private fun shouldRemoveBlockedLine(text: String): Boolean {
        val normalizedText = text.replace(Regex("\\s+"), "")
        return blockedLyricLineKeywords.any { normalizedText.contains(it) }
    }

}
