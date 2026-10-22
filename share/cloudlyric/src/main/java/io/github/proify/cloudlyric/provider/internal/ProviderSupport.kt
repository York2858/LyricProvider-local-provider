/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.internal

import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import io.github.proify.extensions.net.awaitText
import kotlinx.coroutines.CancellationException
import okhttp3.RequestBody

internal object ProviderSupport {
    const val DEFAULT_USER_AGENT: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36"

    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun buildKeyword(
        query: String? = null,
        trackName: String? = null,
        artistName: String? = null,
        albumName: String? = null
    ): String {
        val explicitQuery = query?.trim().orEmpty()
        if (explicitQuery.isNotEmpty()) return explicitQuery

        val parts = linkedSetOf<String>()
        listOf(trackName, artistName, albumName)
            .map { it?.trim().orEmpty() }
            .filter { it.isNotEmpty() }
            .forEach(parts::add)

        return parts.joinToString(" ")
    }

    fun encodeParams(params: Map<String, String>): String {
        return params.entries.joinToString("&") { (key, value) ->
            val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8.name())
            val encodedValue = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
            "$encodedKey=$encodedValue"
        }
    }

    suspend fun httpGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        connectTimeoutMs: Int = 6_000,
        readTimeoutMs: Int = 8_000
    ): String? = request(url, headers, connectTimeoutMs, readTimeoutMs)

    suspend fun request(url: String, headers: Map<String, String> = emptyMap(),
                        connectTimeoutMs: Int = 6_000, readTimeoutMs: Int = 8_000,
                        body: RequestBody? = null, requireSuccess: Boolean = false): String? {
        return try {
            val client = httpClient.newBuilder()
                .connectTimeout(connectTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .build()
            val requestBuilder = Request.Builder()
                .url(URI.create(url).toURL())
                .get()
            if (body != null) requestBuilder.post(body)
            headers.forEach { (key, value) -> requestBuilder.header(key, value) }
            client.newCall(requestBuilder.build()).awaitText(requireSuccess)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            null
        }
    }
}
