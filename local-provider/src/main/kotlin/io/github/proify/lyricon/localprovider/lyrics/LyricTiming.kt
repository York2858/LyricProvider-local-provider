package io.github.proify.lyricon.localprovider.lyrics

import io.github.proify.lyricon.lyric.model.RichLyricLine

/** A whole sentence stored as one word is still line-timed (for example Apple Music TTML). */
object LyricTiming {
    fun hasWordTiming(lines: List<RichLyricLine>): Boolean = lines.any { line ->
        val end = if (line.end > line.begin) line.end else
            if (line.duration > 0 && line.begin <= Long.MAX_VALUE - line.duration)
                line.begin + line.duration else line.begin
        if (line.begin < 0 || end <= line.begin) return@any false
        var firstBegin: Long? = null
        line.words.orEmpty().any words@{ word ->
            if (word.text.isNullOrBlank()) return@words false
            val wordEnd = if (word.end > word.begin) word.end else
                if (word.duration > 0 && word.begin <= Long.MAX_VALUE - word.duration)
                    word.begin + word.duration else word.begin
            if (word.begin < line.begin || wordEnd <= word.begin || wordEnd > end)
                return@words false
            val previous = firstBegin
            if (previous == null) firstBegin = word.begin
            previous != null && previous != word.begin
        }
    }
}
