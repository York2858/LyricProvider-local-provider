/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.qq

import io.github.proify.cloudlyric.CatalogProvider
import io.github.proify.cloudlyric.CatalogPage
import io.github.proify.cloudlyric.CatalogTrack
import io.github.proify.cloudlyric.LyricsProvider
import io.github.proify.cloudlyric.LyricsResult
import io.github.proify.qrckit.QrcDownloader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

class QQMusicProvider : LyricsProvider, CatalogProvider {
    companion object {
        const val ID = "QQMusicProvider"
    }

    override val id: String = ID

    override suspend fun searchCatalog(query: String, pageSize: Int, cursor: String?): CatalogPage {
        val size = pageSize.coerceIn(1, 50)
        val page = cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val response = MusicApiService.searchWithKeyword(query, resultNum = size, pageNum = page)
            ?: error("QQ 音乐搜索暂不可用")
        return parseCatalogResponse(response, size, page)
    }
    internal fun parseCatalogResponse(response: kotlinx.serialization.json.JsonElement, size: Int, page: Int): CatalogPage {
        val songs = jsonParser.decodeFromJsonElement<QQSearchResponse>(response).list
        return CatalogPage(songs.filter { it.id > 0 }.map { song ->
            song.catalogTrack().copy(versions = song.groupedSongs.distinctBy { it.id }
                .filter { it.id > 0 && it.id != song.id }.map { it.catalogTrack() })
        }, if (songs.size >= size) (page + 1).toString() else null)
    }
    private fun QQSongItem.catalogTrack() = CatalogTrack(id.toString(), LyricsResult(
        trackName = title, artistName = singer.joinToString(", ") { it.name },
        albumName = album?.name.orEmpty(), durationMs = interval * 1000L,
        artworkUrl = io.github.proify.cloudlyric.CoverUrls.qq(album?.mid, album?.id ?: 0)))
    override suspend fun loadLyrics(track: CatalogTrack): LyricsResult? {
        val lines = QrcDownloader.downloadLyricsCancellable(track.key).parsedLyric.richLyricLines
        return track.metadata.copy(rich = lines).takeIf { lines.isNotEmpty() }
    }

    override suspend fun search(
        query: String?,
        trackName: String?,
        artistName: String?,
        albumName: String?,
        limit: Int
    ): List<LyricsResult> {
        val key = listOfNotNull(query, trackName, artistName, albumName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (key.isBlank()) return emptyList()

        val element =
            MusicApiService.searchWithKeyword(key, resultNum = limit) ?: return emptyList()

        val searchResponse = try {
            jsonParser.decodeFromJsonElement<QQSearchResponse>(element)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
        val take = searchResponse.list.take(limit)
        //  println("QQMusicProvider: ${take.size} ,source ${searchResponse.list.size}")
        return take.mapNotNull { song ->
            try {
                val response = QrcDownloader.downloadLyricsCancellable(song.id.toString())
                val richLyricLines = response.parsedLyric.richLyricLines

                LyricsResult(
                    trackName = song.title,
                    albumName = song.album?.name.orEmpty(),
                    artistName = song.singer.joinToString(", ") { it.name },
                    durationMs = song.interval * 1000L,
                    rich = richLyricLines,
                    artworkUrl = io.github.proify.cloudlyric.CoverUrls.qq(song.album?.mid, song.album?.id ?: 0)
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Serializable
    private data class QQSearchResponse(
        val list: List<QQSongItem> = emptyList()
    )

    @Serializable
    private data class QQSongItem(
        val id: Long,
        @SerialName("title")
        val title: String = "",
        val interval: Long = 0L,
        val album: QQAlbum? = null,
        val singer: List<QQSinger> = emptyList(),
        @SerialName("grp") val groupedSongs: List<QQSongItem> = emptyList()
    )

    @Serializable
    private data class QQAlbum(val name: String = "", val mid: String = "", val id: Long = 0)

    @Serializable
    private data class QQSinger(val name: String)
}
