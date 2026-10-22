package io.github.proify.lyricon.localprovider.search

/** Only a platform's explicit child records are versions. Main search hits stay independent. */
object SearchVersionGroups {
    data class Group(val parent: SearchCandidate, val versions: List<SearchCandidate>)
    fun groups(items: List<SearchCandidate>, reference: MatchTarget, rules: MatchRules): List<Group> {
        val ranking = SearchCandidate.ranking(reference, rules)
        return items.sortedWith(ranking).map { parent ->
            Group(parent, parent.versions.filter { it.source == parent.source && it.id != parent.id }
                .distinctBy { it.id }.sortedWith(ranking))
        }
    }
    fun find(items: List<SearchCandidate>, id: String): SearchCandidate? = items.firstNotNullOfOrNull {
        if (it.id == id) it else find(it.versions, id)
    }
    fun update(items: List<SearchCandidate>, value: SearchCandidate): List<SearchCandidate> = items.map { old ->
        val next = if (old.id == value.id && !(old.lyricState == LyricState.READY && value.lyricState != LyricState.READY)) value else old
        // A late parent download must not reset already detected child versions.
        next.copy(versions = update(old.versions, value))
    }
}
