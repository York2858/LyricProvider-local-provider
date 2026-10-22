package io.github.proify.cloudlyric

/** A catalog entry exists independently of whether its lyrics can be downloaded. */
data class CatalogTrack(
    val key: String,
    val metadata: LyricsResult,
    val hints: Map<String, String> = emptyMap(),
    val versions: List<CatalogTrack> = emptyList()
)

/** The opaque cursor belongs to the source, never to a growing first-page limit. */
data class CatalogPage(val tracks: List<CatalogTrack>, val nextCursor: String? = null)

/** Only deliberate, non-sensitive service messages are exposed by the search UI. */
class CatalogAccessException(message: String) : IllegalStateException(message)

interface CatalogProvider {
    suspend fun searchCatalog(query: String, pageSize: Int, cursor: String? = null): CatalogPage
    suspend fun loadLyrics(track: CatalogTrack): LyricsResult?
}
