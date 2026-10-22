/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.soda

import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.LyricWord

internal object SodaLyricParser {
    private val lineRegex = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val wordRegex = Regex("""<(\d+),(\d+),\d+>([^<]*)""")

    fun parse(raw: String?): List<LyricLine> {
        if (raw.isNullOrBlank()) return emptyList()

        return raw.lineSequence()
            .mapNotNull { rawLine ->
                val line = rawLine.trim()
                val match = lineRegex.matchEntire(line) ?: return@mapNotNull null

                val lineStart = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val lineDuration = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val lineEnd = lineStart + lineDuration
                val content = match.groupValues[3]

                val words = wordRegex.findAll(content)
                    .mapNotNull { wordMatch ->
                        val offset = wordMatch.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                        val duration = wordMatch.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                        val text = wordMatch.groupValues[3]
                        if (text.isEmpty()) return@mapNotNull null

                        LyricWord(
                            begin = lineStart + offset,
                            end = lineStart + offset + duration,
                            duration = duration,
                            text = text
                        )
                    }
                    .toList()
                    .ifEmpty {
                        listOf(
                            LyricWord(
                                begin = lineStart,
                                end = lineEnd,
                                duration = lineDuration,
                                text = content.replace(Regex("<[^>]+>"), "")
                            )
                        )
                    }

                LyricLine(
                    begin = lineStart,
                    end = lineEnd,
                    duration = lineDuration,
                    text = words.joinToString(separator = "") { it.text.orEmpty() },
                    words = words
                )
            }
            .toList()
    }
}
