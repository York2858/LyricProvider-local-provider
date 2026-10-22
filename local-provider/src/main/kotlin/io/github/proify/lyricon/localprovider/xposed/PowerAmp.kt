/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.localprovider.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.session.PlaybackState
import android.net.Uri
import androidx.core.content.ContextCompat
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import com.kyant.taglib.TagLib
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.localprovider.model.ProviderLyrics
import io.github.proify.lyricon.localprovider.model.LyricsResult
import io.github.proify.lyricon.localprovider.xposed.util.SafUriResolver
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.localprovider.data.RuntimeBridge

object PowerAmp : YukiBaseHooker(), DownloadCallback {
    private const val TAG = "PowerAmp"
    private const val ACTION_TRACK_CHANGED = "com.maxmpz.audioplayer.TRACK_CHANGED"

    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS|LYRICS\\d*|USLT)\\b") }

    private var provider: LyriconProvider? = null
    private var trackReceiver: BroadcastReceiver? = null
    private var currentSongId: String? = null
    private var lastTrackIntent: Intent? = null

    override fun onHook() {
        onAppLifecycle {
            onCreate {
                runCatching { initLyriconProvider(this) }
                    .onFailure { error ->
                        YLog.error(tag = TAG, msg = "Failed to initialize PowerAmp provider", e = error)
                    }
                runCatching { setupBroadcastReceiver(this) }
                    .onFailure { error ->
                        YLog.error(tag = TAG, msg = "Failed to register PowerAmp broadcast receiver", e = error)
                    }
                runCatching { hookMediaSession() }
                    .onFailure { error ->
                        YLog.error(tag = TAG, msg = "Failed to hook PowerAmp MediaSession", e = error)
                    }
            }
            onTerminate { release() }
        }
    }

    private fun initLyriconProvider(context: Context) {
        RuntimeBridge.initialize(context) {
            if (context.packageName !in RuntimeBridge.settings.players) {
                DownloadManager.cancel(); provider?.unregister()
            } else {
                provider?.register(); currentSongId = null
                lastTrackIntent?.let(::handleTrackChange)
            }
        }
        val blacklistConfig = BlacklistFileLoader.loadConfig()
        EnhanceLrcParser.customStaffRoleExact.clear()
        EnhanceLrcParser.customStaffRoleExact.addAll(blacklistConfig.customExact + RuntimeBridge.settings.exactRules)
        EnhanceLrcParser.customStaffRoleKeywords.clear()
        EnhanceLrcParser.customStaffRoleKeywords.addAll(blacklistConfig.customKeywords + RuntimeBridge.settings.keywordRules)

        provider = LyriconFactory.createProvider(
            context = context,
            providerPackageName = Constants.PROVIDER_PACKAGE_NAME,
            playerPackageName = context.packageName,
            logo = null
        ).apply {
            player.setDisplayRoma(true)
            player.setDisplayTranslation(true)
            if (context.packageName in RuntimeBridge.settings.players) register()
        }
        YLog.info(tag = TAG, msg = "Lyricon Provider registered for PowerAmp")
    }

    private fun setupBroadcastReceiver(context: Context) {
        val filter = IntentFilter(ACTION_TRACK_CHANGED)
        trackReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_TRACK_CHANGED) {
                    handleTrackChange(intent)
                }
            }
        }.also {
            ContextCompat.registerReceiver(context, it, filter, ContextCompat.RECEIVER_EXPORTED)
        }
        YLog.info(tag = TAG, msg = "Broadcast receiver registered for PowerAmp")
    }

    private fun hookMediaSession() {
        "android.media.session.MediaSession".toClass().resolve().apply {
            firstMethod {
                name = "setMetadata"
                parameters(android.media.MediaMetadata::class.java)
            }.hook {
                after {
                    if (appContext?.packageName in RuntimeBridge.settings.players) {
                        (args[0] as? android.media.MediaMetadata)?.let(RuntimeBridge::captureArtwork)
                    }
                }
            }

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
                    }.onFailure { error ->
                        YLog.error(tag = TAG, msg = "Failed to handle PowerAmp playback state callback", e = error)
                    }
                }
            }
        }
        YLog.info(tag = TAG, msg = "MediaSession hooked for PowerAmp")
    }

    private fun handleTrackChange(intent: Intent) {
        lastTrackIntent = intent
        if (appContext?.packageName !in RuntimeBridge.settings.players) return
        val bundle = intent.extras ?: return

        val id = bundle.getLong("id", -1).toString()
        val title = bundle.getString("title") ?: return
        val artist = bundle.getString("artist")
        val album = bundle.getString("album")
        val duration = bundle.getLong("durMs")
        val rawPath = bundle.getString("path") ?: return

        RuntimeBridge.setTrackMetadata(title, artist, album, duration)
        val identity = listOf(id, title, artist, album, rawPath).joinToString("\u0000")
        if (identity == currentSongId) return
        currentSongId = identity

        YLog.debug(tag = TAG, msg = "Track changed: $title - $artist")

        provider?.player?.setSong(Song(name = title, artist = artist))

        // --- 核心修复：将 PowerAmp 的相对路径翻译为标准的绝对路径 ---
        val absolutePath = convertToAbsolutePath(rawPath)
        RuntimeBridge.setTrackMetadata(title, artist, album, duration)
        RuntimeBridge.report(Song(name = title, artist = artist, duration = duration), "", absolutePath, "正在匹配歌词")

        DownloadManager.cancel()
        DownloadManager.setCurrentAudioPath(absolutePath)
        DownloadManager.search(this@PowerAmp) {
            trackName = title
            artistName = artist
            albumName = album
            durationMs = duration
            enableLocalSearch = true
            audioFilePath = absolutePath
        }
    }

    /**
     * 将 PowerAmp 的虚拟卷标路径翻译为 Android 真实的绝对路径
     * 例如："primary/Music/test.m4a" -> "/storage/emulated/0/Music/test.m4a"
     * 例如："1234-ABCD/Music/test.m4a" -> "/storage/1234-ABCD/Music/test.m4a"
     */
    private fun convertToAbsolutePath(powerAmpPath: String): String {
        if (powerAmpPath.startsWith("/")) return powerAmpPath // 如果已经是绝对路径，直接返回

        val separatorIndex = powerAmpPath.indexOf('/')
        if (separatorIndex == -1) return powerAmpPath

        val volumeId = powerAmpPath.substring(0, separatorIndex)
        val relativePath = powerAmpPath.substring(separatorIndex + 1)

        return if (volumeId.equals("primary", ignoreCase = true)) {
            "/storage/emulated/0/$relativePath"
        } else {
            "/storage/$volumeId/$relativePath"
        }
    }

    private fun resolveAudioUri(path: String): Uri? {
        val formattedPath = formatSafPath(path) ?: return null
        val context = appContext ?: return null
        return SafUriResolver.resolveToUri(context, formattedPath)
    }

    private fun formatSafPath(path: String): String? {
        val input = path.trimStart()
        if (input.isEmpty() || input.startsWith("/")) return null

        val separatorIndex = input.indexOf('/')
        if (separatorIndex == -1) return null

        val volumeId = input.take(separatorIndex)
        val relativePath = input.substring(separatorIndex + 1)

        return if (volumeId.isNotEmpty()) "$volumeId:$relativePath" else null
    }

    private fun fetchEmbeddedLyrics(uri: Uri): List<io.github.proify.lyricon.lyric.model.RichLyricLine>? {
        return try {
            appContext?.contentResolver?.openFileDescriptor(uri, "r")?.use { pfd ->
                TagLib.getMetadata(pfd.dup().detachFd())?.let { metadata ->
                    metadata.propertyMap.entries.firstOrNull { (key, _) ->
                        lyricTagRegex.matches(key)
                    }?.value?.firstOrNull()?.let { rawLyric ->
                        val doc = EnhanceLrcParser.parse(rawLyric)
                        LocalProvider.normalizeForTripleLine(doc.lines)
                            .filter { !it.text.isNullOrBlank() }
                    }
                }
            }
        } catch (e: Exception) {
            YLog.error(tag = TAG, msg = "Failed to fetch lyrics", e = e)
            null
        }
    }

    override fun onDownloadFinished(response: List<ProviderLyrics>) {
        val firstResult = response.firstOrNull()
        val source = firstResult?.provider?.id ?: "Unknown"
        val newSong = firstResult?.lyrics?.toSong()?.let(RuntimeBridge::bindPlaybackIdentity)
        
        if (newSong != null && newSong.lyrics?.isNotEmpty() == true) {
            provider?.player?.setSong(newSong)
            RuntimeBridge.report(newSong, source, DownloadManager.currentAudioPath, "已发送歌词到词幕")
            YLog.info(tag = TAG, msg = "成功为 PowerAmp 加载歌词，数据源=$source，共 ${newSong.lyrics?.size} 行")
        } else {
            RuntimeBridge.report(Song(name = lastTrackIntent?.getStringExtra("title"), artist = lastTrackIntent?.getStringExtra("artist")), "", DownloadManager.currentAudioPath, "未找到可用歌词")
            YLog.debug(tag = TAG, msg = "PowerAmp 云端和本地均未找到歌词")
        }
    }

    override fun onDownloadFailed(e: Exception) {
        RuntimeBridge.report(Song(name = lastTrackIntent?.getStringExtra("title"), artist = lastTrackIntent?.getStringExtra("artist")), "", DownloadManager.currentAudioPath, "歌词匹配失败：${e.javaClass.simpleName}", true)
        YLog.error(tag = TAG, msg = "PowerAmp 歌词搜索失败: ${e.message}")
    }

    private fun LyricsResult.toSong() = Song().apply {
        name = trackName
        artist = artistName
        val normalizedLyrics = LocalProvider.normalizeForTripleLine(rich)
        lyrics = normalizedLyrics
        duration = normalizedLyrics.lastOrNull()?.end ?: 0L
    }

    private fun release() {
        trackReceiver?.let { appContext?.unregisterReceiver(it) }
        trackReceiver = null
        provider?.unregister()
        provider = null
        YLog.info(tag = TAG, msg = "PowerAmp provider released")
    }
}
