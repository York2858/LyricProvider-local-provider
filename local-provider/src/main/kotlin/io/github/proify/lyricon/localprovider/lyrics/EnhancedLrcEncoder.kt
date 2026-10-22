package io.github.proify.lyricon.localprovider.lyrics

import io.github.proify.lyricon.localprovider.model.LyricsResult
import io.github.proify.lyricon.lyric.model.LyricWord
import java.util.Locale

/** Typed access survives R8 renaming. Never turn missing words into an apparently valid file. */
object EnhancedLrcEncoder {
    fun encode(result: LyricsResult): String? {
        val prepared = result.rich.map { line ->
            if (line.text.isNullOrBlank()) line.copy(text = line.words.orEmpty().joinToString("") { it.text.orEmpty() }) else line
        }
        val lines = LyricNormalizer.normalizeForTripleLine(prepared).filter { line ->
            !line.text.isNullOrBlank() || line.words.orEmpty().any { !it.text.isNullOrBlank() }
        }
        if (lines.isEmpty()) return null
        val duet = lines.any { it.isAlignedRight }
        return buildString {
            listOf("ti" to result.trackName, "ar" to result.artistName, "al" to result.albumName).forEach { (key, value) ->
                value?.takeIf { it.isNotBlank() }?.let { append('[').append(key).append(':').append(singleLine(it)).append("]\n") }
            }
            lines.forEach { line ->
                val begin = line.begin.coerceAtLeast(0)
                val wordEnd = line.words.orEmpty().maxOfOrNull(::endOf) ?: begin
                val end = maxOf(line.end, if (line.duration > 0) begin + line.duration else begin, wordEnd)
                val role = if (!duet) "" else if (line.isAlignedRight) "v2:" else "v1:"
                appendTrack(begin, end, line.text.orEmpty(), line.words.orEmpty(), role)
                line.roma?.takeIf { it.isNotBlank() }?.let { text ->
                    val words = if (line.secondary == line.roma) line.secondaryWords.orEmpty() else emptyList()
                    appendTrack(begin, end, text, words, role)
                }
                line.translation?.takeIf { it.isNotBlank() }?.let { text ->
                    appendTrack(begin, end, text, line.translationWords.orEmpty(), role)
                }
            }
        }
    }
    private fun StringBuilder.appendTrack(begin: Long, end: Long, text: String, words: List<LyricWord>, role: String) {
        val usable = words.filter { !it.text.isNullOrEmpty() }
        val timed = usable.any { it.begin > 0 || endOf(it) > it.begin } &&
            usable.all { it.begin >= 0 } && usable.zipWithNext().all { (a, b) -> a.begin <= b.begin }
        append('[').append(time(begin)).append(']')
        if (timed) {
            usable.forEachIndexed { index, word ->
                append('<').append(time(word.begin)).append('>')
                if (index == 0) append(role)
                append(singleLine(word.text.orEmpty()))
                val next = usable.getOrNull(index + 1)
                val wordEnd = endOf(word)
                if (next != null && wordEnd > word.begin && wordEnd < next.begin) append('<').append(time(wordEnd)).append('>')
            }
            val lastEnd = maxOf(end, endOf(usable.last()))
            if (lastEnd > usable.last().begin) append('<').append(time(lastEnd)).append('>')
        } else {
            append(role).append(singleLine(text.ifBlank { usable.joinToString("") { it.text.orEmpty() } }))
            if (end > begin) append('[').append(time(end)).append(']')
        }
        append('\n')
    }
    private fun endOf(word: LyricWord) = maxOf(word.end, if (word.duration > 0) word.begin + word.duration else word.begin)
    private fun singleLine(text: String) = text.replace('\r', ' ').replace('\n', ' ')
    private fun time(ms: Long): String = String.format(Locale.ROOT, "%02d:%02d.%03d", ms / 60_000, ms / 1000 % 60, ms % 1000)
}
