/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.netease

import io.github.proify.cloudlyric.CatalogProvider
import io.github.proify.cloudlyric.CatalogPage
import io.github.proify.cloudlyric.CatalogTrack
import io.github.proify.cloudlyric.LyricsProvider
import io.github.proify.cloudlyric.LyricsResult
import io.github.proify.cloudlyric.toRichLines
import io.github.proify.cloudlyric.provider.internal.ProviderSupport
import io.github.proify.lrckit.LrcParser
import io.github.proify.lyricon.yrckit.YrcParser
import io.github.proify.lyricon.yrckit.download.YrcDownloader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class NetEaseProvider : LyricsProvider, CatalogProvider {
    companion object {
        const val ID = "NetEase"

        private const val SEARCH_URL = "https://music.163.com/api/search/get/web"
    }

    override val id: String = ID

    override suspend fun searchCatalog(query: String, pageSize: Int, cursor: String?): CatalogPage {
        val size = pageSize.coerceIn(1, 50)
        val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val body = ProviderSupport.httpGet("$SEARCH_URL?" + ProviderSupport.encodeParams(mapOf(
            "s" to query, "type" to "1", "offset" to offset.toString(), "limit" to size.toString())),
            headers = mapOf("Referer" to "https://music.163.com/")) ?: error("网易云搜索暂不可用")
        val response = ProviderSupport.json.decodeFromString<NetEaseSearchResponse>(body)
        if (response.code == -462) throw io.github.proify.cloudlyric.CatalogAccessException("网易云要求验证，请稍后重试")
        if (response.code != 200) throw io.github.proify.cloudlyric.CatalogAccessException("网易云搜索暂不可用（${response.code}）")
        val songs = response.result?.songs.orEmpty()
        val missing = songs.filter { it.album.picUrl.isBlank() }
        val covers = if (missing.isEmpty()) emptyMap() else runCatching {
            val details = ProviderSupport.httpGet("https://music.163.com/api/song/detail/?" + ProviderSupport.encodeParams(
                mapOf("ids" to missing.joinToString(prefix = "[", postfix = "]") { it.id.toString() })),
                headers = mapOf("Referer" to "https://music.163.com/")) ?: return@runCatching emptyMap<Long, String>()
            ProviderSupport.json.decodeFromString<NetEaseDetailsResponse>(details).songs.associate { it.id to it.album.picUrl }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrDefault(emptyMap())
        return CatalogPage(songs.map { song -> CatalogTrack(song.id.toString(), LyricsResult(
            trackName = song.name, artistName = song.artists.joinToString(", ") { it.name },
            albumName = song.album.name, durationMs = song.duration,
            artworkUrl = io.github.proify.cloudlyric.CoverUrls.normalize(song.album.picUrl.ifBlank { covers[song.id].orEmpty() }))) },
            if (songs.size >= size && (response.result?.songCount?.let { offset + songs.size < it } != false))
                (offset + size).toString() else null)
    }
    override suspend fun loadLyrics(track: CatalogTrack): LyricsResult? {
        val response = YrcDownloader.fetchLyricCancellable(track.key.toLong())
        val lines = when {
            !response.yrc?.lyric.isNullOrBlank() -> YrcParser.parse(response.yrc?.lyric)
            !response.lrc?.lyric.isNullOrBlank() -> LrcParser.parse(response.lrc?.lyric).lines
            else -> emptyList()
        }
        return track.metadata.copy(rich = lines.toRichLines(transLrc = response.tlyric?.lyric,
            romaLrc = response.romalrc?.lyric), instrumental = response.pureMusic).takeIf { lines.isNotEmpty() }
    }

    override suspend fun search(
        query: String?,
        trackName: String?,
        artistName: String?,
        albumName: String?,
        limit: Int
    ): List<LyricsResult> {
        val keyword = ProviderSupport.buildKeyword(
            query = query,
            trackName = trackName,
            artistName = artistName,
            albumName = albumName
        )
        if (keyword.isBlank()) return emptyList()

        val url = "$SEARCH_URL?${ProviderSupport.encodeParams(buildMap {
            put("csrf_token", "")
            put("s", keyword)
            put("type", "1")
            put("offset", "0")
            put("limit", limit.coerceAtLeast(1).toString())
        })}"

        val body = ProviderSupport.httpGet(
            url = url,
            headers = mapOf(
                "Referer" to "https://music.163.com/",
                "User-Agent" to ProviderSupport.DEFAULT_USER_AGENT
            )
        ) ?: return emptyList()

        val response = runCatching {
            ProviderSupport.json.decodeFromString<NetEaseSearchResponse>(body)
        }.getOrNull() ?: return emptyList()

        val songs = response.result?.songs.orEmpty().take(limit)
        // The public search endpoint returns album IDs but usually omits picUrl.
        // Resolve all missing pictures with one metadata request, without extra lyric searches.
        val missing = songs.filter { it.album.picUrl.isBlank() }
        val covers = if (missing.isEmpty()) emptyMap() else runCatching {
            val details = ProviderSupport.httpGet(
                url = "https://music.163.com/api/song/detail/?" + ProviderSupport.encodeParams(
                    mapOf("ids" to missing.joinToString(prefix = "[", postfix = "]") { it.id.toString() })),
                headers = mapOf("Referer" to "https://music.163.com/", "User-Agent" to ProviderSupport.DEFAULT_USER_AGENT)
            ) ?: return@runCatching emptyMap<Long, String>()
            ProviderSupport.json.decodeFromString<NetEaseDetailsResponse>(details).songs.associate { it.id to it.album.picUrl }
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrDefault(emptyMap())
        return songs
            .mapNotNull { song ->
                val lyricResponse = runCatching {
                    YrcDownloader.fetchLyricCancellable(song.id)
                }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull() ?: return@mapNotNull null

                val originalLines = when {
                    !lyricResponse.yrc?.lyric.isNullOrBlank() -> YrcParser.parse(lyricResponse.yrc?.lyric)
                    !lyricResponse.lrc?.lyric.isNullOrBlank() -> LrcParser.parse(lyricResponse.lrc?.lyric).lines
                    else -> emptyList()
                }
                if (originalLines.isEmpty()) return@mapNotNull null

                LyricsResult(
                    trackName = song.name,
                    artistName = song.artists.joinToString(", ") { it.name },
                    albumName = song.album.name,
                    durationMs = song.duration,
                    rich = originalLines.toRichLines(
                        transLrc = lyricResponse.tlyric?.lyric,
                        romaLrc = lyricResponse.romalrc?.lyric
                    ),
                    instrumental = lyricResponse.pureMusic,
                    artworkUrl = io.github.proify.cloudlyric.CoverUrls.normalize(song.album.picUrl.ifBlank { covers[song.id].orEmpty() })
                )
            }
    }

    @Serializable
    private data class NetEaseDetailsResponse(val songs: List<NetEaseSong> = emptyList())

    @Serializable
    private data class NetEaseSearchResponse(
        val code: Int = 0,
        val result: NetEaseSearchResult? = null
    )

    @Serializable
    private data class NetEaseSearchResult(
        val songs: List<NetEaseSong> = emptyList(),
        val songCount: Int? = null
    )

    @Serializable
    private data class NetEaseSong(
        val id: Long,
        val name: String,
        val duration: Long = 0,
        @SerialName("artists")
        val artists: List<NetEaseArtist> = emptyList(),
        @SerialName("album")
        val album: NetEaseAlbum = NetEaseAlbum()
    )

    @Serializable
    private data class NetEaseArtist(
        val name: String = ""
    )

    @Serializable
    private data class NetEaseAlbum(
        val name: String = "",
        val picUrl: String = ""
    )
}
