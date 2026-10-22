/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.localprovider.xposed

import io.github.proify.lyricon.localprovider.lyrics.LyricTiming
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.cloudlyric.CloudLyrics
import io.github.proify.cloudlyric.provider.LyricsConfig
import io.github.proify.cloudlyric.provider.apple.AppleMusicProvider
import io.github.proify.cloudlyric.provider.qq.QQMusicProvider
import io.github.proify.lyricon.localprovider.model.ProviderLyrics
import io.github.proify.lyricon.localprovider.model.LyricsResult
import io.github.proify.lyricon.localprovider.local.ExternalLrcProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import io.github.proify.lyricon.localprovider.data.RuntimeBridge

object DownloadManager {
    private const val TAG = "LocalProvider"
    private const val EARLY_ACCEPT_WORD_LEVEL_SCORE = 175

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentTask: Job? = null
    private val externalProvider = ExternalLrcProvider()
    private val requestGeneration = java.util.concurrent.atomic.AtomicInteger()
    private var appleMusicProvider: AppleMusicProvider? = null
    private var appleMusicConfigSignature: String? = null

    @Volatile
    var currentAudioPath: String? = null
        private set

    fun setCurrentAudioPath(path: String?) {
        currentAudioPath = path
    }

    private data class CloudCandidate(
        val item: io.github.proify.cloudlyric.ProviderLyrics,
        val finalScore: Int,
        val titleScore: Int,
        val artistScore: Int,
        val albumScore: Int,
        val durationScore: Int,
        val isWordLevel: Boolean
    )

    private data class ProviderBatchResult(
        val providerId: String,
        val candidates: List<CloudCandidate>
    )

    private fun createCloudProviders(): MutableList<io.github.proify.cloudlyric.LyricsProvider> {
        val providers = LyricsConfig.ALL_PROVIDERS.filter { it.id !in RuntimeBridge.settings.disabledSources }.toMutableList()
        val appleConfig = AppleMusicConfigLoader.loadConfig()
        if (appleConfig != null) {
            val signature = listOf(
                appleConfig.storefront,
                appleConfig.mediaUserToken,
                appleConfig.language,
                appleConfig.developerToken,
                appleConfig.lyricsTypePreference
            ).joinToString("|")
            if (appleMusicProvider == null || appleMusicConfigSignature != signature) {
                appleMusicProvider = AppleMusicProvider(appleConfig)
                appleMusicConfigSignature = signature
            }
            appleMusicProvider?.let(providers::add)
        } else {
            appleMusicProvider = null
            appleMusicConfigSignature = null
        }
        return providers
    }

    private fun createCloudLyrics(): CloudLyrics {
        val providers = createCloudProviders()
        return CloudLyrics(
            providers = providers,
            logger = { message ->
                YLog.warn(tag = TAG, msg = message)
            }
        )
    }

