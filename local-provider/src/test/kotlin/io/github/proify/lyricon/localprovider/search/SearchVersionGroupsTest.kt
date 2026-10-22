package io.github.proify.lyricon.localprovider.search

import io.github.proify.cloudlyric.CatalogTrack
import io.github.proify.cloudlyric.LyricsResult
import io.github.proify.lyricon.lyric.model.RichLyricLine
import org.junit.Assert.*
import org.junit.Test

class SearchVersionGroupsTest {
    private val reference = MatchTarget("歌曲", "歌手", "专辑", 240000)
    private fun track(id: String, versions: List<CatalogTrack> = emptyList()) = CatalogTrack(id,
        LyricsResult(trackName = "歌曲", artistName = "歌手", albumName = "专辑", durationMs = 240000), versions = versions)
    private fun row(source: String, id: String, versions: List<CatalogTrack> = emptyList()) = SearchCandidate.catalog(source, track(id, versions))
    @Test fun identicalNamesAcrossPlatformsAndWithinOnePlatformRemainSeparateMainRows() {
        val rows = listOf(row("QQMusicProvider", "1"), row("KuGou", "1"), row("NetEase", "1"), row("QQMusicProvider", "2"))
        val groups = SearchVersionGroups.groups(rows, reference, MatchRules())
        assertEquals(4, groups.size)
        assertEquals(rows.map { it.id }.toSet(), groups.map { it.parent.id }.toSet())
        assertTrue(groups.all { it.versions.isEmpty() })
    }
    @Test fun onlyExplicitChildrenOfTheSamePlatformBecomeMoreVersions() {
        val qq = row("QQMusicProvider", "1", listOf(track("2"), track("2"), track("1")))
        val kg = row("KuGou", "1", listOf(track("2")))
        val groups = SearchVersionGroups.groups(listOf(qq, kg), reference, MatchRules())
        assertEquals(2, groups.size)
        groups.forEach { group ->
            assertEquals(1, group.versions.size)
            assertEquals(group.parent.source, group.versions.single().source)
            assertTrue(group.versions.single().versions.isEmpty())
        }
        val invalid = qq.copy(versions = qq.versions + kg.versions)
        assertEquals(1, SearchVersionGroups.groups(listOf(invalid), reference, MatchRules()).single().versions.size)
    }
    @Test fun childUpdatesStayUnderTheirParentAndSurviveLateParentUpdates() {
        val parent = row("KuGou", "1", listOf(track("2")))
        val child = parent.versions.single()
        val ready = child.copy(lyricState = LyricState.READY, song = child.song.copy(lyrics = listOf(RichLyricLine(text = "歌词"))))
        val updated = SearchVersionGroups.update(listOf(parent), ready)
        assertEquals(1, updated.size)
        assertEquals(ready, SearchVersionGroups.find(updated, child.id))
        val lateParent = SearchVersionGroups.update(updated, parent.copy(lyricState = LyricState.READY))
        assertEquals(ready, lateParent.single().versions.single())
        val lateChecking = SearchVersionGroups.update(lateParent, child.copy(lyricState = LyricState.CHECKING))
        assertEquals(LyricState.READY, lateChecking.single().versions.single().lyricState)
    }
    @Test fun savedMainLyricsDoNotHideThePlatformsVersionList() {
        val parent = row("QQMusicProvider", "1", listOf(track("2")))
        val saved = parent.copy(localUri = "saved:${parent.id}", versions = emptyList(), lyricState = LyricState.READY)
        val merged = SearchCandidate.distinct(listOf(parent, saved)).single()
        assertEquals(saved.localUri, merged.localUri)
        assertEquals(parent.versions, merged.versions)
    }
    @Test fun scoreRankingAppliesToMainRowsAndVersionsWithoutPromotingAChildToMainRow() {
        val exact = row("QQMusicProvider", "1", listOf(track("2"), track("3"))).let { parent ->
            parent.copy(versions = listOf(parent.versions[0].copy(song = parent.versions[0].song.copy(duration = 120000)), parent.versions[1]))
        }
        val other = row("NetEase", "4").let { it.copy(song = it.song.copy(name = "其他歌")) }
        val groups = SearchVersionGroups.groups(listOf(other, exact), reference, MatchRules())
        assertEquals(exact.id, groups.first().parent.id)
        assertEquals(exact.versions[1].id, groups.first().versions.first().id)
    }
}
