/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.kugou

import io.github.proify.cloudlyric.CatalogProvider
import io.github.proify.cloudlyric.CatalogPage
import io.github.proify.cloudlyric.CatalogTrack
import io.github.proify.cloudlyric.LyricsProvider
import io.github.proify.cloudlyric.LyricsResult
import io.github.proify.cloudlyric.provider.internal.ProviderSupport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Inflater

class KuGouProvider : LyricsProvider, CatalogProvider {
    companion object {
        const val ID = "KuGou"

        private const val SIGN_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
        private const val SEARCH_URL = "http://complexsearch.kugou.com/v2/search/song"
        private const val LYRIC_SEARCH_URL = "https://lyrics.kugou.com/v1/search"
        private const val LYRIC_DOWNLOAD_URL = "http://lyrics.kugou.com/download"

        private val KRC_KEY = byteArrayOf(
            64, 71, 97, 119, 94, 50, 116, 71,
            81, 54, 49, 45, -50, -46, 110, 105
        )
    }

    override val id: String = ID

    override suspend fun searchCatalog(query: String, pageSize: Int, cursor: String?): CatalogPage {
        val size = pageSize.coerceIn(1, 10)
        val page = cursor?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val params = buildSignedParams(mapOf("keyword" to query, "page" to page.toString(), "pagesize" to size.toString()))
        val body = ProviderSupport.httpGet("$SEARCH_URL?" + ProviderSupport.encodeParams(params),
            headers = defaultHeaders("SearchSong", includeRouter = true)) ?: error("酷狗搜索暂不可用")
        return parseCatalogResponse(body, size, page)
    }
    internal fun parseCatalogResponse(body: String, size: Int, page: Int): CatalogPage {
        val response = ProviderSupport.json.decodeFromString<KugouSearchResponse>(body)
        check(response.errorCode == 0) { "酷狗搜索暂不可用" }
        val songs = response.data?.lists.orEmpty()
        return CatalogPage(songs.filter { it.versionKey().isNotBlank() }.map { song ->
            val versions = linkedMapOf<String, KugouSong>()
            fun collect(item: KugouSong) {
                (item.groupedSongs + item.legacyGroupedSongs).forEach { child ->
                    val key = child.versionKey()
                    if (key.isNotBlank() && versions.putIfAbsent(key, child) == null) collect(child)
                }
            }
            collect(song)
            song.catalogTrack().copy(versions = versions.filterKeys { it != song.versionKey() }.values.map { it.catalogTrack() })
        }, if (songs.size >= size) (page + 1).toString() else null)
    }
    private fun KugouSong.versionKey() = id.ifBlank { fileHash }
    private fun KugouSong.catalogTrack() = CatalogTrack(versionKey(), LyricsResult(
        trackName = songName, artistName = singers.joinToString(", ") { it.name },
        albumName = albumName, durationMs = duration * 1000L,
        artworkUrl = io.github.proify.cloudlyric.CoverUrls.normalize(image)), mapOf("hash" to fileHash, "audioId" to id))
    override suspend fun loadLyrics(track: CatalogTrack): LyricsResult? {
        val hash = track.hints["hash"].orEmpty().takeIf { it.isNotBlank() } ?: return null
        val params = buildSignedParams(mapOf("album_audio_id" to track.hints["audioId"].orEmpty(),
            "duration" to (track.metadata.durationMs / 1000).toString(), "hash" to hash,
            "keyword" to "${track.metadata.artistName} - ${track.metadata.trackName}", "lrctxt" to "1", "man" to "no"), module = "Lyric")
        val body = ProviderSupport.httpGet("$LYRIC_SEARCH_URL?" + ProviderSupport.encodeParams(params),
            headers = defaultHeaders("Lyric")) ?: error("酷狗歌词查询失败")
        val response = ProviderSupport.json.decodeFromString<KugouLyricSearchResponse>(body)
        check(response.errorCode == 0) { "酷狗歌词查询失败" }
        val lyric = response.candidates.firstOrNull() ?: return null
        val download = buildSignedParams(mapOf("accesskey" to lyric.accesskey, "charset" to "utf8", "client" to "mobi",
            "fmt" to "krc", "id" to lyric.id, "ver" to "1"), module = "Lyric")
        val raw = ProviderSupport.httpGet("$LYRIC_DOWNLOAD_URL?" + ProviderSupport.encodeParams(download),
            headers = defaultHeaders("Lyric")) ?: error("酷狗歌词下载失败")
        val content = ProviderSupport.json.decodeFromString<KugouLyricContent>(raw)
        val decoded = decodeLyricContent(content) ?: error("酷狗歌词解析失败")
        val lines = KugouKrcParser.parse(decoded)
        return track.metadata.copy(rich = lines).takeIf { lines.isNotEmpty() }
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

        val pageSize = limit.coerceIn(1, 10)
        val searchParams = buildSignedParams(
            mapOf(
                "keyword" to keyword,
                "page" to "1",
                "pagesize" to pageSize.toString()
            )
        )
        val searchBody = ProviderSupport.httpGet(
            url = "$SEARCH_URL?${ProviderSupport.encodeParams(searchParams)}",
            headers = defaultHeaders(module = "SearchSong", includeRouter = true)
        ) ?: return emptyList()

        val response = runCatching {
            ProviderSupport.json.decodeFromString<KugouSearchResponse>(searchBody)
        }.getOrNull() ?: return emptyList()

        return response.data?.lists.orEmpty()
            .take(pageSize)
            .mapNotNull { song ->
                val hash = song.fileHash.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val lyricSearchParams = buildSignedParams(
                    customParams = mapOf(
                        "album_audio_id" to song.id,
                        "duration" to song.duration.toString(),
                        "hash" to hash,
                        "keyword" to "${song.singers.joinToString(" / ") { it.name }} - ${song.songName}",
                        "lrctxt" to "1",
                        "man" to "no"
                    ),
                    module = "Lyric"
                )
                val lyricSearchBody = ProviderSupport.httpGet(
                    url = "$LYRIC_SEARCH_URL?${ProviderSupport.encodeParams(lyricSearchParams)}",
                    headers = defaultHeaders(module = "Lyric")
                ) ?: return@mapNotNull null

                val lyricSearchResponse = runCatching {
                    ProviderSupport.json.decodeFromString<KugouLyricSearchResponse>(lyricSearchBody)
                }.getOrNull() ?: return@mapNotNull null

                val candidate = lyricSearchResponse.candidates.firstOrNull() ?: return@mapNotNull null
                val downloadParams = buildSignedParams(
                    customParams = mapOf(
                        "accesskey" to candidate.accesskey,
                        "charset" to "utf8",
                        "client" to "mobi",
                        "fmt" to "krc",
                        "id" to candidate.id,
                        "ver" to "1"
                    ),
                    module = "Lyric"
                )
                val downloadBody = ProviderSupport.httpGet(
                    url = "$LYRIC_DOWNLOAD_URL?${ProviderSupport.encodeParams(downloadParams)}",
                    headers = defaultHeaders(module = "Lyric")
                ) ?: return@mapNotNull null

                val lyricContent = runCatching {
                    ProviderSupport.json.decodeFromString<KugouLyricContent>(downloadBody)
                }.getOrNull() ?: return@mapNotNull null

                val rawLyric = decodeLyricContent(lyricContent) ?: return@mapNotNull null
                val richLines = KugouKrcParser.parse(rawLyric)
                if (richLines.isEmpty()) return@mapNotNull null

                LyricsResult(
                    trackName = song.songName,
                    artistName = song.singers.joinToString(", ") { it.name },
                    albumName = song.albumName,
                    durationMs = song.duration * 1000L,
                    rich = richLines,
                    artworkUrl = io.github.proify.cloudlyric.CoverUrls.normalize(song.image)
                )
            }
    }

