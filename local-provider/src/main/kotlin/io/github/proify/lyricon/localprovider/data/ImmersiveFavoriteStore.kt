package io.github.proify.lyricon.localprovider.data

import android.content.Context
import android.os.Bundle

class ImmersiveFavoriteStore(private val context: Context) {
    fun call(data: Bundle): Bundle {
        val player = data.getString("player").orEmpty()
        val title = data.getString("title").orEmpty()
        val artist = data.getString("artist").orEmpty()
        val album = data.getString("album").orEmpty()
        val mediaId = data.getString("media_id").orEmpty()
        require(player.length <= 300 && title.isNotBlank() && title.length <= 2000 &&
            artist.length <= 2000 && album.length <= 2000 && mediaId.length <= 4000)
        val records = PreferenceRecordStore(context)
        if (player !in ProviderStore(context).settings().players) return rejected("请在本地歌词服务中启用当前播放器")
        val value = PreferenceObservation(player, mediaId, title, artist, album, data.getLong("duration"))
        if (data.containsKey("favorite")) {
            if (!records.enabled()) return rejected("请先开启本地歌词服务的收藏与评分记录")
            records.setImmersiveFavorite(value, data.getBoolean("favorite"))
        }
        return Bundle().apply {
            putBoolean("accepted", true)
            putBoolean("favorite", records.immersiveRecord(value)?.let { it.favorite ?: it.lastFavorite } == true)
        }
    }

    private fun rejected(message: String) = Bundle().apply { putBoolean("accepted", false); putString("error", message) }
}
