/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package io.github.proify.lyricon.localprovider.xposed

import com.highcapable.yukihookapi.hook.log.YLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class RoleBlacklistConfig(
    val customExact: List<String> = emptyList(),
    val customKeywords: List<String> = emptyList()
)

object BlacklistFileLoader {
    private const val TAG = "LocalProvider"
    
    // 黑名单配置文件的绝对路径
    private const val CONFIG_FILE_PATH = "/storage/emulated/0/Lyrics/lyricon_blacklist.json"
    
    // 配置 JSON 解析器，自动格式化并忽略未知字段
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun loadConfig(): RoleBlacklistConfig {
        val file = File(CONFIG_FILE_PATH)
        
        // 如果文件不存在，自动生成一个模板文件
        if (!file.exists()) {
            try {
                file.parentFile?.mkdirs()
                val defaultConfig = RoleBlacklistConfig(
                    customExact = listOf("示例精确词1", "示例精确词2"),
                    customKeywords = listOf("示例包含词1", "示例包含词2")
                )
                file.writeText(json.encodeToString(defaultConfig))
                YLog.info(tag = TAG, msg = "已创建默认黑名单配置文件: $CONFIG_FILE_PATH")
            } catch (e: Exception) {
                YLog.error(tag = TAG, msg = "创建黑名单配置文件失败，可能无存储权限: ${e.message}")
            }
            return RoleBlacklistConfig()
        }

        // 读取并解析用户填写的文件
        return try {
            json.decodeFromString<RoleBlacklistConfig>(file.readText())
        } catch (e: Exception) {
            YLog.error(tag = TAG, msg = "解析黑名单配置文件失败，请检查 JSON 格式: ${e.message}")
            RoleBlacklistConfig() // 格式错误时返回空配置，防止崩溃
        }
    }
}