    fun search(downloadCallback: DownloadCallback, block: SearchOptions.() -> Unit) {
        val generation = requestGeneration.incrementAndGet()
        val guardedCallback = object : DownloadCallback {
            override fun onDownloadFinished(response: List<ProviderLyrics>) {
                if (generation == requestGeneration.get()) downloadCallback.onDownloadFinished(response)
            }
            override fun onDownloadFailed(e: Exception) {
                if (generation == requestGeneration.get()) downloadCallback.onDownloadFailed(e)
            }
        }
        currentTask = scope.launch {
            val options = SearchOptions().apply(block)
            RuntimeBridge.setTrackMetadata(options.trackName, options.artistName, options.albumName, options.durationMs)
            val path = options.audioFilePath ?: currentAudioPath
            val settings = RuntimeBridge.settings

            // 【新增拦截逻辑】：过滤播放器广播的无效加载状态
            if (isInvalidMetadata(options)) {
                guardedCallback.onDownloadFinished(emptyList())
                return@launch
            }

            var fallbackLocalResult: ProviderLyrics? = null
            val catalogMatch = RuntimeBridge.find(options.trackName, options.artistName, path)
            if (catalogMatch != null) {
                val (song, manual) = catalogMatch
                val result = LyricsResult(trackName = options.trackName, artistName = options.artistName,
                    albumName = options.albumName, rich = song.lyrics.orEmpty())
                val payload = ProviderLyrics(CloudProviderPlaceholder(if (manual) "手动选择" else "目录歌词"), result)
                if (manual || !settings.wordFirst || containsWordLevelLyrics(result)) {
                    guardedCallback.onDownloadFinished(listOf(payload))
                    return@launch
                }
                fallbackLocalResult = payload
            }
            YLog.warn(
                tag = TAG,
                msg = "[搜索中] title=${options.trackName}, artist=${options.artistName}, album=${options.albumName}, path=$path"
            )

            // 1. 本地搜索阶段
            if (path != null) {
                val embeddedResult = EmbeddedLyricsProvider.searchByAudioFile(
                    audioFilePath = path,
                    trackName = options.trackName,
                    artistName = options.artistName,
                    albumName = options.albumName
                )
                if (embeddedResult != null) {
                    val payload = ProviderLyrics(EmbeddedProviderPlaceholder, embeddedResult)
                    if (!settings.wordFirst || containsWordLevelLyrics(embeddedResult)) {
                        YLog.warn(tag = TAG, msg = "命中内嵌逐字歌词，直接返回")
                        guardedCallback.onDownloadFinished(listOf(payload))
                        return@launch
                    }
                    YLog.warn(
                        tag = TAG,
                        msg = "内嵌歌词无逐字，记录为回退候选，共 ${embeddedResult.rich.size} 行"
                    )
                    if (fallbackLocalResult == null) fallbackLocalResult = payload
                }

                val externalResult = externalProvider.searchByAudioFile(
                    audioFilePath = path,
                    trackName = options.trackName,
                    artistName = options.artistName,
                    albumName = options.albumName
                )
                if (externalResult != null) {
                    val payload = ProviderLyrics(externalProvider, externalResult)
                    if (!settings.wordFirst || containsWordLevelLyrics(externalResult)) {
                        YLog.warn(tag = TAG, msg = "命中同目录逐字歌词，直接返回")
                        guardedCallback.onDownloadFinished(listOf(payload))
                        return@launch
                    }
                    YLog.warn(
                        tag = TAG,
                        msg = "同目录歌词无逐字，记录为回退候选，共 ${externalResult.rich.size} 行"
                    )
                    if (fallbackLocalResult == null) fallbackLocalResult = payload
                }
            }

            if (options.enableLocalSearch) {
            val metadataResult = externalProvider.searchByMetadata(
                trackName = options.trackName,
                artistName = options.artistName,
                albumName = options.albumName
            )
            if (metadataResult != null) {
                val payload = ProviderLyrics(externalProvider, metadataResult)
                if (!settings.wordFirst || containsWordLevelLyrics(metadataResult)) {
                    YLog.warn(tag = TAG, msg = "命中路径库逐字歌词，直接返回")
                    guardedCallback.onDownloadFinished(listOf(payload))
                    return@launch
                }
                YLog.warn(
                    tag = TAG,
                    msg = "路径库歌词无逐字，记录为回退候选，共 ${metadataResult.rich.size} 行"
                )
                if (fallbackLocalResult == null) fallbackLocalResult = payload
            }

            // 2. 云端搜索阶段（不再被本地结果包裹，独立执行）
            }
            val cloudResult = if (settings.cloudEnabled) searchCloudWordLevelLyricsIncremental(options) else null

            if (cloudResult != null) {
                // 判断云端搜到的是否包含逐字时间轴
                val isCloudWordLevel = containsWordLevelLyrics(cloudResult.lyrics)
                
                if (isCloudWordLevel) {
                    YLog.warn(tag = TAG, msg = "云端命中逐字歌词，优先使用: ${cloudResult.provider.id}")
                    saveToLrcFile(path, cloudResult.lyrics)
                    guardedCallback.onDownloadFinished(listOf(cloudResult))
                    return@launch
                }
                
                // 云端只是普通歌词，此时如果本地有普通歌词备胎，优先用本地的！
                if (fallbackLocalResult != null) {
                    YLog.warn(
                        tag = TAG,
                        msg = "云端仅为普通歌词，优先使用本地备胎: ${fallbackLocalResult.provider.id}"
                    )
                    guardedCallback.onDownloadFinished(listOf(fallbackLocalResult))
                    return@launch
                }
                
                // 云端是普通歌词，且本地完全没有歌词 (例如 WebDAV)
                YLog.warn(tag = TAG, msg = "本地无歌词，命中云端普通歌词: ${cloudResult.provider.id}")
                guardedCallback.onDownloadFinished(listOf(cloudResult))
                return@launch
            }

            // 3. 云端完全没搜到，但本地有备胎
            if (fallbackLocalResult != null) {
                YLog.warn(tag = TAG, msg = "云端无可用结果，使用本地备胎: ${fallbackLocalResult.provider.id}")
                guardedCallback.onDownloadFinished(listOf(fallbackLocalResult))
                return@launch
            }

            // 4. 彻底没歌词
            YLog.warn(tag = TAG, msg = "未找到可用歌词结果")
            guardedCallback.onDownloadFinished(emptyList())
        }
    }