    private fun defaultHeaders(module: String, includeRouter: Boolean = false): Map<String, String> {
        val headers = linkedMapOf(
            "User-Agent" to "Android14-1070-11070-201-0-$module-wifi",
            "KG-Rec" to "1",
            "KG-RC" to "1",
            "KG-CLIENTTIMEMS" to System.currentTimeMillis().toString()
        )
        if (includeRouter) {
            headers["x-router"] = "complexsearch.kugou.com"
        }
        return headers
    }

    private fun buildSignedParams(
        customParams: Map<String, String>,
        body: String = "",
        module: String = "Search"
    ): Map<String, String> {
        val currentTimeMs = System.currentTimeMillis()
        val params = linkedMapOf<String, String>()
        if (module == "Lyric") {
            params["appid"] = "3116"
            params["clientver"] = "11070"
        } else {
            params["userid"] = "0"
            params["appid"] = "3116"
            params["token"] = ""
            params["clienttime"] = (currentTimeMs / 1000).toString()
            params["iscorrection"] = "1"
            params["uuid"] = "-"
            params["mid"] = md5(currentTimeMs.toString())
            params["dfid"] = "-"
            params["clientver"] = "11070"
            params["platform"] = "AndroidFilter"
        }
        params.putAll(customParams)

        val sortedString = params.toSortedMap().entries.joinToString(separator = "") { (key, value) ->
            "$key=$value"
        }
        params["signature"] = md5("$SIGN_SALT$sortedString$body$SIGN_SALT")
        return params
    }

