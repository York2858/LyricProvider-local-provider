/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.soda

import io.github.proify.cloudlyric.CatalogProvider
import io.github.proify.cloudlyric.CatalogPage
import io.github.proify.cloudlyric.CatalogTrack
import io.github.proify.cloudlyric.LyricsProvider
import io.github.proify.cloudlyric.LyricsResult
import io.github.proify.cloudlyric.toRichLines
import io.github.proify.cloudlyric.provider.internal.ProviderSupport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class SodaProvider : LyricsProvider, CatalogProvider {
    companion object {
        const val ID = "Soda"

        private const val SEARCH_URL = "https://api.qishui.com/luna/pc/search/track"
        private const val TRACK_URL = "https://api.qishui.com/luna/pc/track_v2"
    }

    override val id: String = ID

    override suspend fun searchCatalog(query: String, pageSize: Int, cursor: String?): CatalogPage {
        val requested = cursor ?: "0"
        val body = ProviderSupport.httpGet("$SEARCH_URL?" + ProviderSupport.encodeParams(mapOf(
            "q" to query, "cursor" to requested, "search_method" to "input", "aid" to "386088",
            "device_platform" to "web", "channel" to "pc_web")),
            headers = mapOf("User-Agent" to ProviderSupport.DEFAULT_USER_AGENT)) ?: error("汽水音乐搜索暂不可用")
        val response = ProviderSupport.json.decodeFromString<SodaSearchResponse>(body)
        val root = ProviderSupport.json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
        fun value(key: String) = (root?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content
        val next = value("next_cursor") ?: value("cursor")
        val hasMore = value("has_more") in listOf("true", "1")
        val songs = response.resultGroups.firstOrNull()?.data.orEmpty().map { it.entity.track }.filter { it.id.isNotBlank() }
        // Return every item from the service's cursor page. Its page size is server-controlled.
        return CatalogPage(songs.map { track -> CatalogTrack(track.id, LyricsResult(
            trackName = track.name, artistName = track.artists.joinToString(", ") { it.name },
            albumName = track.album.name, durationMs = track.duration.toLong(),
            artworkUrl = io.github.proify.cloudlyric.CoverUrls.soda(track.album.cover?.urls?.firstOrNull(), track.album.cover?.uri))) },
            next?.takeIf { hasMore && it != requested && songs.isNotEmpty() })
    }
    override suspend fun loadLyrics(track: CatalogTrack): LyricsResult? {
        val body = ProviderSupport.httpGet("$TRACK_URL?" + ProviderSupport.encodeParams(mapOf(
            "track_id" to track.key, "media_type" to "track", "aid" to "386088",
            "device_platform" to "web", "channel" to "pc_web")),
            headers = mapOf("User-Agent" to ProviderSupport.DEFAULT_USER_AGENT)) ?: error("汽水音乐歌词获取失败")
        val lyric = ProviderSupport.json.decodeFromString<SodaTrackResponse>(body).lyric ?: return null
        val lines = SodaLyricParser.parse(lyric.content)
        return track.metadata.copy(rich = lines.toRichLines(transLrc = lyric.translations?.cn)).takeIf { lines.isNotEmpty() }
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

        val url = "$SEARCH_URL?${ProviderSupport.encodeParams(
            mapOf(
                "q" to keyword,
                "cursor" to "0",
                "search_method" to "input",
                "aid" to "386088",
                "device_platform" to "web",
                "channel" to "pc_web"
            )
        )}"

        val body = ProviderSupport.httpGet(
            url = url,
            headers = mapOf("User-Agent" to ProviderSupport.DEFAULT_USER_AGENT)
        ) ?: return emptyList()

        val response = runCatching {
            ProviderSupport.json.decodeFromString<SodaSearchResponse>(body)
        }.getOrNull() ?: return emptyList()

        return response.resultGroups.firstOrNull()?.data.orEmpty()
            .take(limit)
            .mapNotNull { item ->
                val track = item.entity.track
                if (track.id.isBlank()) return@mapNotNull null

                val trackBody = ProviderSupport.httpGet(
                    url = "$TRACK_URL?${ProviderSupport.encodeParams(
                        mapOf(
                            "track_id" to track.id,
                            "media_type" to "track",
                            "aid" to "386088",
                            "device_platform" to "web",
                            "channel" to "pc_web"
                        )
                    )}",
                    headers = mapOf("User-Agent" to ProviderSupport.DEFAULT_USER_AGENT)
                ) ?: return@mapNotNull null

                val trackResponse = runCatching {
                    ProviderSupport.json.decodeFromString<SodaTrackResponse>(trackBody)
                }.getOrNull() ?: return@mapNotNull null

                val lyric = trackResponse.lyric ?: return@mapNotNull null
                val originalLines = SodaLyricParser.parse(lyric.content)
                if (originalLines.isEmpty()) return@mapNotNull null

                LyricsResult(
                    trackName = track.name,
                    artistName = track.artists.joinToString(", ") { it.name },
                    albumName = track.album.name,
                    durationMs = track.duration.toLong(),
                    rich = originalLines.toRichLines(transLrc = lyric.translations?.cn),
                    artworkUrl = io.github.proify.cloudlyric.CoverUrls.soda(track.album.cover?.urls?.firstOrNull(), track.album.cover?.uri)
                )
            }
    }

    @Serializable
    private data class SodaSearchResponse(
        @SerialName("result_groups")
        val resultGroups: List<SodaResultGroup> = emptyList()
    )

    @Serializable
    private data class SodaResultGroup(
        val data: List<SodaSearchItem> = emptyList()
    )

    @Serializable
    private data class SodaSearchItem(
        val entity: SodaSearchEntity
    )

    @Serializable
    private data class SodaSearchEntity(
        val track: SodaTrack
    )

    @Serializable
    private data class SodaTrack(
        val id: String = "",
        val name: String = "",
        val duration: Int = 0,
        val artists: List<SodaArtist> = emptyList(),
        val album: SodaAlbum = SodaAlbum()
    )

    @Serializable
    private data class SodaArtist(
        val name: String = ""
    )

    @Serializable
    private data class SodaAlbum(
        val name: String = "",
        @SerialName("url_cover") val cover: SodaCover? = null
    )
    @Serializable private data class SodaCover(val uri: String = "", val urls: List<String> = emptyList())

    @Serializable
    private data class SodaTrackResponse(
        val lyric: SodaLyric? = null
    )

    @Serializable
    private data class SodaLyric(
        val content: String = "",
        val translations: SodaTranslations? = null
    )

    @Serializable
    private data class SodaTranslations(
        val cn: String? = null
    )
}
