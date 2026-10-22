/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.apple

import kotlin.test.Test
import kotlin.test.assertEquals

class AppleMusicProviderTest {

    private val provider = AppleMusicProvider(
        AppleMusicConfig(
            storefront = "us",
            mediaUserToken = "test-token"
        )
    )

    @Test
    fun selectPreferredTtml_prefersLocalizationForChineseLanguage() {
        val selected = provider.selectPreferredTtml(
            ttml = "<tt>original</tt>",
            localizedTtml = "<tt>translated</tt>",
            language = "zh-Hans-CN"
        )

        assertEquals("<tt>translated</tt>", selected)
    }

    @Test
    fun selectPreferredTtml_prefersOriginalForEnglishLanguage() {
        val selected = provider.selectPreferredTtml(
            ttml = "<tt>original</tt>",
            localizedTtml = "<tt>translated</tt>",
            language = "en-US"
        )

        assertEquals("<tt>original</tt>", selected)
    }

    @Test
    fun selectPreferredTtml_fallsBackToOriginalWhenLocalizationMissing() {
        val selected = provider.selectPreferredTtml(
            ttml = "<tt>original</tt>",
            localizedTtml = "",
            language = "zh-Hans-CN"
        )

        assertEquals("<tt>original</tt>", selected)
    }
}
