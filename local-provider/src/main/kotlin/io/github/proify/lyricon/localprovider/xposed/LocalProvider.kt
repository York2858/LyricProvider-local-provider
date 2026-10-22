/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.localprovider.xposed

import io.github.proify.lyricon.localprovider.lyrics.LyricTiming
import android.content.Context
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.kyant.taglib.TagLib
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.localprovider.model.ProviderLyrics
import io.github.proify.lyricon.localprovider.model.LyricsResult
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import io.github.proify.lyricon.localprovider.data.RuntimeBridge

object LocalProvider : YukiBaseHooker(), DownloadCallback {
    private const val TAG = "LocalProvider"
    private const val POWERAMP_PACKAGE = "com.maxmpz.audioplayer"
    private val LOCAL_PATH_RETRY_DELAYS_MS = longArrayOf(300L, 900L, 1800L)

    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS|LYRICS\\d*|USLT)\\b") }
    private val retryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var provider: LyriconProvider? = null
    private var lastMediaSignature: String? = null
    private var lastPathHint: Pair<String?, String?>? = null
    private var pendingLocalRetry: Job? = null
    private var lastMetadata: MediaMetadata? = null

    override fun onHook() {
        if (processName == POWERAMP_PACKAGE) return
        // YLog.debug(tag = TAG, msg = "========== LocalProvider 已注入，进程名=$processName ==========")

        onAppLifecycle {
            onCreate {
                runCatching { initProvider() }
                    .onFailure { error ->
                        YLog.error(tag = TAG, msg = "Failed to initialize LocalProvider", e = error)
                    }
                runCatching { hookMediaSession() }
                    .onFailure { error ->
                        YLog.error(tag = TAG, msg = "Failed to hook MediaSession", e = error)
                    }
            }
            onTerminate { release() }
        }
    }

    private fun initProvider() {
        val context = appContext ?: return
        RuntimeBridge.initialize(context) {
            if (context.packageName !in RuntimeBridge.settings.players) {
                DownloadManager.cancel()
                provider?.unregister()
            } else {
                provider?.register()
                lastMediaSignature = null
                lastMetadata?.let(::handleMetadata)
            }
        }

        // ----- 新增：从本地文件读取自定义黑名单并应用到解析器 -----
        val blacklistConfig = BlacklistFileLoader.loadConfig()
        AppleMusicConfigLoader.loadConfig()
        EnhanceLrcParser.customStaffRoleExact.clear()
        EnhanceLrcParser.customStaffRoleExact.addAll(blacklistConfig.customExact + RuntimeBridge.settings.exactRules)
        
        EnhanceLrcParser.customStaffRoleKeywords.clear()
        EnhanceLrcParser.customStaffRoleKeywords.addAll(blacklistConfig.customKeywords + RuntimeBridge.settings.keywordRules)
        // -----------------------------------------------------------

        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            processName = processName
        ).apply {
            player.setDisplayTranslation(true)
            player.setDisplayRoma(true)
            if (context.packageName in RuntimeBridge.settings.players) register()
        }
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            firstMethod {
                name = "setPlaybackState"
                parameters(PlaybackState::class.java)
            }.hook {
                after {
                    runCatching {
                        val state = args[0] as? PlaybackState ?: return@runCatching
                        if (appContext?.packageName !in RuntimeBridge.settings.players) return@runCatching
                        RuntimeBridge.reportPlayback(state)
                        provider?.player?.setPlaybackState(state)
                        BridgeV4Sender.sendPlaybackState(appContext ?: return@runCatching, processName, state)
                    }.onFailure { error ->
                        YLog.error(tag = TAG, msg = "Failed to handle playback state callback", e = error)
                    }
                }
            }

            firstMethod {
                name = "setMetadata"
                parameters("android.media.MediaMetadata")
            }.hook {
                after {
                    runCatching {
                        val metadata = args[0] as? MediaMetadata ?: return@runCatching
                        handleMetadata(metadata)
                        (instance as? android.media.session.MediaSession)?.controller?.playbackState?.let(RuntimeBridge::reportPlayback)
                    }.onFailure { error ->
                        YLog.error(tag = TAG, msg = "Failed to handle metadata callback", e = error)
                    }
                }
            }
        }
    }

    private fun handleMetadata(metadata: MediaMetadata) {
        lastMetadata = metadata
        if (appContext?.packageName !in RuntimeBridge.settings.players) return
        if (processName == POWERAMP_PACKAGE) {
            // YLog.debug(tag = TAG, msg = "Skip PowerAmp processing, handled by PowerAmp hook")
            return
        }

        val id = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val signature = calculateSignature(id, title, artist, album)
        val pathHint = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI) to metadata.getString("filePath")
        RuntimeBridge.setTrackMetadata(title, artist, album, duration)
        RuntimeBridge.captureArtwork(metadata)
        // Artwork/duration can arrive later, but identical metadata need not query MediaStore again.
        if (signature == lastMediaSignature && pathHint == lastPathHint) return
        lastPathHint = pathHint
        var resolvedPath = resolveAudioPath(metadata)

        if (resolvedPath == null && !title.isNullOrBlank()) {
            resolvedPath = getPathFromMediaStoreExact(title, artist)
        }

        YLog.debug(tag = TAG, msg = "LocalProvider resolved audio path: $resolvedPath")

        if (signature == lastMediaSignature && resolvedPath == null) resolvedPath = DownloadManager.currentAudioPath
        if (signature == lastMediaSignature && resolvedPath == DownloadManager.currentAudioPath) return
        DownloadManager.setCurrentAudioPath(resolvedPath)
        pendingLocalRetry?.cancel()
        lastMediaSignature = signature
        val blacklist = BlacklistFileLoader.loadConfig()
        EnhanceLrcParser.customStaffRoleExact.apply { clear(); addAll(blacklist.customExact + RuntimeBridge.settings.exactRules) }
        EnhanceLrcParser.customStaffRoleKeywords.apply { clear(); addAll(blacklist.customKeywords + RuntimeBridge.settings.keywordRules) }
        RuntimeBridge.setTrackMetadata(title, artist, album, duration)
        RuntimeBridge.report(Song(name = title, artist = artist, duration = duration), "", resolvedPath, "正在匹配歌词")

        provider?.player?.setSong(Song(name = title, artist = artist))
        BridgeV4Sender.sendTrack(
            appContext ?: return, processName,
            Song(id = id, name = title, artist = artist, duration = duration)
        )

        DownloadManager.cancel()
        DownloadManager.search(this@LocalProvider) {
            trackName = title
            artistName = artist
            albumName = album
            durationMs = duration
            enableLocalSearch = true
            audioFilePath = resolvedPath
        }

        if (resolvedPath == null) {
            YLog.warn(
                tag = TAG,
                msg = "Audio path is null, scheduling local path retry: title=$title, artist=$artist, album=$album, duration=$duration"
            )
            scheduleLocalPathRetry(
                signature = signature,
                title = title,
                artist = artist,
                album = album,
                duration = duration
            )
        }
    }

    private fun resolveAudioPath(metadata: MediaMetadata): String? {
        val mediaUriString = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
        val filePath = metadata.getString("filePath")

        return when {
            !mediaUriString.isNullOrBlank() -> {
                val uri = Uri.parse(mediaUriString)
                when (uri.scheme) {
                    "file" -> uri.path
                    "content" -> getPathFromContentUri(uri)
                    else -> null
                }
            }
            !filePath.isNullOrBlank() -> filePath
            else -> null
        }
    }

    private fun getPathFromContentUri(uri: Uri): String? {
        return runCatching {
            val projection = arrayOf(MediaStore.Audio.Media.DATA)
            appContext?.contentResolver?.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val columnIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                if (columnIndex < 0) {
                    YLog.warn(tag = TAG, msg = "MediaStore DATA column unavailable for content uri: $uri")
                    return@use null
                }
                cursor.getString(columnIndex)
            }
        }.onFailure { error ->
            YLog.warn(tag = TAG, msg = "Failed to resolve file path from content uri: $uri, reason=${error.message}")
        }.getOrNull()
    }

    private fun getPathFromMediaStoreExact(title: String?, artist: String?): String? {
        if (title.isNullOrBlank()) return null
        return runCatching {
            val projection = arrayOf(MediaStore.Audio.Media.DATA)
            val selection = java.lang.StringBuilder()
            val selectionArgs = mutableListOf<String>()
            selection.append("${MediaStore.Audio.Media.TITLE} = ?")
            selectionArgs.add(title)
            if (!artist.isNullOrBlank()) {
                selection.append(" AND ${MediaStore.Audio.Media.ARTIST} = ?")
                selectionArgs.add(artist)
            }
            appContext?.contentResolver?.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection.toString(),
                selectionArgs.toTypedArray(),
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val columnIndex = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)
                if (columnIndex < 0) {
                    YLog.warn(tag = TAG, msg = "MediaStore DATA column unavailable for title=$title, artist=$artist")
                    return@use null
                }
                cursor.getString(columnIndex)
            }
        }.onFailure { error ->
            YLog.warn(
                tag = TAG,
                msg = "Failed to resolve file path from MediaStore for title=$title, artist=$artist, reason=${error.message}"
            )
        }.getOrNull()
    }

    private fun tryLoadEmbeddedLyrics(filePath: String, title: String?, artist: String?): List<io.github.proify.lyricon.lyric.model.RichLyricLine>? {
        val audioFile = File(filePath)
        if (!audioFile.exists()) {
            YLog.debug(tag = TAG, msg = "音频文件不存在: $filePath")
            return null
        }

        val uri = Uri.fromFile(audioFile)
        return try {
            appContext?.contentResolver?.openFileDescriptor(uri, "r")?.use { pfd ->
                TagLib.getMetadata(pfd.dup().detachFd())?.let { metadata ->
                    metadata.propertyMap.entries.firstOrNull { (key, _) ->
                        lyricTagRegex.matches(key)
                    }?.value?.firstOrNull()?.let { rawLyric ->
                        YLog.info(tag = TAG, msg = "找到内嵌歌词，长度=${rawLyric.length}")
                        val doc = EnhanceLrcParser.parse(rawLyric)
                        doc.lines.filter { !it.text.isNullOrBlank() }
                    }
                }
            }
        } catch (e: Exception) {
            YLog.error(tag = TAG, msg = "读取内嵌歌词失败", e = e)
            null
        }
    }

    private fun calculateSignature(vararg data: String?): String {
        return data.joinToString("") { it?.hashCode()?.toString() ?: "0" }.hashCode().toString()
    }

    private fun scheduleLocalPathRetry(
        signature: String,
        title: String?,
        artist: String?,
        album: String?,
        duration: Long
    ) {
        if (title.isNullOrBlank()) {
            YLog.warn(tag = TAG, msg = "Skip local path retry because title is blank")
            return
        }

        pendingLocalRetry = retryScope.launch {
            LOCAL_PATH_RETRY_DELAYS_MS.forEachIndexed { index, delayMs ->
                delay(delayMs)
                if (lastMediaSignature != signature) return@launch

                val retryPath = getPathFromMediaStoreExact(title, artist)
                YLog.warn(
                    tag = TAG,
                    msg = "Retry local path resolution ${index + 1}/${LOCAL_PATH_RETRY_DELAYS_MS.size} after ${delayMs}ms: $retryPath"
                )

                if (retryPath.isNullOrBlank()) return@forEachIndexed

                DownloadManager.setCurrentAudioPath(retryPath)
                if (lastMediaSignature != signature) return@launch

                YLog.warn(tag = TAG, msg = "Resolved audio path on retry, rerunning local/embedded search: $retryPath")
                DownloadManager.cancel()
                DownloadManager.search(this@LocalProvider) {
                    trackName = title
                    artistName = artist
                    albumName = album
                    durationMs = duration
                    enableLocalSearch = true
                    audioFilePath = retryPath
                }
                return@launch
            }

            if (lastMediaSignature == signature) {
                YLog.warn(tag = TAG, msg = "Local path retry exhausted without resolving path")
            }
        }
    }

    override fun onDownloadFinished(response: List<ProviderLyrics>) {
        val firstResult = response.firstOrNull()
        val source = firstResult?.provider?.id ?: "Unknown"
        val newSong = firstResult?.lyrics?.toSong()?.let(RuntimeBridge::bindPlaybackIdentity)
        if (newSong != null && newSong.lyrics?.isNotEmpty() == true) {
            provider?.player?.setSong(newSong)
            BridgeV4Sender.sendLyrics(
                appContext ?: return, processName,
                newSong
            )
            RuntimeBridge.report(newSong, source, DownloadManager.currentAudioPath, "已发送歌词到词幕")
            YLog.debug(tag = TAG, msg = "成功加载歌词，source=$source，共 ${newSong.lyrics?.size} 行")
        } else {
            RuntimeBridge.report(lastMetadata?.let { Song(name = it.getString(MediaMetadata.METADATA_KEY_TITLE), artist = it.getString(MediaMetadata.METADATA_KEY_ARTIST)) }, "", DownloadManager.currentAudioPath, "未找到可用歌词")
            YLog.debug(tag = TAG, msg = "未找到歌词，保留现有歌词")
        }
    }

    override fun onDownloadFailed(e: Exception) {
        RuntimeBridge.report(lastMetadata?.let { Song(name = it.getString(MediaMetadata.METADATA_KEY_TITLE), artist = it.getString(MediaMetadata.METADATA_KEY_ARTIST)) }, "", DownloadManager.currentAudioPath, "歌词匹配失败：${e.javaClass.simpleName}", true)
        YLog.error(tag = TAG, msg = "歌词搜索失败: ${e.message}")
    }

    private fun LyricsResult.toSong() = Song().apply {
        name = trackName
        artist = artistName
        val normalizedLyrics = normalizeForTripleLine(rich)
        lyrics = normalizedLyrics
        duration = normalizedLyrics.lastOrNull()?.end ?: 0L
    }

    private fun containsWordLevelLyrics(lines: List<RichLyricLine>): Boolean {
        return LyricTiming.hasWordTiming(lines)
    }

    internal fun normalizeForTripleLine(lines: List<RichLyricLine>): List<RichLyricLine> =
        io.github.proify.lyricon.localprovider.lyrics.LyricNormalizer.normalizeForTripleLine(lines)

    private fun release() {
        pendingLocalRetry?.cancel()
        pendingLocalRetry = null
        YLog.info(tag = TAG, msg = "LocalProvider released")
    }
}
