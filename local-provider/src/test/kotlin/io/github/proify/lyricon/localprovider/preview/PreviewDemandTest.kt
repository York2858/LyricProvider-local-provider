package io.github.proify.lyricon.localprovider.preview
import org.junit.Assert.*
import org.junit.Test

class PreviewDemandTest {
    @Test fun visiblePreviewRenewsAndClosedOrCrashedPreviewExpires() {
        val demand = PreviewDemand(30)
        assertEquals(0L, demand.expiresAt(0))
        assertEquals(31L, demand.update("page", true, 1))
        assertEquals(50L, demand.update("page", true, 20))
        assertEquals(50L, demand.expiresAt(49))
        assertEquals(0L, demand.expiresAt(50))
        assertEquals(90L, demand.update("page", true, 60))
        assertEquals(0L, demand.update("page", false, 61))
    }
    @Test fun oldActivityCannotUnsubscribeANewPreview() {
        val demand = PreviewDemand(30)
        demand.update("old", true, 0)
        demand.update("new", true, 10)
        assertEquals(40L, demand.update("old", false, 11))
        assertEquals(0L, demand.update("new", false, 12))
    }
}
