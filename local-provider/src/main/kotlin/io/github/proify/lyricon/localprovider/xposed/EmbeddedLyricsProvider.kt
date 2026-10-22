/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.localprovider.xposed

import android.net.Uri
import com.highcapable.yukihookapi.hook.log.YLog
import com.kyant.taglib.TagLib
import io.github.proify.lrckit.EnhanceLrcParser
import io.github.proify.lyricon.localprovider.model.LyricsResult
import io.github.proify.lyricon.lyric.model.RichLyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object EmbeddedLyricsProvider {
    private const val TAG = "LocalProvider"

    private val lyricTagRegex by lazy { Regex("(?i)\\b(LYRICS|LYRICS\\d*|USLT)\\b") }

    suspend fun searchByAudioFile(
        audioFilePath: String,
        trackName: String?,
        artistName: String?,
        albumName: String?
    ): LyricsResult? = withContext(Dispatchers.IO) {
        YLog.debug(tag = TAG, msg = "EmbeddedLyricsProvider searchByAudioFile: path=$audioFilePath, track=$trackName, artist=$artistName")

        val audioFile = File(audioFilePath)
        if (!audioFile.exists()) {
            YLog.debug(tag = TAG, msg = "EmbeddedLyricsProvider audio file not found: $audioFilePath")
            return@withContext null
        }

        val uri = Uri.fromFile(audioFile)
        val lyrics = extractLyricFromTag(uri) ?: run {
            YLog.debug(tag = TAG, msg = "EmbeddedLyricsProvider no embedded lyrics found")
            return@withContext null
        }

        YLog.info(tag = TAG, msg = "EmbeddedLyricsProvider found embedded lyrics, length=${lyrics.length}")
        val richLines = parseLyrics(lyrics)
        if (richLines.isEmpty()) {
            YLog.warn(tag = TAG, msg = "EmbeddedLyricsProvider parsed embedded lyrics but lines are empty")
            return@withContext null
        }

        LyricsResult(
            trackName = trackName,
            artistName = artistName,
            albumName = albumName,
            rich = richLines
        ).also {
            YLog.info(tag = TAG, msg = "EmbeddedLyricsProvider returning embedded lyrics result")
        }
    }

    private fun extractLyricFromTag(uri: Uri): String? {
        val context = io.github.proify.lyricon.localprovider.data.RuntimeBridge.appContext ?: return null
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                TagLib.getMetadata(pfd.detachFd())?.let { metadata ->
                    metadata.propertyMap.entries.firstOrNull { (key, _) ->
                        lyricTagRegex.matches(key)
                    }?.value?.firstOrNull()
                }
            }
        } catch (e: Exception) {
            YLog.error(tag = TAG, msg = "EmbeddedLyricsProvider failed to read lyric tag: ${e.message}", e = e)
            null
        }
    }

    private fun parseLyrics(raw: String): List<RichLyricLine> {
        val doc = EnhanceLrcParser.parse(raw)
        return LocalProvider.normalizeForTripleLine(doc.lines)
    }
}
