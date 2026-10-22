package io.github.proify.lyricon.localprovider.search

import io.github.proify.lyricon.localprovider.data.ProviderSettings
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class MatchScorerTest {
    private val target = MatchTarget("My Song", "Singer", "Album", 240000)
    @Test fun exactMetadataGetsOneHundredRegardlessOfWeightSum() {
        assertEquals(100, MatchScorer.evaluate(target, target, MatchRules(titleWeight = 90)).total)
    }
    @Test fun translatedTitleAliasDoesNotRejectTheSameSongButLiveVersionRemainsDistinct() {
        assertTrue(MatchScorer.evaluate(MatchTarget("10年後の君へ (致10年后的你)", "板野友美"),
            MatchTarget("10年後の君へ", "板野友美"), MatchRules()).eligible)
        assertFalse(MatchScorer.evaluate(target, target.copy(title = "My Song (Live)"), MatchRules()).eligible)
    }
    @Test fun unknownReferenceAlbumAndDurationDoNotReduceScore() {
        val score = MatchScorer.evaluate(target.copy(album = null, durationMs = 0), target, MatchRules())
        assertEquals(100, score.total); assertNull(score.parts[2].similarity); assertNull(score.parts[3].similarity)
    }
    @Test fun missingCandidateMetadataDoesNotBecomeAPerfectMatch() {
        val score = MatchScorer.evaluate(target, target.copy(artist = null), MatchRules())
        assertEquals(70, score.total); assertFalse(score.eligible)
    }
    @Test fun changingWeightsActuallyChangesRanking() {
        val title = target.copy(artist = "Someone Else")
        val artist = target.copy(title = "Different")
        val rules = MatchRules(titleWeight = 80, artistWeight = 10, albumWeight = 0, durationWeight = 0,
            requireTitle = false, requireArtist = false)
        assertTrue(MatchScorer.evaluate(target, title, rules).total > MatchScorer.evaluate(target, artist, rules).total)
        val reversed = rules.copy(titleWeight = 10, artistWeight = 80)
        assertTrue(MatchScorer.evaluate(target, title, reversed).total < MatchScorer.evaluate(target, artist, reversed).total)
    }
    @Test fun exactNormalizedAndFuzzyModesAreDifferent() {
        assertEquals(0.0, MatchScorer.similarity("My Song", "my song", "exact")!!, 0.0)
        assertEquals(0.0, MatchScorer.similarity("My Song", "Ｍｙ－Ｓｏｎｇ", "exact")!!, 0.0)
        assertEquals(1.0, MatchScorer.similarity("My Song", "Ｍｙ－Ｓｏｎｇ", "normalized")!!, 0.0)
        assertTrue(MatchScorer.similarity("My Song", "My Songs", "fuzzy")!! > .8)
    }
    @Test fun durationScoreDecreasesUntilTheConfiguredTolerance() {
        val rules = MatchRules(durationWeight = 100, titleWeight = 0, artistWeight = 0, albumWeight = 0)
        assertEquals(100, MatchScorer.evaluate(target, target.copy(durationMs = 241000), rules).total)
        assertEquals(50, MatchScorer.evaluate(target, target.copy(durationMs = 243000), rules).total)
        assertEquals(0, MatchScorer.evaluate(target, target.copy(durationMs = 245000), rules).total)
    }
    @Test fun durationToleranceAndHardGateAreIndependentFromWeights() {
        val far = target.copy(durationMs = 250000)
        val rules = MatchRules(durationWeight = 0, requireDuration = true, durationToleranceMs = 5000)
        assertFalse(MatchScorer.evaluate(target, far, rules).eligible)
        assertTrue(MatchScorer.evaluate(target, far, rules.copy(durationToleranceMs = 15000)).eligible)
    }
    @Test fun minimumScoreIsAppliedToAutoSelection() {
        assertFalse(MatchScorer.evaluate(target, target.copy(album = "Other"), MatchRules(minimumScore = 95)).eligible)
    }
    @Test fun allZeroWeightsAndInvalidValuesAreRejected() {
        assertFalse(MatchRules(0, 0, 0, 0).valid())
        assertFalse(MatchRules(durationToleranceMs = 0).valid())
        assertFalse(MatchRules(titleMode = "unknown").valid())
    }
    @Test fun emptyTargetsNeverAutoMatchAndScoresRemainFinite() {
        val score = MatchScorer.evaluate(MatchTarget(), target, MatchRules())
        assertEquals(0, score.total); assertFalse(score.eligible)
    }
    @Test fun oldSettingsRemainReadableWithDefaultRules() {
        assertEquals(MatchRules(), Json.decodeFromString<ProviderSettings>("{}").matchRules)
    }
    @Test fun groupingKeepsDifferentArtistsSeparateAndCollectsVersions() {
        assertEquals(MatchScorer.groupKey(target), MatchScorer.groupKey(target.copy(title = "My Song (Live)")))
        assertNotEquals(MatchScorer.groupKey(target), MatchScorer.groupKey(target.copy(artist = "Cover Singer")))
    }
}
