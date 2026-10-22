/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.apple

import io.github.proify.cloudlyric.CatalogProvider
import io.github.proify.cloudlyric.CatalogAccessException
import io.github.proify.cloudlyric.CatalogPage
import io.github.proify.cloudlyric.CatalogTrack
import io.github.proify.cloudlyric.LyricsProvider
import io.github.proify.cloudlyric.LyricsResult
import io.github.proify.cloudlyric.provider.internal.ProviderSupport
import io.github.proify.extensions.net.HttpStatusException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale
import java.io.IOException

class AppleMusicProvider(
    private val config: AppleMusicConfig,
    private val developerTokenProvider: AppleMusicDeveloperTokenProvider = AppleMusicDeveloperTokenProvider.shared,
    private val httpGet: suspend (String, Map<String, String>) -> String = { url, headers ->
        ProviderSupport.httpGetOrThrow(url, headers, readTimeoutMs = 12_000)
    }
) : LyricsProvider, CatalogProvider {
    companion object {
        const val ID = "AppleMusic"

        private const val BASE_URL = "https://amp-api.music.apple.com"
        private const val PLAIN_LYRICS_TYPE = "lyrics"
        private const val SYLLABLE_LYRICS_TYPE = "syllable-lyrics"
    }

    override val id: String = ID

    override suspend fun searchCatalog(query: String, pageSize: Int, cursor: String?): CatalogPage {
        val size = pageSize.coerceIn(1, 25)
        val offset = cursor?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val response = requestSearch(query, size, offset) ?: error("Apple Music 搜索暂不可用")
        val songs = response.results.songs?.data.orEmpty()
        return CatalogPage(songs.map { song -> CatalogTrack(song.id, LyricsResult(
            trackName = song.attributes.name, artistName = song.attributes.artistName,
            albumName = song.attributes.albumName, durationMs = song.attributes.durationInMillis,
            artworkUrl = io.github.proify.cloudlyric.CoverUrls.normalize(song.attributes.artwork?.url)),
            mapOf("hasLyrics" to song.attributes.hasLyrics.toString(),
                "synced" to song.attributes.hasTimeSyncedLyrics.toString())) },
            if (songs.size >= size) (offset + size).toString() else null)
    }
    override suspend fun loadLyrics(track: CatalogTrack): LyricsResult? {
        if (!config.isConfigured()) error("请先配置 Apple Music 账号")
        val lines = buildLyricsTypePriority(track.hints["synced"] == "true").firstNotNullOfOrNull { type ->
            requestLyrics(track.key, type)
        }
        if (lines == null && track.hints["hasLyrics"] != "false") error("歌词获取失败，请重试或检查账号")
        return lines?.takeIf { it.isNotEmpty() }?.let { track.metadata.copy(rich = it) }
    }

    override suspend fun search(
        query: String?,
        trackName: String?,
        artistName: String?,
        albumName: String?,
        limit: Int
    ): List<LyricsResult> {
        if (!config.isConfigured()) return emptyList()

        val safeLimit = limit.coerceAtLeast(1)
        val candidateKeywords = buildSearchKeywords(
            query = query,
            trackName = trackName,
            artistName = artistName,
            albumName = albumName
        )
        if (candidateKeywords.isEmpty()) return emptyList()

        val searchLimit = maxOf(safeLimit * 5, 20).coerceAtMost(50)
        val candidateSongs = candidateKeywords
            .flatMap { keyword ->
                val searchResponse = requestSearch(keyword, searchLimit)
                    ?: return@flatMap emptyList()
                searchResponse.results.songs?.data.orEmpty()
            }
            .distinctBy { it.id }
            .sortedByDescending { song ->
                scoreSongMatch(
                    targetTrackName = trackName,
                    targetArtistName = artistName,
                    targetAlbumName = albumName,
                    song = song
                )
            }
            .take(maxOf(safeLimit * 4, 12))

        return candidateSongs.mapNotNull { song ->
            val lyricsTypes = buildLyricsTypePriority(song.attributes.hasTimeSyncedLyrics)
            val richLines = lyricsTypes.firstNotNullOfOrNull { lyricsType ->
                requestLyrics(
                    songId = song.id,
                    lyricsType = lyricsType
                )
            } ?: return@mapNotNull null

            LyricsResult(
                trackName = song.attributes.name,
                artistName = song.attributes.artistName,
                albumName = song.attributes.albumName,
                durationMs = song.attributes.durationInMillis,
                rich = richLines,
                artworkUrl = io.github.proify.cloudlyric.CoverUrls.normalize(song.attributes.artwork?.url)
            )
        }.take(safeLimit)
    }

    private suspend fun requestSearch(
        keyword: String,
        limit: Int,
        offset: Int = 0
    ): AppleMusicSearchResponse? {
        val storefront = normalizeStorefront(config.storefront)
        val language = normalizeLanguage(config.language)

        val url = "$BASE_URL/v1/catalog/$storefront/search?${ProviderSupport.encodeParams(
            mapOf(
                "term" to keyword,
                "types" to "songs",
                "limit" to limit.toString(),
                "offset" to offset.toString(),
                "l" to language
            )
        )}"

        val body = authenticatedGet(url) ?: return null

        return runCatching {
            ProviderSupport.json.decodeFromString<AppleMusicSearchResponse>(body)
        }.getOrNull()
    }

    private suspend fun requestLyrics(
        songId: String,
        lyricsType: String
    ): List<io.github.proify.lyricon.lyric.model.RichLyricLine>? {
        val mediaUserToken = config.mediaUserToken.trim().takeIf { it.isNotBlank() } ?: return null
        val storefront = normalizeStorefront(config.storefront)
        val language = normalizeLanguage(config.language)

        val url = "$BASE_URL/v1/catalog/$storefront/songs/$songId/$lyricsType?${ProviderSupport.encodeParams(
            mapOf(
                "l" to language,
                "extend" to "ttmlLocalizations"
            )
        )}"

        val body = authenticatedGet(url, mediaUserToken) ?: return null

        val response = runCatching {
            ProviderSupport.json.decodeFromString<AppleMusicLyricsResponse>(body)
        }.getOrNull() ?: return null

        val attributes = response.data.firstOrNull()?.attributes ?: return null
        val ttml = selectPreferredTtml(
            ttml = attributes.ttml,
            localizedTtml = attributes.ttmlLocalizations,
            language = language
        )
            ?: return null

        return AppleMusicTtmlParser.parse(ttml).takeIf { it.isNotEmpty() }
    }

    private suspend fun resolveDeveloperToken(forceRefreshToken: Boolean): String {
        val manualToken = config.developerToken.trim()
        if (manualToken.isNotBlank()) return manualToken
        return developerTokenProvider.getToken(forceRefreshToken)
    }

    private suspend fun authenticatedGet(url: String, mediaUserToken: String? = null): String? {
        try {
            suspend fun request(refresh: Boolean): String = httpGet(url, buildMap {
                put("Authorization", "Bearer ${resolveDeveloperToken(refresh)}")
                put("Origin", "https://music.apple.com")
                put("Referer", "https://music.apple.com/")
                put("User-Agent", ProviderSupport.DEFAULT_USER_AGENT)
                if (mediaUserToken != null) put("Cookie", "media-user-token=$mediaUserToken")
            })
            return try { request(false) }
            catch (error: HttpStatusException) {
                // A slow connection or missing lyrics cannot be fixed by downloading a new token.
                if (error.statusCode !in listOf(401, 403) || config.developerToken.isNotBlank()) throw error
                request(true)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: HttpStatusException) {
            if (error.statusCode == 404) return null
            val message = when (error.statusCode) {
                401, 403 -> "Apple Music 认证失败，请检查账号或令牌配置"
                429 -> "Apple Music 请求过于频繁，请稍后重试"
                else -> "Apple Music 服务暂不可用（HTTP ${error.statusCode}），请重试"
            }
            throw CatalogAccessException(message)
        } catch (_: IOException) {
            throw CatalogAccessException("Apple Music 网络连接失败，请稍后重试")
        }
    }

    private fun buildLyricsTypePriority(hasTimeSyncedLyrics: Boolean): List<String> {
        val preferred = config.lyricsTypePreference.trim().lowercase()
        return when (preferred) {
            PLAIN_LYRICS_TYPE -> listOf(PLAIN_LYRICS_TYPE, SYLLABLE_LYRICS_TYPE)
            SYLLABLE_LYRICS_TYPE -> listOf(SYLLABLE_LYRICS_TYPE, PLAIN_LYRICS_TYPE)
            else -> if (hasTimeSyncedLyrics) {
                listOf(SYLLABLE_LYRICS_TYPE, PLAIN_LYRICS_TYPE)
            } else {
                listOf(PLAIN_LYRICS_TYPE, SYLLABLE_LYRICS_TYPE)
            }
        }
    }

    private fun normalizeStorefront(raw: String): String {
        val storefront = raw.trim().lowercase().filter { it.isLetter() }
        return storefront.takeIf { it.length == 2 } ?: "us"
    }

    private fun normalizeLanguage(raw: String): String {
        val value = raw.trim()
        return value.ifBlank { "en-US" }
    }

    private fun buildSearchKeywords(
        query: String?,
        trackName: String?,
        artistName: String?,
        albumName: String?
    ): List<String> {
        val keywords = linkedSetOf<String>()

        fun addKeyword(vararg parts: String?) {
            val keyword = parts.asSequence()
                .map { it?.trim().orEmpty() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(" ")
                .trim()
            if (keyword.isNotEmpty()) {
                keywords += keyword
            }
        }

        query?.trim()?.takeIf { it.isNotEmpty() }?.let(keywords::add)
        addKeyword(trackName, artistName, albumName)
        addKeyword(trackName, artistName)
        addKeyword(trackName, albumName)
        addKeyword(trackName)
        addKeyword(artistName, trackName)

        return keywords.toList()
    }

    private fun scoreSongMatch(
        targetTrackName: String?,
        targetArtistName: String?,
        targetAlbumName: String?,
        song: AppleMusicSongData
    ): Int {
        var score = 0
        score += stringMatchScore(targetTrackName, song.attributes.name, exactScore = 120, containsScore = 70, tokenScore = 40)
        score += stringMatchScore(targetArtistName, song.attributes.artistName, exactScore = 90, containsScore = 55, tokenScore = 30)
        score += stringMatchScore(targetAlbumName, song.attributes.albumName, exactScore = 50, containsScore = 25, tokenScore = 12)
        if (song.attributes.hasTimeSyncedLyrics) score += 12
        if (song.attributes.hasLyrics) score += 6
        return score
    }

    private fun stringMatchScore(
        targetValue: String?,
        actualValue: String?,
        exactScore: Int,
        containsScore: Int,
        tokenScore: Int
    ): Int {
        val target = normalizeMatchText(targetValue) ?: return 0
        val actual = normalizeMatchText(actualValue) ?: return 0

        if (target == actual) return exactScore
        if (actual.contains(target) || target.contains(actual)) return containsScore

        val targetTokens = target.split(" ").filter { it.isNotBlank() }.toSet()
        val actualTokens = actual.split(" ").filter { it.isNotBlank() }.toSet()
        return if (targetTokens.intersect(actualTokens).isNotEmpty()) tokenScore else 0
    }

    private fun normalizeMatchText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.lowercase(Locale.US)
            .replace("&", " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { null }
    }

    internal fun selectPreferredTtml(
        ttml: String,
        localizedTtml: String,
        language: String
    ): String? {
        val normalizedLanguage = normalizeLanguage(language)
        return if (shouldPreferLocalizedTtml(normalizedLanguage)) {
            localizedTtml.takeIf { it.isNotBlank() }
                ?: ttml.takeIf { it.isNotBlank() }
        } else {
            ttml.takeIf { it.isNotBlank() }
                ?: localizedTtml.takeIf { it.isNotBlank() }
        }
    }

    internal fun shouldPreferLocalizedTtml(language: String): Boolean {
        val locale = Locale.forLanguageTag(language.trim())
        return locale.language.lowercase(Locale.US) != "en"
    }

    @Serializable
    private data class AppleMusicSearchResponse(
        val results: AppleMusicSearchResults = AppleMusicSearchResults()
    )

    @Serializable
    private data class AppleMusicSearchResults(
        val songs: AppleMusicSongResults? = null
    )

    @Serializable
    private data class AppleMusicSongResults(
        val data: List<AppleMusicSongData> = emptyList()
    )

    @Serializable
    private data class AppleMusicSongData(
        val id: String,
        val attributes: AppleMusicSongAttributes = AppleMusicSongAttributes()
    )

    @Serializable
    private data class AppleMusicSongAttributes(
        val name: String = "",
        @SerialName("artistName")
        val artistName: String = "",
        @SerialName("albumName")
        val albumName: String = "",
        @SerialName("durationInMillis")
        val durationInMillis: Long = 0L,
        @SerialName("hasLyrics")
        val hasLyrics: Boolean = false,
        @SerialName("hasTimeSyncedLyrics")
        val hasTimeSyncedLyrics: Boolean = false,
        val artwork: AppleMusicArtwork? = null
    )
    @Serializable private data class AppleMusicArtwork(val url: String = "")

    @Serializable
    private data class AppleMusicLyricsResponse(
        val data: List<AppleMusicLyricsData> = emptyList()
    )

    @Serializable
    private data class AppleMusicLyricsData(
        val attributes: AppleMusicLyricsAttributes = AppleMusicLyricsAttributes()
    )

    @Serializable
    private data class AppleMusicLyricsAttributes(
        val ttml: String = "",
        @SerialName("ttmlLocalizations")
        val ttmlLocalizations: String = ""
    )
}
