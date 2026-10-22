/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.localprovider.xposed

import android.os.Environment
import com.highcapable.yukihookapi.hook.log.YLog
import io.github.proify.cloudlyric.provider.apple.AppleMusicConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

private const val DEFAULT_APPLE_MUSIC_LANGUAGE = "zh-Hans-CN"

@Serializable
private data class AppleMusicConfigFile(
    val enabled: Boolean = true,
    val storefront: String = "us",
    val mediaUserToken: String = "",
    val language: String = DEFAULT_APPLE_MUSIC_LANGUAGE,
    val developerToken: String = "",
    val lyricsTypePreference: String = ""
)

object AppleMusicConfigLoader {
    private const val TAG = "LocalProvider"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val configFilePath: String by lazy {
        val storageRoot = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
        "$storageRoot/Lyrics/lyricon_apple_music.json"
    }

    fun loadConfig(): AppleMusicConfig? {
        val settings = io.github.proify.lyricon.localprovider.data.RuntimeBridge.settings
        if (!settings.appleEnabled) return null
        if (settings.appleConfigured) {
            if (settings.appleUserToken.isBlank()) return null
            return AppleMusicConfig(
                storefront = settings.appleStorefront,
                mediaUserToken = settings.appleUserToken,
                language = normalizeAppleMusicLanguageTag(settings.appleLanguage),
                developerToken = settings.appleDeveloperToken,
                lyricsTypePreference = ""
            )
        }
        val file = File(configFilePath)
        if (!file.exists()) {
            createTemplate(file)
            return null
        }

        val data = runCatching {
            json.decodeFromString<AppleMusicConfigFile>(file.readText())
        }.getOrElse { error ->
            YLog.error(tag = TAG, msg = "Apple Music 配置解析失败，请检查 JSON: ${error.message}")
            return null
        }

        if (!data.enabled) return null

        val mediaUserToken = data.mediaUserToken.trim()
        if (mediaUserToken.isBlank()) return null

        return AppleMusicConfig(
            storefront = data.storefront.trim().ifBlank { "us" },
            mediaUserToken = mediaUserToken,
            language = normalizeAppleMusicLanguageTag(data.language),
            developerToken = data.developerToken.trim(),
            lyricsTypePreference = data.lyricsTypePreference.trim()
        )
    }

    private fun createTemplate(file: File) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                json.encodeToString(
                    AppleMusicConfigFile(
                        enabled = true,
                        storefront = "us",
                        mediaUserToken = "PASTE_YOUR_MEDIA_USER_TOKEN_HERE",
                        language = DEFAULT_APPLE_MUSIC_LANGUAGE,
                        developerToken = "",
                        lyricsTypePreference = ""
                    )
                )
            )
            YLog.info(tag = TAG, msg = "已创建 Apple Music 配置文件: $configFilePath")
        }.onFailure { error ->
            YLog.error(tag = TAG, msg = "创建 Apple Music 配置文件失败: ${error.message}")
        }
    }

    private fun normalizeAppleMusicLanguageTag(rawLanguage: String): String {
        val trimmed = rawLanguage.trim()
        if (trimmed.isBlank()) return DEFAULT_APPLE_MUSIC_LANGUAGE

        val locale = Locale.forLanguageTag(trimmed)
        val language = locale.language.lowercase(Locale.US)
        if (language != "zh") return trimmed

        return when (locale.country.uppercase(Locale.US)) {
            "TW" -> "zh-Hant-TW"
            "HK" -> "zh-Hant-HK"
            "MO" -> "zh-Hant-MO"
            else -> DEFAULT_APPLE_MUSIC_LANGUAGE
        }
    }
}
