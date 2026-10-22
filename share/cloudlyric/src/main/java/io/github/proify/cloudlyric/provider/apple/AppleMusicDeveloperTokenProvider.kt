/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.apple

import io.github.proify.cloudlyric.provider.internal.ProviderSupport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppleMusicDeveloperTokenProvider {
    @Volatile
    private var cachedToken: String? = null
    private val tokenMutex = Mutex()

    suspend fun getToken(forceRefresh: Boolean = false): String {
        if (!forceRefresh) {
            cachedToken?.let { return it }
        }

        return tokenMutex.withLock {
            if (!forceRefresh) {
                cachedToken?.let { return@withLock it }
            }

            val token = fetchToken()
            cachedToken = token
            token
        }
    }

    private suspend fun fetchToken(): String {
        val pageHtml = ProviderSupport.httpGet(
            url = "https://music.apple.com",
            headers = mapOf(
                "User-Agent" to ProviderSupport.DEFAULT_USER_AGENT,
                "Accept-Encoding" to "identity"
            ),
            connectTimeoutMs = 8_000,
            readTimeoutMs = 10_000
        ).orEmpty()
        check(pageHtml.isNotBlank()) { "Failed to load Apple Music home page" }

        val assetPath = INDEX_JS_REGEX.find(pageHtml)?.value
            ?: error("Apple Music index script not found")

        val scriptBody = ProviderSupport.httpGet(
            url = "https://music.apple.com$assetPath",
            headers = mapOf(
                "User-Agent" to ProviderSupport.DEFAULT_USER_AGENT,
                "Accept-Encoding" to "identity"
            ),
            connectTimeoutMs = 8_000,
            readTimeoutMs = 10_000
        ).orEmpty()
        check(scriptBody.isNotBlank()) { "Failed to load Apple Music script" }

        return extractToken(scriptBody)
            ?: error("Apple Music bearer token not found")
    }

    internal fun extractToken(script: String): String? = TOKEN_REGEX.find(script)?.value

    private companion object {
        private val INDEX_JS_REGEX = Regex("""/assets/index[^"]+\.js""")
        private val TOKEN_REGEX = Regex("""eyJ[A-Za-z0-9_=-]+\.[A-Za-z0-9_=-]+\.[A-Za-z0-9_=-]+""")
    }
}
