package io.github.proify.lyricon.localprovider.search

import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Serializable
data class MatchRules(
    val titleWeight: Int = 40, val artistWeight: Int = 30,
    val albumWeight: Int = 10, val durationWeight: Int = 20,
    val titleMode: String = "normalized", val artistMode: String = "normalized",
    val albumMode: String = "normalized", val durationToleranceMs: Long = 5000,
    val minimumScore: Int = 65, val requireTitle: Boolean = true,
    val requireArtist: Boolean = true, val requireAlbum: Boolean = false,
    val requireDuration: Boolean = false
) {
    fun valid(): Boolean = listOf(titleWeight, artistWeight, albumWeight, durationWeight).all { it in 0..100 } &&
        titleWeight + artistWeight + albumWeight + durationWeight > 0 &&
        durationToleranceMs in 1000..120000 && minimumScore in 0..100 &&
        listOf(titleMode, artistMode, albumMode).all { it in setOf("exact", "normalized", "fuzzy") }
}

data class MatchTarget(val title: String? = null, val artist: String? = null,
                       val album: String? = null, val durationMs: Long = 0)
data class MatchPart(val label: String, val similarity: Double?, val weight: Int, val points: Double)
data class MatchScore(val total: Int, val parts: List<MatchPart>, val eligible: Boolean,
                      val reasons: List<String>)

/** One scoring policy shared by visible search ranking and the hooked player's auto matching. */
object MatchScorer {
    fun normalize(value: String?): String = Normalizer.normalize(value.orEmpty(), Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun similarity(target: String?, candidate: String?, mode: String): Double? {
        if (target.isNullOrBlank()) return null
        if (candidate.isNullOrBlank()) return 0.0
        if (mode == "exact") return if (target.trim() == candidate.trim()) 1.0 else 0.0
        val a = normalize(target); val b = normalize(candidate)
        if (a.isEmpty()) return null
        if (b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (mode == "normalized") return 0.0
        // Bound work for malformed source metadata; lyrics are never compared here.
        val x = a.take(256); val y = b.take(256)
        var previous = IntArray(y.length + 1) { it }
        for (i in x.indices) {
            val next = IntArray(y.length + 1); next[0] = i + 1
            for (j in y.indices) next[j + 1] = minOf(next[j] + 1, previous[j + 1] + 1,
                previous[j] + if (x[i] == y[j]) 0 else 1)
            previous = next
        }
        return (1.0 - previous.last().toDouble() / maxOf(x.length, y.length)).coerceIn(0.0, 1.0)
    }

    fun evaluate(target: MatchTarget, candidate: MatchTarget, rules: MatchRules): MatchScore {
        val safe = rules.takeIf { it.valid() } ?: MatchRules()
        val title = if (safe.titleMode == "exact") similarity(target.title, candidate.title, safe.titleMode)
            else similarity(comparableTitle(target.title), comparableTitle(candidate.title), safe.titleMode)
        val artist = similarity(target.artist, candidate.artist, safe.artistMode)
        val album = similarity(target.album, candidate.album, safe.albumMode)
        val duration = if (target.durationMs <= 0) null else if (candidate.durationMs <= 0) 0.0 else {
            val delta = abs(target.durationMs - candidate.durationMs)
            if (delta <= 1000) 1.0 else (1.0 - (delta - 1000).toDouble() / (safe.durationToleranceMs - 1000).coerceAtLeast(1)).coerceIn(0.0, 1.0)
        }
        val values = listOf(title, artist, album, duration)
        val weights = listOf(safe.titleWeight, safe.artistWeight, safe.albumWeight, safe.durationWeight)
        val labels = listOf("标题", "艺术家", "专辑", "时长")
        val activeWeight = values.indices.filter { values[it] != null }.sumOf { weights[it] }
        val parts = values.indices.map { i -> MatchPart(labels[i], values[i], weights[i],
            if (activeWeight == 0 || values[i] == null) 0.0 else values[i]!! * weights[i] * 100.0 / activeWeight) }
        val total = parts.sumOf { it.points }.roundToInt().coerceIn(0, 100)
        val reasons = mutableListOf<String>()
        listOf(safe.requireTitle, safe.requireArtist, safe.requireAlbum).forEachIndexed { i, required ->
            if (required && values[i] != null && values[i]!! < .6) reasons += "${labels[i]}未满足必需规则"
        }
        if (safe.requireDuration && target.durationMs > 0 && (candidate.durationMs <= 0 ||
                    abs(target.durationMs - candidate.durationMs) > safe.durationToleranceMs)) reasons += "时长超出容差"
        if (activeWeight == 0) reasons += "没有可比较的字段"
        if (total < safe.minimumScore) reasons += "低于自动匹配最低分 ${safe.minimumScore}"
        return MatchScore(total, parts, reasons.isEmpty(), reasons)
    }

    private fun comparableTitle(value: String?): String = value.orEmpty().replace(
        Regex("[（(\\[【](.*?)[）)\\]】]")) { match ->
        // Drop a translated alias, while preserving performance/version distinctions.
        if (Regex("(?i)live|remaster|instrumental|acoustic|伴奏|现场|重制|混音|remix").containsMatchIn(match.value)) match.value else ""
    }

    /** Group versions of one title/artist, but keep different artists in separate groups. */
    fun groupKey(target: MatchTarget): String {
        val base = target.title.orEmpty()
            .replace(Regex("[（(\\[【].*?[）)\\]】]"), "")
            .replace(Regex("(?i)\\s*[-–—]?\\s*(live|remaster(?:ed)?(?:\\s*\\d{4})?|instrumental|acoustic|伴奏|现场版)\\s*$"), "")
        return normalize(base.ifBlank { target.title.orEmpty() }) + "|" + normalize(target.artist)
    }
}