    fun cancel() {
        requestGeneration.incrementAndGet()
        currentTask?.cancel()
        currentTask = null
    }

    /**
     * 判断传入的元数据是否是播放器广播的无效加载状态
     */
    private fun isInvalidMetadata(options: SearchOptions): Boolean {
        val title = options.trackName?.lowercase(Locale.getDefault()) ?: ""
        val artist = options.artistName?.lowercase(Locale.getDefault()) ?: ""
        val album = options.albumName?.lowercase(Locale.getDefault()) ?: ""
        val normalizedTitle = title.trim()
        val normalizedArtist = artist.trim()
        val normalizedAlbum = album.trim()

        // 定义播放器加载、扫描等状态的常见占位关键词
        val invalidKeywords = listOf(
            "正在扫描", "正在加载", "neutron music player", 
            "正在进行的任务", "媒体渲染器","媒体服务器媒体渲染器"
        )
        

        // 只要包含其中一个关键词，就认为是假的元数据
        if (invalidKeywords.any { title.contains(it) || artist.contains(it) || album.contains(it) }) {
            return true
        }

        val invalidPlaceholders = setOf("-", "--", "-/-", "/", "//", "unknown", "<unknown>", "null", "n/a")
        val titleIsPlaceholder = normalizedTitle in invalidPlaceholders
        val artistIsPlaceholder = normalizedArtist in invalidPlaceholders
        val albumIsPlaceholder = normalizedAlbum in invalidPlaceholders || normalizedAlbum.isBlank()
        if (titleIsPlaceholder && artistIsPlaceholder && albumIsPlaceholder) {
            YLog.warn(
                tag = TAG,
                msg = "跳过无用歌曲: title=${options.trackName}, artist=${options.artistName}, album=${options.albumName}"
            )
            return true
        }

        // 顺便拦截：如果歌名和歌手都是空的，那也没必要搜了
        if (title.isBlank() && artist.isBlank()) {
            return true
        }

        return false
    }

    private fun saveToLrcFile(audioFilePath: String?, result: LyricsResult) {
        val settings = RuntimeBridge.settings
        if (!settings.saveCloud || audioFilePath.isNullOrBlank() || !audioFilePath.startsWith("/")) return
        try {
            val saved = io.github.proify.lyricon.localprovider.lyrics.CloudLyricsFileSaver.save(
                java.io.File(audioFilePath), result, settings.overwriteCloud)
            if (saved) YLog.info(tag = TAG, msg = "云端歌词已完整保存到音乐同目录")
        } catch (error: Exception) {
            // The in-memory result can still be delivered to Lyricon if disk access fails.
            YLog.error(tag = TAG, msg = "保存云端歌词失败，原文件已保留: ${error.javaClass.simpleName}")
        }
    }

        private object EmbeddedProviderPlaceholder : io.github.proify.lyricon.localprovider.model.LyricsProvider {
            override val id = "Embedded"
            override suspend fun search(
                query: String?,
                trackName: String?,
                artistName: String?,
                albumName: String?,
                limit: Int
            ): List<io.github.proify.lyricon.localprovider.model.LyricsResult> = emptyList()
        }

