/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.lrclib

import io.github.proify.cloudlyric.CatalogProvider
import io.github.proify.cloudlyric.CatalogPage
import io.github.proify.cloudlyric.CatalogTrack
import io.github.proify.cloudlyric.LyricsProvider
import io.github.proify.cloudlyric.LyricsResult
import io.github.proify.lrckit.EnhanceLrcParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.roundToLong

/**
 * [LrcLibProvider] 是基于 LRCLIB API 实现的歌词提供者。
 */
class LrcLibProvider : LyricsProvider, CatalogProvider {
    companion object {
        const val ID = "LrcLib"

        private const val USER_AGENT: String =
            "CloudLyric (https://github.com/proify/LyricProvider)"

        private const val BASE_URL = "https://lrclib.net/api"

        private const val TIMEOUT = 6000
    }

    override val id: String = ID

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private var catalogQuery = ""
    private var catalogCache: List<LrcLibResponse> = emptyList()
    override suspend fun searchCatalog(query: String, pageSize: Int, cursor: String?): CatalogPage = withContext(Dispatchers.IO) {
        val size = pageSize.coerceIn(1, 50)
        val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        // LRCLIB search has no server pagination. Fetch it once and page the same response.
        if (cursor == null || catalogQuery != query) {
            val body = sendRawRequest("$BASE_URL/search?" + encodeParams(mapOf("q" to query))) ?: error("LRCLIB 搜索暂不可用")
            catalogCache = json.decodeFromString<List<LrcLibResponse>>(body)
            catalogQuery = query
        }
        val songs = catalogCache.drop(offset).take(size)
        CatalogPage(songs.map { song -> CatalogTrack(song.id.toString(), LyricsResult(
            trackName = song.trackName, artistName = song.artistName, albumName = song.albumName,
            durationMs = ((song.duration ?: 0.0) * 1000).roundToLong(), instrumental = song.instrumental)) },
            if (offset + songs.size < catalogCache.size) (offset + size).toString() else null)
    }
    override suspend fun loadLyrics(track: CatalogTrack): LyricsResult? {
        val response = catalogCache.firstOrNull { it.id.toString() == track.key } ?: run {
            val body = sendRawRequest("$BASE_URL/get/${track.key}") ?: error("LRCLIB 歌词获取失败")
            json.decodeFromString<LrcLibResponse>(body)
        }
        val lines = if (!response.syncedLyrics.isNullOrBlank()) EnhanceLrcParser.parse(response.syncedLyrics).lines
            else response.plainLyrics.orEmpty().lines().filter { it.isNotBlank() }.map {
                io.github.proify.lyricon.lyric.model.RichLyricLine(text = it)
            }
        return track.metadata.copy(rich = lines).takeIf { lines.isNotEmpty() }
    }

    override suspend fun search(
        query: String?,
        trackName: String?,
        artistName: String?,
        albumName: String?,
        limit: Int
    ): List<LyricsResult> = withContext(Dispatchers.IO) {
        val queryParams = mutableMapOf<String, String>()
        query?.let { queryParams["q"] = it }
        trackName?.let { queryParams["track_name"] = it }
        artistName?.let { queryParams["artist_name"] = it }
        albumName?.let { queryParams["album_name"] = it }

        if (queryParams["q"] == null && queryParams["track_name"] == null) return@withContext emptyList()

        val fullUrl = "$BASE_URL/search?${encodeParams(queryParams)}"
        val responseBody = sendRawRequest(fullUrl) ?: return@withContext emptyList()

        return@withContext try {
            val response = json.decodeFromString<List<LrcLibResponse>>(responseBody)
            response
                .sortedByDescending { calculateIntegrityScore(it) }
                .take(limit)
                .map {
                    LyricsResult(
                        trackName = it.trackName,
                        artistName = it.artistName,
                        albumName = it.albumName,
                        durationMs = ((it.duration ?: 0.0) * 1000.0).roundToLong(),
                        rich = EnhanceLrcParser.parse(it.syncedLyrics).lines,
                        instrumental = it.instrumental,
                    )
                }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private suspend fun sendRawRequest(urlString: String): String? =
        io.github.proify.cloudlyric.provider.internal.ProviderSupport.request(urlString,
            headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json"),
            connectTimeoutMs = TIMEOUT, readTimeoutMs = TIMEOUT, requireSuccess = true)

    private fun encodeParams(params: Map<String, String>): String {
        return params.map { (key, value) ->
            val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8.name())
            val encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            "$encodedKey=$encodedValue"
        }.joinToString("&")
    }

    private fun calculateIntegrityScore(response: LrcLibResponse): Int {
        var score = 0
        if (!response.trackName.isNullOrBlank()) score += 20
        if (!response.artistName.isNullOrBlank()) score += 20
        if (!response.albumName.isNullOrBlank()) score += 10
        if (!response.syncedLyrics.isNullOrBlank()) score += 50
        return score
    }
}
