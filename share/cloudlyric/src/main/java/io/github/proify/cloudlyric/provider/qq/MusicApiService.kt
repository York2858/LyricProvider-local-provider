/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.qq

import io.github.proify.cloudlyric.provider.internal.ProviderSupport
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.random.Random

object MusicApiService {

    private val jsonConfig = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private const val API_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"

    /**
     * 使用关键词搜索歌曲
     *
     * @param keyword 关键词（字符串）
     * @param searchType 搜索结果类型：0 歌曲，2 专辑，3 歌单，4 MV，7 歌词，8 用户
     * @param resultNum 每页结果数量
     * @param pageNum 页面序号
     * @param origin 是否返回原始完整数据
     * @return 解析后的 [JsonElement]，异常或无数据时返回 null
     */
    suspend fun searchWithKeyword(
        keyword: String,
        searchType: Int = 0,
        resultNum: Int = 10,
        pageNum: Int = 1,
        origin: Boolean = false
    ): JsonElement? {

        // 1. 构建请求 JSON
        val requestBody = buildJsonObject {
            putJsonObject("comm") {
                put("ct", "11")
                put("cv", "1003006")
                put("v", "1003006")
                put("os_ver", "15")
                put("phonetype", "24122RKC7C")
                put("tmeAppID", "qqmusiclight")
                put("nettype", "NETWORK_WIFI")
            }
            putJsonObject("req_0") {
                put("method", "DoSearchForQQMusicLite")
                put("module", "music.search.SearchCgiService")
                putJsonObject("param") {
                    put("search_id", Random.nextLong(10000000000000000L, 90000000000000000L).toString())
                    put("remoteplace", "search.android.keyboard")
                    put("grp", 1)
                    put("num_per_page", resultNum)
                    put("page_num", pageNum)
                    put("query", keyword)
                    put("search_type", searchType)
                    put("highlight", 0)
                    put("nqc_flag", 0)
                    put("page_id", 1)
                }
            }
        }.toString()

        return try {
                val responseText = ProviderSupport.request(API_URL, headers = mapOf(
                    "Accept" to "application/json, text/plain, */*", "User-Agent" to "okhttp/3.14.9",
                    "Referer" to "https://y.qq.com/"), connectTimeoutMs = 10_000, readTimeoutMs = 10_000,
                    body = requestBody.toRequestBody("application/json; charset=utf-8".toMediaType()),
                    requireSuccess = true) ?: return null
                val fullJson = jsonConfig.parseToJsonElement(responseText).jsonObject

                if (origin) return fullJson

                // 5. 提取核心数据
                val body = fullJson["req_0"]?.jsonObject
                    ?.get("data")?.jsonObject
                    ?.get("body")?.jsonObject ?: return null

                when (searchType) {
                    0, 7 -> buildJsonObject {
                        put("list", body["item_song"] ?: JsonArray(emptyList()))
                    }
                    2 -> body["album"]
                    3 -> body["songlist"]
                    4 -> body["mv"]
                    8 -> body["user"]
                    else -> body
                }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
