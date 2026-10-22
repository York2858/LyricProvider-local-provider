package io.github.proify.lyricon.localprovider.preview

import kotlinx.serialization.Serializable

/** PlaybackState uses the same monotonic clock in the player and the preview process. */
@Serializable
data class PlaybackSnapshot(
    val player: String = "", val track: String = "", val positionMs: Long = -1,
    val updatedElapsedMs: Long = 0, val speed: Float = 0f, val playing: Boolean = false
) {
    fun positionAt(nowElapsedMs: Long, durationMs: Long = 0): Long {
        if (positionMs < 0) return -1
        val elapsed = if (playing && updatedElapsedMs > 0 && speed.isFinite())
            (nowElapsedMs - updatedElapsedMs).coerceAtLeast(0) * speed.toDouble() else 0.0
        val position = (positionMs + elapsed).toLong().coerceAtLeast(0)
        return if (durationMs > 0) position.coerceAtMost(durationMs) else position
    }
}
