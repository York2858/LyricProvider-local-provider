/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.apple

import io.github.proify.cloudlyric.provider.internal.ProviderSupport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AppleMusicDeveloperTokenProvider(
    private val loadText: suspend (String) -> String? = { url ->
        // Let OkHttp negotiate gzip: the homepage and script are multi-megabyte uncompressed.
        ProviderSupport.httpGetOrThrow(url, mapOf("User-Agent" to ProviderSupport.DEFAULT_USER_AGENT))
    }
) {
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
        val pageHtml = loadText("https://music.apple.com").orEmpty()
        check(pageHtml.isNotBlank()) { "Failed to load Apple Music home page" }

        val assetPath = INDEX_JS_REGEX.find(pageHtml)?.value
            ?: error("Apple Music index script not found")

        val scriptBody = loadText("https://music.apple.com$assetPath").orEmpty()
        check(scriptBody.isNotBlank()) { "Failed to load Apple Music script" }

        return extractToken(scriptBody)
            ?: error("Apple Music bearer token not found")
    }

    internal fun extractToken(script: String): String? = TOKEN_REGEX.find(script)?.value

    companion object {
        // Search sessions and hooked-player searches share one token within their process.
        internal val shared by lazy { AppleMusicDeveloperTokenProvider() }
        private val INDEX_JS_REGEX = Regex("""/assets/index[^"\s]+\.js""")
        private val TOKEN_REGEX = Regex("""eyJ[A-Za-z0-9_=-]+\.[A-Za-z0-9_=-]+\.[A-Za-z0-9_=-]+""")
    }
}