        private class CloudProviderPlaceholder(override val id: String) :
            io.github.proify.lyricon.localprovider.model.LyricsProvider {
            override suspend fun search(
                query: String?,
                trackName: String?,
                artistName: String?,
                albumName: String?,
                limit: Int
            ): List<io.github.proify.lyricon.localprovider.model.LyricsResult> = emptyList()
        }

        private fun containsWordLevelLyrics(result: LyricsResult): Boolean {
            return LyricTiming.hasWordTiming(result.rich)
        }

        private suspend fun searchCloudWordLevelLyrics(options: SearchOptions): ProviderLyrics? {
            val normalizedTitle = normalizeTitle(options.trackName)
            val normalizedArtist = normalizeArtist(options.artistName)
            val normalizedAlbum = normalizeAlbum(options.albumName)
            val artistTokens = normalizedArtist?.split(" ")?.filter { it.isNotBlank() }?.toSet().orEmpty()
            val searchTitle = normalizeTitle(options.trackName) ?: options.trackName
            val searchArtist = normalizeArtist(options.artistName) ?: options.artistName
            val searchAlbum = normalizeAlbum(options.albumName) ?: options.albumName

            YLog.warn(
                tag = TAG,
                msg = "[搜索中...] title=$searchTitle, artist=$searchArtist, album=$searchAlbum, durationMs=${options.durationMs}"
            )
            val cloudResults = createCloudLyrics().search {
                trackName = searchTitle
                artistName = searchArtist
                albumName = searchAlbum
                perProviderLimit = 3
                maxTotalResults = 18
                filter { item ->
                    val titleScore = titleMatchScore(normalizedTitle, item.lyrics.trackName)
                    val artistScore = if (artistTokens.isNotEmpty()) {
                        artistMatchScore(artistTokens, item.lyrics.artistName)
                    } else {
                        0
                    }
                    val lyricDuration = effectiveDurationMs(item.lyrics)
                    val durationScore = durationMatchScore(options.durationMs, lyricDuration)

                    YLog.warn(
                        tag = TAG,
                        msg = "Filter check: [${item.provider.id}] ${item.lyrics.trackName} - ${item.lyrics.artistName}" +
                                "\n标题: ${item.lyrics.trackName} vs $normalizedTitle (Score: $titleScore)" +
                                "\n歌手: ${item.lyrics.artistName} vs $artistTokens (Score: $artistScore)" +
                                "\n时长: ${lyricDuration / 1000.0}s vs ${options.durationMs / 1000.0}s (Score: $durationScore)"
                    )

                    if (titleScore == 0) return@filter false
                    if (artistTokens.isNotEmpty() && artistScore == 0) return@filter false
                    true
                }
                prefer(40) { item -> titleMatchScore(normalizedTitle, item.lyrics.trackName) >= 3 }
                prefer(30) { item -> artistMatchScore(artistTokens, item.lyrics.artistName) > 0 }
                prefer(2) { item -> item.provider.id == AppleMusicProvider.ID }
                prefer(2) { item -> albumMatchScore(normalizedAlbum, item.lyrics.albumName) > 0 }
                if (options.durationMs > 0) {
                    prefer(3) { item ->
                        val lyricDuration = effectiveDurationMs(item.lyrics)
                        durationMatchScore(options.durationMs, lyricDuration) >= 3
                    }
                    prefer(2) { item ->
                        val lyricDuration = effectiveDurationMs(item.lyrics)
                        durationMatchScore(options.durationMs, lyricDuration) >= 2
                    }
                    prefer(1) { item ->
                        val lyricDuration = effectiveDurationMs(item.lyrics)
                        durationMatchScore(options.durationMs, lyricDuration) >= 1
                    }
                }
            }
            val topResults = cloudResults.take(10).mapIndexed { index, item ->
                val duration = effectiveDurationMs(item.lyrics)
                "${index + 1}. [${item.provider.id}] ${item.lyrics.trackName} - ${item.lyrics.artistName} [${item.lyrics.albumName}] (${formatDuration(duration)})"
            }
            if (topResults.isNotEmpty()) {
                YLog.warn(tag = TAG, msg = "云搜索结果:\n${topResults.joinToString("\n")}")
            } else {
                YLog.warn(tag = TAG, msg = "云搜索结果为空")
            }
            
            // 优先获取逐字/对唱歌词，如果找不到，则拿回匹配度最高的普通云端歌词保底
            val rawCloudResult = cloudResults.firstOrNull { containsWordLevelOrDuetLyrics(it.lyrics) }
                ?: cloudResults.firstOrNull() 
                ?: return null

            val localResult = LyricsResult(
                trackName = rawCloudResult.lyrics.trackName,
                artistName = rawCloudResult.lyrics.artistName,
                albumName = rawCloudResult.lyrics.albumName,
                rich = rawCloudResult.lyrics.rich,
                instrumental = rawCloudResult.lyrics.instrumental
            )

            return ProviderLyrics(
                provider = CloudProviderPlaceholder(rawCloudResult.provider.id),
                lyrics = localResult
            )
        }

