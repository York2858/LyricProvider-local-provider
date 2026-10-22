package io.github.proify.lyricon.localprovider.preview

import io.github.proify.lyricon.lyric.model.RichLyricLine

/** Build text ranges once, rather than searching all words on every animation frame. */
class PreviewTimeline(val lines: List<RichLyricLine>) {
    data class Word(val start: Int, val end: Int, val beginMs: Long, val endMs: Long)
    data class Display(val text: String, val words: List<Word>)
    data class Position(val line: Int, val word: Int = -1, val singing: Boolean = false)
    val display = lines.map { line ->
        val words = line.words.orEmpty()
        var rendered = line.text.orEmpty()
        var cursor = 0
        var mismatch = false
        val mapped = words.map { word ->
            val value = word.text.orEmpty()
            val start = if (value.isEmpty()) cursor else rendered.indexOf(value, cursor)
            if (start < 0) mismatch = true else cursor = start + value.length
            Word(start, start + value.length, word.begin, word.end.takeIf { it > word.begin } ?: (word.begin + word.duration))
        }
        val ranges = if (mismatch) {
            rendered = words.joinToString("") { it.text.orEmpty() }; cursor = 0
            words.map { word ->
                val start = cursor; cursor += word.text.orEmpty().length
                Word(start, cursor, word.begin, word.end.takeIf { it > word.begin } ?: (word.begin + word.duration))
            }
        } else mapped
        Display(rendered, ranges)
    }
    private val ordered = lines.indices.sortedBy { lines[it].begin }
    private val begins = ordered.map { lines[it].begin }.toLongArray()
    private fun upperBound(values: LongArray, time: Long): Int {
        var low = 0; var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] <= time) low = mid + 1 else high = mid
        }
        return low
    }
    private val ends = LongArray(lines.size) { index ->
        val line = lines[index]
        line.end.takeIf { it > line.begin }
            ?: line.duration.takeIf { it > 0 }?.let { line.begin + it }
            ?: display[index].words.maxOfOrNull { it.endMs }?.takeIf { it > line.begin }
            ?: begins.getOrNull(upperBound(begins, line.begin)) ?: line.begin
    }
    private data class WordLookup(val times: LongArray, val active: IntArray, val anchor: IntArray)
    private val wordLookups = display.map { row ->
        val events = sortedMapOf<Long, Pair<MutableList<Int>, MutableList<Int>>>()
        row.words.forEachIndexed { index, word ->
            if (word.start >= 0 && word.end > word.start && word.endMs > word.beginMs) {
                events.getOrPut(word.beginMs) { mutableListOf<Int>() to mutableListOf() }.first += index
                events.getOrPut(word.endMs) { mutableListOf<Int>() to mutableListOf() }.second += index
            }
        }
        val active = java.util.TreeSet<Int>()
        var lastStarted = -1
        val singing = IntArray(events.size); val anchors = IntArray(events.size)
        events.entries.forEachIndexed { index, (_, changes) ->
            active.removeAll(changes.second.toSet()); active.addAll(changes.first)
            changes.first.maxOrNull()?.let { lastStarted = maxOf(lastStarted, it) }
            singing[index] = if (active.isEmpty()) -1 else active.last()
            anchors[index] = lastStarted
        }
        WordLookup(events.keys.toLongArray(), singing, anchors)
    }
    private val boundaries = (begins.asSequence() + ends.asSequence() + wordLookups.asSequence()
        .flatMap { it.times.asSequence() }).distinct().sorted().toList().toLongArray()
    private val timed = lines.any { it.begin > 0 || it.end > it.begin || it.duration > 0 } ||
        display.any { row -> row.words.any { it.endMs > it.beginMs } }
    fun anchorOffset(position: Position, playbackMs: Long, offsetMs: Long = 0): Int {
        val words = display[position.line].words
        val lookup = wordLookups[position.line]
        val event = upperBound(lookup.times, playbackMs - offsetMs) - 1
        val anchor = lookup.anchor.getOrNull(event) ?: -1
        return words.getOrNull(position.word)?.start ?: words.getOrNull(anchor)?.start ?: 0
    }
    /** Absolute playback time at which highlighting can next change. No frame polling needed. */
    fun nextBoundary(playbackMs: Long, offsetMs: Long = 0): Long? =
        if (!timed || playbackMs < 0) null else boundaries.getOrNull(upperBound(boundaries, playbackMs - offsetMs))?.plus(offsetMs)

    fun nextDelay(playbackMs: Long, offsetMs: Long = 0, playing: Boolean, speed: Float): Long? {
        if (!playing || !speed.isFinite() || speed <= 0) return null
        val boundary = nextBoundary(playbackMs, offsetMs) ?: return null
        return kotlin.math.ceil((boundary - playbackMs) / speed.toDouble()).toLong().coerceAtLeast(16)
    }
    fun locate(playbackMs: Long, offsetMs: Long = 0): Position? {
        if (lines.isEmpty() || !timed || playbackMs < 0) return null
        val time = playbackMs - offsetMs
        val index = ordered[(upperBound(begins, time) - 1).coerceAtLeast(0)]
        val singing = time >= lines[index].begin && time < ends[index]
        val lookup = wordLookups[index]
        val event = upperBound(lookup.times, time) - 1
        return Position(index, if (singing) lookup.active.getOrNull(event) ?: -1 else -1, singing)
    }
}