    private fun decodeLyricContent(content: KugouLyricContent): String? {
        return try {
            if (content.contenttype == 2) {
                String(Base64.getDecoder().decode(content.content), StandardCharsets.UTF_8)
            } else {
                decryptKrc(content.content)
            }.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptKrc(base64Content: String): String {
        val encrypted = Base64.getDecoder().decode(base64Content)
        if (encrypted.size <= 4) return ""

        val data = encrypted.copyOfRange(4, encrypted.size)
        for (index in data.indices) {
            data[index] = (data[index].toInt() xor KRC_KEY[index % KRC_KEY.size].toInt()).toByte()
        }

        val inflater = Inflater()
        inflater.setInput(data)
        val buffer = ByteArray(4096)
        val output = java.io.ByteArrayOutputStream()
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count <= 0) break
            output.write(buffer, 0, count)
        }
        inflater.end()
        return output.toString(StandardCharsets.UTF_8.name())
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    @Serializable
    private data class KugouSearchResponse(
        val status: Int = 0,
        @SerialName("error_code")
        val errorCode: Int = 0,
        val data: KugouSearchData? = null
    )

    @Serializable
    private data class KugouSearchData(
        val lists: List<KugouSong> = emptyList()
    )

    @Serializable
    private data class KugouSong(
        @SerialName("ID")
        val id: String = "",
        @SerialName("FileHash")
        val fileHash: String = "",
        @SerialName("SongName")
        val songName: String = "",
        @SerialName("Singers")
        val singers: List<KugouSinger> = emptyList(),
        @SerialName("AlbumName")
        val albumName: String = "",
        @SerialName("Duration")
        val duration: Int = 0,
        @SerialName("Image") val image: String = "",
        @SerialName("Grp") val groupedSongs: List<KugouSong> = emptyList(),
        @SerialName("group") val legacyGroupedSongs: List<KugouSong> = emptyList()
    )

    @Serializable
    private data class KugouSinger(
        val name: String = ""
    )

    @Serializable
    private data class KugouLyricSearchResponse(
        val status: Int = 0,
        @SerialName("error_code")
        val errorCode: Int = 0,
        val candidates: List<KugouLyricCandidate> = emptyList()
    )

    @Serializable
    private data class KugouLyricCandidate(
        val id: String,
        val accesskey: String = ""
    )

    @Serializable
    private data class KugouLyricContent(
        val content: String = "",
        val fmt: String = "",
        val contenttype: Int = 0
    )
}