        private suspend fun searchCloudWordLevelLyricsIncremental(options: SearchOptions): ProviderLyrics? {
            val normalizedTitle = normalizeTitle(options.trackName)
            val normalizedArtist = normalizeArtist(options.artistName)
            val normalizedAlbum = normalizeAlbum(options.albumName)
            val artistTokens = normalizedArtist?.split(" ")?.filter { it.isNotBlank() }?.toSet().orEmpty()
            val searchTitle = normalizeTitle(options.trackName) ?: options.trackName
            val searchArtist = normalizeArtist(options.artistName) ?: options.artistName
            val searchAlbum = normalizeAlbum(options.albumName) ?: options.albumName

            YLog.warn(
                tag = TAG,
                msg = "[搜索中...] title=$searchTitle, artist=$searchArtist, album=$searchAlbum, durationMs=${options.durationMs}"
            )

            val providers = createCloudProviders()
            if (providers.isEmpty()) return null

            val providerScope = CoroutineScope(kotlinx.coroutines.currentCoroutineContext() + Dispatchers.IO + SupervisorJob(kotlinx.coroutines.currentCoroutineContext()[Job]))
            val channel = Channel<ProviderBatchResult>(Channel.UNLIMITED)

            providers.forEach { provider ->
                providerScope.launch {
                    val rawResults = runCatching {
                        provider.search(
                            query = null,
                            trackName = searchTitle,
                            artistName = searchArtist,
                            albumName = searchAlbum,
                            limit = 3
                        ).take(3)
                    }.getOrElse { error ->
                        if (error is kotlinx.coroutines.CancellationException) throw error
                        YLog.warn(
                            tag = TAG,
                            msg = "Provider failed: ${provider.id}, reason=${error::class.java.simpleName}: ${error.message.orEmpty()}"
                        )
                        emptyList()
                    }

                    val candidates = rawResults.mapNotNull { lyrics ->
                        evaluateCloudCandidate(
                            item = io.github.proify.cloudlyric.ProviderLyrics(provider, lyrics),
                            normalizedTitle = options.trackName,
                            normalizedAlbum = options.albumName,
                            artistTokens = options.artistName?.let { setOf(it) }.orEmpty(),
                            durationMs = options.durationMs
                        )
                    }

                    channel.trySend(ProviderBatchResult(provider.id, candidates))
                }
            }

            val collectedCandidates = mutableListOf<CloudCandidate>()
            try {
                repeat(providers.size) {
                    val batchResult = channel.receive()
                    collectedCandidates += batchResult.candidates

                    val earlyAccepted = batchResult.candidates
                        .filter { RuntimeBridge.settings.wordFirst && it.isWordLevel && it.finalScore >= 99 }
                        .maxByOrNull { it.finalScore }

                    if (earlyAccepted != null) {
                        YLog.warn(
                            tag = TAG,
                            msg = "提前获的歌词的源: [${earlyAccepted.item.provider.id}] ${earlyAccepted.item.lyrics.trackName} - ${earlyAccepted.item.lyrics.artistName} (score=${earlyAccepted.finalScore})"
                        )
                        return cloudResultToLocal(earlyAccepted.item)
                    }
                }
            } finally {
                channel.close()
                providerScope.coroutineContext[Job]?.cancel()
            }

            val sortedCandidates = collectedCandidates.sortedByDescending { it.finalScore }
            val cloudResults = sortedCandidates.map { it.item }
            val topResults = cloudResults.take(10).mapIndexed { index, item ->
                val duration = effectiveDurationMs(item.lyrics)
                "${index + 1}. [${item.provider.id}] ${item.lyrics.trackName} - ${item.lyrics.artistName} [${item.lyrics.albumName}] (${formatDuration(duration)})"
            }
            if (topResults.isNotEmpty()) {
                YLog.warn(tag = TAG, msg = "云搜索结果:\n${topResults.joinToString("\n")}")
            } else {
                YLog.warn(tag = TAG, msg = "云搜索结果为空")
            }

            val rawCloudResult = sortedCandidates.firstOrNull { RuntimeBridge.settings.wordFirst && containsWordLevelOrDuetLyrics(it.item.lyrics) }?.item
                ?: sortedCandidates.firstOrNull()?.item
                ?: return null

            return cloudResultToLocal(rawCloudResult)
        }

