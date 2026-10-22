package io.github.proify.lyricon.localprovider.search

import io.github.proify.cloudlyric.LyricsProvider
import io.github.proify.cloudlyric.provider.LyricsConfig
import io.github.proify.cloudlyric.provider.apple.AppleMusicConfig
import io.github.proify.cloudlyric.provider.apple.AppleMusicProvider
import io.github.proify.lyricon.localprovider.data.ProviderSettings

object SearchProviders {
    fun create(settings: ProviderSettings): List<LyricsProvider> = buildList {
        addAll(LyricsConfig.ALL_PROVIDERS.filter { it.id !in settings.disabledSources })
        if (settings.appleEnabled && settings.appleConfigured && settings.appleUserToken.isNotBlank()) {
            add(AppleMusicProvider(AppleMusicConfig(storefront = settings.appleStorefront,
                mediaUserToken = settings.appleUserToken, developerToken = settings.appleDeveloperToken,
                language = settings.appleLanguage, lyricsTypePreference = "")))
        }
    }
}
