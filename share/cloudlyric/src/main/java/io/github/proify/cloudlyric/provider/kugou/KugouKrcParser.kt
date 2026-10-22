/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.kugou

import io.github.proify.cloudlyric.provider.internal.ProviderSupport
import io.github.proify.lyricon.lyric.model.LyricLine
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.util.Base64

internal object KugouKrcParser {
    private val tagRegex = Regex("""^\[(\w+):([^]]*)]$""")
    private val lineRegex = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val wordRegex = Regex("""<(\d+),(\d+),(\d+)>([^<]*)""")

    fun parse(raw: String): List<RichLyricLine> {
        if (raw.isBlank()) return emptyList()

        val tags = mutableMapOf<String, String>()
        val originalLines = mutableListOf<LyricLine>()

        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (!line.startsWith("[")) return@forEach

            val tagMatch = tagRegex.matchEntire(line)
            if (tagMatch != null) {
                tags[tagMatch.groupValues[1]] = tagMatch.groupValues[2]
                return@forEach
            }

            val lineMatch = lineRegex.matchEntire(line) ?: return@forEach
            val lineStart = lineMatch.groupValues[1].toLongOrNull() ?: return@forEach
            val lineDuration = lineMatch.groupValues[2].toLongOrNull() ?: return@forEach
            val lineEnd = lineStart + lineDuration
            val content = lineMatch.groupValues[3]

            val words = wordRegex.findAll(content)
                .mapNotNull { match ->
                    val wordOffset = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                    val wordDuration = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                    val wordText = match.groupValues[4]
                    if (wordText.isEmpty()) return@mapNotNull null

                    LyricWord(
                        begin = lineStart + wordOffset,
                        end = lineStart + wordOffset + wordDuration,
                        duration = wordDuration,
                        text = wordText
                    )
                }
                .toList()
                .ifEmpty {
                    listOf(
                        LyricWord(
                            begin = lineStart,
                            end = lineEnd,
                            duration = lineDuration,
                            text = content
                        )
                    )
                }

            originalLines += LyricLine(
                begin = lineStart,
                end = lineEnd,
                duration = lineDuration,
                text = words.joinToString(separator = "") { it.text.orEmpty() },
                words = words
            )
        }

        if (originalLines.isEmpty()) return emptyList()

        val translations = MutableList<String?>(originalLines.size) { null }
        val romas = MutableList<String?>(originalLines.size) { null }

        tags["language"]
            ?.takeIf { it.isNotBlank() }
            ?.let { encoded ->
                runCatching {
                    val decoded = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
                    ProviderSupport.json.decodeFromString<KrcLanguageRoot>(decoded)
                }.getOrNull()
            }
            ?.content
            .orEmpty()
            .forEach { item ->
                when (item.type) {
                    0 -> {
                        var contentIndex = 0
                        originalLines.forEachIndexed { lineIndex, line ->
                            if (line.text.isNullOrBlank()) return@forEachIndexed

                            val syllables = item.lyricContent
                                .getOrNull(contentIndex)
                                .orEmpty()
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }

                            if (syllables.isNotEmpty()) {
                                romas[lineIndex] = syllables.joinToString(" ")
                            }
                            contentIndex++
                        }
                    }

                    1 -> {
                        item.lyricContent.forEachIndexed { lineIndex, values ->
                            if (lineIndex >= translations.size) return@forEachIndexed
                            val text = values.firstOrNull()?.trim().orEmpty()
                            if (text.isNotEmpty()) {
                                translations[lineIndex] = text
                            }
                        }
                    }
                }
            }

        return originalLines.mapIndexed { index, line ->
            RichLyricLine(
                begin = line.begin,
                end = line.end,
                duration = line.duration,
                text = line.text,
                translation = translations[index],
                roma = romas[index],
                words = line.words
            )
        }
    }

    @Serializable
    private data class KrcLanguageRoot(
        val content: List<KrcLanguageItem> = emptyList()
    )

    @Serializable
    private data class KrcLanguageItem(
        val type: Int = 0,
        val lyricContent: List<List<String>> = emptyList()
    )
}