        private fun evaluateCloudCandidate(
            item: io.github.proify.cloudlyric.ProviderLyrics,
            normalizedTitle: String?,
            normalizedAlbum: String?,
            artistTokens: Set<String>,
            durationMs: Long
        ): CloudCandidate? {
            val score = io.github.proify.lyricon.localprovider.search.MatchScorer.evaluate(
                io.github.proify.lyricon.localprovider.search.MatchTarget(normalizedTitle, artistTokens.joinToString(" "), normalizedAlbum, durationMs),
                io.github.proify.lyricon.localprovider.search.MatchTarget(item.lyrics.trackName, item.lyrics.artistName, item.lyrics.albumName, item.lyrics.durationMs),
                RuntimeBridge.settings.matchRules
            )
            if (!score.eligible) return null
            return CloudCandidate(item, score.total,
                (score.parts[0].similarity.orZero() * 100).toInt(),
                (score.parts[1].similarity.orZero() * 100).toInt(),
                (score.parts[2].similarity.orZero() * 100).toInt(),
                (score.parts[3].similarity.orZero() * 100).toInt(), containsWordLevelLyrics(item.lyrics))
        }
        private fun Double?.orZero(): Double = this ?: 0.0

        private fun calculateCloudIntegrityScore(lyrics: io.github.proify.cloudlyric.LyricsResult): Int {
            var score = 0
            if (lyrics.trackName.isNullOrBlank().not()) score += 20
            if (lyrics.artistName.isNullOrBlank().not()) score += 20
            if (lyrics.albumName.isNullOrBlank().not()) score += 10
            if (lyrics.rich.isNotEmpty()) score += 50
            return score
        }

        private fun cloudResultToLocal(item: io.github.proify.cloudlyric.ProviderLyrics): ProviderLyrics {
            val localResult = LyricsResult(
                trackName = item.lyrics.trackName,
                artistName = item.lyrics.artistName,
                albumName = item.lyrics.albumName,
                rich = item.lyrics.rich,
                instrumental = item.lyrics.instrumental
            )
            return ProviderLyrics(
                provider = CloudProviderPlaceholder(item.provider.id),
                lyrics = localResult
            )
        }

        private fun containsWordLevelLyrics(result: io.github.proify.cloudlyric.LyricsResult): Boolean {
            return LyricTiming.hasWordTiming(result.rich)
        }

        private fun containsWordLevelOrDuetLyrics(result: io.github.proify.cloudlyric.LyricsResult): Boolean {
            return LyricTiming.hasWordTiming(result.rich) || result.rich.any { line ->
                !line.secondary.isNullOrBlank() || line.isAlignedRight
            }
        }

        private fun effectiveDurationMs(result: io.github.proify.cloudlyric.LyricsResult): Long {
            return result.durationMs.takeIf { it > 0L }
                ?: result.rich.lastOrNull()?.end
                ?: 0L
        }

