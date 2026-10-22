/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.apple

data class AppleMusicConfig(
    val storefront: String = "us",
    val mediaUserToken: String = "",
    val language: String = "en-US",
    val developerToken: String = "",
    val lyricsTypePreference: String = ""
) {
    fun isConfigured(): Boolean = mediaUserToken.isNotBlank()
}
