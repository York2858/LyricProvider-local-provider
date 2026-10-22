package io.github.proify.lyricon.localprovider.data

import io.github.proify.lyricon.lyric.model.Song
import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class LyricFolder(val uri: String, val name: String)

@Serializable
data class ProviderSettings(
    val revision: Long = 0,
    val wordFirst: Boolean = true,
    val cloudEnabled: Boolean = true,
    val saveCloud: Boolean = true,
    val overwriteCloud: Boolean = false,
    val folders: List<LyricFolder> = listOf(
        LyricFolder("/storage/emulated/0/Music", "Music"),
        LyricFolder("/storage/emulated/0/Lyrics", "Lyrics"),
        LyricFolder("/storage/emulated/0/Download", "Download")
    ),
    val files: List<LyricFolder> = emptyList(),
    val players: Set<String> = DEFAULT_PLAYERS,
    val knownPlayers: Set<String> = DEFAULT_PLAYERS,
    val disabledSources: Set<String> = emptySet(),
    val exactRules: List<String> = emptyList(),
    val keywordRules: List<String> = emptyList(),
    val appleEnabled: Boolean = true,
    val appleStorefront: String = "us",
    val appleUserToken: String = "",
    val appleDeveloperToken: String = "",
    val appleLanguage: String = "zh-Hans-CN",
    val appleConfigured: Boolean = false,
    val matchRules: io.github.proify.lyricon.localprovider.search.MatchRules = io.github.proify.lyricon.localprovider.search.MatchRules()
) {
    companion object {
        val DEFAULT_PLAYERS = setOf(
            "yos.music.player", "com.neutroncode.mp", "com.maxmpz.audioplayer",
            "ink.trantor.coneplayer", "ink.trantor.coneplayer.gp"
        )
    }
}

@Serializable
data class RuntimeSnapshot(
    val player: String = "",
    val updatedAt: Long = 0,
    val source: String = "",
    val path: String? = null,
    val message: String = "等待播放器连接",
    val song: Song? = null,
    val isError: Boolean = false,
    val album: String = "",
    val artwork: String = ""
)

@Serializable
data class RuntimeEvent(val time: Long, val player: String, val title: String, val message: String)

@Serializable
data class SongChoice(val uri: String = "", val offset: Long = 0, val temporary: Boolean = false)

fun trackKey(title: String?, artist: String?): String =
    title.orEmpty().trim().lowercase(Locale.ROOT) + "|" + artist.orEmpty().trim().lowercase(Locale.ROOT)