        private fun normalizeTitle(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val lower = text.lowercase(Locale.getDefault())
            val keepKeywords = listOf(
                "version", "ver", "demo", "remix", "live", "edition",
                "国语", "国语版", "粤语", "粤语版", "日语", "日语版", "英语", "英语版", "粤", "国",
                "acoustic", "instrumental", "inst", "伴奏", "伴唱",
                "remaster", "remastered", "edit", "radio",
                "ost", "tv size", "short", "original", "bonus"
            )
            val removedBrackets = lower.replace(
                Regex("""[\(（\[\【]([^\)\）\]\】]*)[\)\）\]\】]""")
            ) { matchResult ->
                val fullMatch = matchResult.value
                val bracketContent = matchResult.groupValues[1]
                if (keepKeywords.any { bracketContent.contains(it) }) {
                    if (fullMatch.startsWith("(") && fullMatch.endsWith(")")) fullMatch else bracketContent
                } else {
                    " "
                }
            }
            return removedBrackets
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { null }
        }

        private fun normalizeArtist(text: String?): String? {
            val normalized = normalizeCommon(text) ?: return null
            val connectionWordsRegex = Regex("\\b(feat\\.|feat|ft\\.|ft|with|vs\\.|vs)\\b")
            return normalized
                .replace(connectionWordsRegex, " ")
                .replace("/", " ")
                .replace(",", " ")
                .replace("、", " ")
                .replace("&", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { null }
        }

        private fun normalizeAlbum(text: String?): String? {
            val normalized = normalizeCommon(text) ?: return null
            return normalized
                .replace(Regex("\\s+-\\s+(single|ep|album)$"), "")
                .replace(Regex("\\b(ep)\\b"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { null }
        }

        private fun normalizeCommon(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val lower = text.lowercase(Locale.getDefault())
            val removedBrackets = lower.replace(Regex("[\\(（\\[【].*?[\\)）\\]】]"), " ")
            return removedBrackets
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { null }
        }

        private fun titleMatchScore(targetTitle: String?, resultTitle: String?): Int {
            val target = normalizeTitle(targetTitle) ?: return 0
            val result = normalizeTitle(resultTitle) ?: return 0
            if (target == result) return 3
            if (result.contains(target) || target.contains(result)) return 2
            val targetTokens = target.split(" ").filter { it.isNotBlank() }.toSet()
            val resultTokens = result.split(" ").filter { it.isNotBlank() }.toSet()
            return if (targetTokens.intersect(resultTokens).isNotEmpty()) 1 else 0
        }

        private fun artistMatchScore(targetTokens: Set<String>, resultArtist: String?): Int {
            if (targetTokens.isEmpty()) return 0
            val resultTokens = normalizeArtist(resultArtist)
                ?.split(" ")
                ?.filter { it.isNotBlank() }
                ?.toSet()
                .orEmpty()
            return if (targetTokens.intersect(resultTokens).isNotEmpty()) 2 else 0
        }

        private fun albumMatchScore(targetAlbum: String?, resultAlbum: String?): Int {
            val target = normalizeAlbum(targetAlbum) ?: return 0
            val result = normalizeAlbum(resultAlbum) ?: return 0
            return if (target == result || result.contains(target) || target.contains(result)) 1 else 0
        }

        private fun durationMatchScore(targetDuration: Long, resultDuration: Long): Int {
            if (targetDuration <= 0 || resultDuration <= 0) return 0
            val diff = abs(targetDuration - resultDuration)
            return when {
                diff < 1000 -> 3
                diff < 2000 -> 2
                diff < 5000 -> 1
                else -> 0
            }
        }

        private fun formatDuration(durationMs: Long): String {
            val totalSeconds = (durationMs / 1000).coerceAtLeast(0)
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%02d:%02d".format(minutes, seconds)
        }
    }

data class SearchOptions(
    var trackName: String? = null,
    var artistName: String? = null,
    var albumName: String? = null,
    var durationMs: Long = 0L,
    var enableLocalSearch: Boolean = false,
    var audioFilePath: String? = null
)
