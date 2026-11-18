package io.github.proify.lyricon.localprovider.lyrics

import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/** ColorOS uses the plain lane; Bridge can also render the separate timed words and translation. */
internal object LyricInfoEncoder {
    const val SOURCE = "lyricprovider/local-provider/neutron"

    fun encode(
        title: String,
        artist: String?,
        album: String?,
        mediaId: String?,
        generation: Long,
        lines: List<RichLyricLine>,
        ttml: String? = null
    ): String? {
        val rows = lines.asSequence().filter { it.begin >= 0 }.sortedBy { it.begin }.mapNotNull { line ->
            val text = clean(line.text).ifBlank {
                clean(line.words.orEmpty().joinToString("") { it.text.orEmpty() })
            }
            if (text.isBlank()) return@mapNotNull null
            val tag = "[${time(line.begin)}]"
            val translation = clean(line.translation).takeIf { it.isNotBlank() && it != text }
            Row(tag + text, enhanced(line)?.let { tag + it }, translation?.let { tag + it })
        }.toList()
        if (rows.isEmpty()) return null

        return buildJsonObject {
            put("songName", title)
            artist?.let { put("artist", it) }
            album?.let { put("album", it) }
            mediaId?.let { put("songId", it) }
            put("sessionGeneration", generation)
            put("source", SOURCE)
            put("lyricType", 0)
            put("noLyric", false)
            put("lyric", rows.joinToString("\n") { it.plain })
            ttml?.let { put("ttml", it) }
            if (rows.any { it.enhanced != null }) {
                put("rawLyric", rows.joinToString("\n") { it.enhanced ?: it.plain })
            }
            val translations = rows.mapNotNull { it.translation }
            if (translations.isNotEmpty()) put("translationLyric", translations.joinToString("\n"))
        }.toString()
    }

    private fun enhanced(line: RichLyricLine): String? {
        val words = line.words.orEmpty().filter { !it.text.isNullOrEmpty() }
        if (!LyricTiming.hasWordTiming(listOf(line)) ||
            words.any { it.begin < line.begin } ||
            words.zipWithNext().any { (first, second) -> first.begin > second.begin }
        ) return null

        return buildString {
            words.forEach { word ->
                append('<').append(time(word.begin)).append('>')
                append(singleLine(word.text.orEmpty()))
            }
            val last = words.last()
            val end = wordEnd(last)
            if (end > last.begin) append('<').append(time(end)).append('>')
        }
    }

    private fun wordEnd(word: LyricWord): Long = when {
        word.end > word.begin -> word.end
        word.duration > 0 && word.begin <= Long.MAX_VALUE - word.duration -> word.begin + word.duration
        else -> word.begin
    }

    private fun clean(text: String?): String = singleLine(text.orEmpty()).trim()
    private fun singleLine(text: String): String = text.replace('\r', ' ').replace('\n', ' ')
    private fun time(ms: Long): String =
        String.format(Locale.ROOT, "%02d:%02d.%03d", ms / 60_000, ms / 1000 % 60, ms % 1000)

    private data class Row(val plain: String, val enhanced: String?, val translation: String?)
}
