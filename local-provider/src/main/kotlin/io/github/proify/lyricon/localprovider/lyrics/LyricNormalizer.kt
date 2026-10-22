package io.github.proify.lyricon.localprovider.lyrics

import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.localprovider.xposed.LyricsHeaderCleanup
import java.util.Locale

/** Converts parser and legacy provider rows to the public Lyricon field meanings. */
object LyricNormalizer {
    fun normalizeForTripleLine(lines: List<RichLyricLine>): List<RichLyricLine> {
        if (lines.isEmpty()) return lines
        val source = lines.map { it.copy() }
        val merged = mutableListOf<RichLyricLine>()
        var index = 0
        while (index < source.size) {
            val base = source[index]
            val cluster = mutableListOf(base)
            var cursor = index + 1
            while (cursor < source.size && isTimingNear(base, source[cursor])) {
                cluster.add(source[cursor])
                cursor++
            }
            merged.add(mergeTimingCluster(cluster))
            index = cursor
        }
        val cleanupOutcome = LyricsHeaderCleanup.cleanup(merged)
        return cleanupOutcome.lines
    }

    private fun mergeTimingCluster(cluster: List<RichLyricLine>): RichLyricLine {
        val primary = cluster.maxByOrNull(::scoreMainLine) ?: return cluster.first()
        val main = primary.copy()
        val mainText = main.text?.trim().orEmpty()
        val mainDisplayText = main.words?.takeIf { it.isNotEmpty() }?.let(::wordsToDisplayText)?.trim().orEmpty()
        val mainComparableTexts = setOf(
            normalizeComparableText(mainText),
            normalizeComparableText(mainDisplayText)
        ).filter { it.isNotEmpty() }.toSet()

        data class TimedTextCandidate(
            val text: String,
            val words: List<io.github.proify.lyricon.lyric.model.LyricWord>?
        )

        val romaCandidates = linkedMapOf<String, TimedTextCandidate>()
        val translationCandidates = linkedMapOf<String, TimedTextCandidate>()

        fun collectCandidate(
            text: String?,
            words: List<io.github.proify.lyricon.lyric.model.LyricWord>? = null,
            romanization: Boolean? = null
        ) {
            val value = text?.trim().orEmpty()
            if (value.isBlank()) return

            if (normalizeComparableText(value) in mainComparableTexts) return
            val target = if (romanization ?: (containsCjk(mainText) && isLikelyRomaText(value))) {
                romaCandidates
            } else {
                translationCandidates
            }

            val current = target[value]
            if (current == null || (current.words.isNullOrEmpty() && !words.isNullOrEmpty())) {
                target[value] = TimedTextCandidate(
                    text = value,
                    words = words
                )
            }
        }

        fun collectWords(words: List<io.github.proify.lyricon.lyric.model.LyricWord>?) {
            if (words.isNullOrEmpty()) return
            val value = wordsToDisplayText(words).trim()
            collectCandidate(value, words)
        }

        // Explicit fields take priority over heuristic interpretation of auxiliary rows.
        cluster.forEach { line ->
            val legacyTranslation = !line.roma.isNullOrBlank() && !isLikelyRomaText(line.roma!!)
                && isLikelyRomaText(line.secondary.orEmpty())
            collectCandidate(line.translation, line.translationWords, romanization = false)
            collectCandidate(line.roma, romanization = !legacyTranslation)
        }
        cluster.forEach { line ->
            if (line !== primary) {
                collectCandidate(line.text, line.words)
                collectWords(line.words)
            }
            collectCandidate(line.secondary, line.secondaryWords)
        }

        val romaCandidate = romaCandidates.values.firstOrNull { candidate ->
            candidate.text != mainText && normalizeComparableText(candidate.text) !in mainComparableTexts
        }
        val translationCandidate = translationCandidates.values.firstOrNull { it.text != mainText }

        main.roma = romaCandidate?.text
        main.translation = translationCandidate?.text
        main.translationWords = translationCandidate?.words
        // Keep timed romanization as a redundant secondary track for older clients.
        main.secondary = romaCandidate?.text
        main.secondaryWords = romaCandidate?.words
        return main
    }

    private fun wordsToDisplayText(words: List<io.github.proify.lyricon.lyric.model.LyricWord>): String {
        val joined = words.joinToString(separator = "") { it.text.orEmpty() }

        val hasLatin = joined.any { it in 'a'..'z' || it in 'A'..'Z' }
        val hasCjk = joined.any { char ->
            char in '\u3040'..'\u30ff'
                    || char in '\u3400'..'\u4dbf'
                    || char in '\u4e00'..'\u9fff'
                    || char in '\u1100'..'\u11ff'
                    || char in '\u3130'..'\u318f'
                    || char in '\uac00'..'\ud7af'
                    || char in '\u1100'..'\u11ff'
                    || char in '\u3130'..'\u318f'
                    || char in '\uac00'..'\ud7af'
        }
        return if (hasLatin && !hasCjk) {
            buildString {
                words.forEach { word ->
                    val text = word.text.orEmpty()
                    if (text.isEmpty()) return@forEach
                    if (isNotEmpty() && !last().isWhitespace() && !text.first().isWhitespace()) {
                        append(' ')
                    }
                    append(text)
                }
            }.trim()
        } else {
            joined
        }
    }

    private fun isTimingNear(a: RichLyricLine, b: RichLyricLine): Boolean {
        return a.isAlignedRight == b.isAlignedRight && kotlin.math.abs(a.begin - b.begin) <= 120L
                && kotlin.math.abs(a.end - b.end) <= 220L
    }

    private fun scoreMainLine(line: RichLyricLine): Int {
        val text = line.text?.trim().orEmpty()
        if (text.isBlank()) return Int.MIN_VALUE
        var score = 0
        if (!isLikelyRomaText(text)) score += 2
        if (containsKana(text)) score += 4
        if (containsCjk(text)) score += 2
        if (!line.words.isNullOrEmpty()) score += 3
        if (!line.secondary.isNullOrBlank() || !line.translation.isNullOrBlank()) score += 1
        return score
    }

    private fun isLikelyRomaText(text: String): Boolean {
        if (text.isBlank()) return false
        return containsLatinLetter(text) && !containsCjk(text)
    }

    private fun isTimingClose(a: RichLyricLine, b: RichLyricLine): Boolean {
        return kotlin.math.abs(a.begin - b.begin) <= 1500L
                && kotlin.math.abs(a.end - b.end) <= 2000L
    }

    private fun containsLatinLetter(text: String): Boolean {
        return text.any { it in 'a'..'z' || it in 'A'..'Z' }
    }

    private fun containsCjk(text: String): Boolean {
        return text.any { char ->
            char in '\u3040'..'\u30ff'
                    || char in '\u3400'..'\u4dbf'
                    || char in '\u4e00'..'\u9fff'
                    || char in '\u1100'..'\u11ff'
                    || char in '\u3130'..'\u318f'
                    || char in '\uac00'..'\ud7af'
        }
    }

    private fun containsKana(text: String): Boolean {
        return text.any { it in '\u3040'..'\u30ff' }
    }

    private fun normalizeComparableText(text: String?): String {
        return text.orEmpty()
            .lowercase(Locale.getDefault())
            .replace(Regex("\\s+"), "")
            .ifBlank { "" }
    }

}
