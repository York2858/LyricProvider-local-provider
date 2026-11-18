package io.github.proify.lyricon.localprovider.xposed

import io.github.proify.lyricon.localprovider.lyrics.LyricInfoEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object NeutronLockscreenPolicy {
    private const val LYRIC_ENTRANCE_ALL = 52 // OPlus lockscreen lyric entrance value.
    private val line = Regex("(?m)^\\[\\d+:[0-5]\\d\\.\\d{3}][^\\S\\r\\n]*\\S")

    fun entrance(original: Int, player: Any?, lyricInfo: () -> String?): Int =
        if (original == 0 && accepts(player, lyricInfo)) LYRIC_ENTRANCE_ALL else original

    fun support(original: Boolean, player: Any?, lyricInfo: () -> String?): Boolean =
        original || accepts(player, lyricInfo)

    private fun accepts(player: Any?, lyricInfo: () -> String?) =
        player == Constants.NEUTRON_PLAYER_PACKAGE && hasPublishedLyrics(lyricInfo())

    private fun hasPublishedLyrics(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val payload = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return false
        fun field(key: String) = (payload[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return field("source") == LyricInfoEncoder.SOURCE &&
            payload["noLyric"] == JsonPrimitive(false) &&
            field("lyric")?.let(line::containsMatchIn) == true
    }
}
