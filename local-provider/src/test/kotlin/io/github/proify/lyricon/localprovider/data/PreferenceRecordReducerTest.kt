package io.github.proify.lyricon.localprovider.data

import org.junit.Assert.*
import org.junit.Test

class PreferenceRecordReducerTest {
    private fun observation(rating:Int?=null,favorite:Boolean?=null,time:Long=1000,mediaId:String="song")=
        PreferenceObservation("player",mediaId,"Song","Artist","Album",3000,favorite,rating,observedAt=time)
    @Test fun unknownAndInitialZeroDoNotClearFiles() {
        val baseline=PreferenceRecordReducer.merge(null,observation(0,false))
        assertFalse(baseline.hasChanges)
        val changed=PreferenceRecordReducer.merge(baseline,observation(60,true,2000))
        assertEquals(60,changed.rating);assertEquals(true,changed.favorite);assertNotNull(changed.favoriteTime)
        val absent=PreferenceRecordReducer.merge(changed,observation(time=3000))
        assertEquals(changed.favoriteTime,absent.favoriteTime);assertEquals(60,absent.rating)
        assertEquals(changed.revision,absent.revision)
    }
    @Test fun repeatedFavoriteKeepsOriginalTimeAndWrittenState() {
        val first=PreferenceRecordReducer.merge(null,observation(60,true)).copy(status="written")
        val again=PreferenceRecordReducer.merge(first,observation(60,true,3000))
        assertEquals(first,again)
    }
    @Test fun explicitClearsRemainIndependent() {
        val first=PreferenceRecordReducer.merge(null,observation(60,true))
        val clearFavorite=PreferenceRecordReducer.merge(first,observation(60,false,2000))
        assertEquals(false,clearFavorite.favorite);assertNull(clearFavorite.favoriteTime);assertEquals(60,clearFavorite.rating)
        val clearRating=PreferenceRecordReducer.merge(clearFavorite,observation(0,null,3000))
        assertEquals(0,clearRating.rating);assertEquals(false,clearRating.favorite)
        assertTrue(clearRating.revision>clearFavorite.revision)
    }
    @Test fun differentPlayerAndMediaIdCannotMergeAndManualChoiceIsKept() {
        val value=observation(60,true)
        assertNotEquals(value.identity(),value.copy(player="other").identity())
        assertNotEquals(value.identity(),observation(mediaId="other").identity())
        assertNotEquals(value.identity(),value.copy(title="Different recording with a reused ID").identity())
        val chosen=PreferenceRecordReducer.merge(null,value).copy(file=PreferenceFile("/a","","手动确认"))
        val update=PreferenceRecordReducer.merge(chosen,value.copy(file=PreferenceFile("/b","","Local Provider 补充")))
        assertEquals("/a",update.file?.path)
    }
    @Test fun ambiguityStaysUnresolvedUntilFileIsChosen() {
        val r=PreferenceRecordReducer.merge(null,observation(60).copy(candidates=listOf(PreferenceFile("/a"),PreferenceFile("/b"))))
        assertFalse(r.ready);assertEquals(2,r.candidates.size)
        val resolved=PreferenceRecordReducer.merge(r,observation(60).copy(file=PreferenceFile("/a")))
        assertTrue(resolved.ready)
    }
}
