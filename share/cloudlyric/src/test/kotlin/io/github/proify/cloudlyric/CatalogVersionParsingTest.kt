package io.github.proify.cloudlyric

import io.github.proify.cloudlyric.provider.qq.QQMusicProvider
import io.github.proify.cloudlyric.provider.kugou.KuGouProvider
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CatalogVersionParsingTest {
    @Test fun qqUsesGrpWithoutCollapsingMainSearchHits() {
        val raw = Json.parseToJsonElement("""{"list":[
            {"id":1,"title":"同名歌曲","interval":240,"singer":[{"name":"歌手"}],"album":{"name":"主专辑","mid":"main"},
             "grp":[
                {"id":2,"title":"同名歌曲","interval":245,"album":{"name":"精选集","mid":"other"}},
                {"id":2,"title":"重复版本"},
                {"id":1,"title":"主记录自身"}
             ]},
            {"id":3,"title":"同名歌曲","album":{"name":"Live"}}
        ]}""")
        val page = QQMusicProvider().parseCatalogResponse(raw, 2, 1)
        assertEquals(listOf("1", "3"), page.tracks.map { it.key })
        val version = page.tracks.first().versions.single()
        assertEquals("2", version.key)
        assertEquals("精选集", version.metadata.albumName)
        assertEquals(245000L, version.metadata.durationMs)
        assertTrue(version.metadata.artworkUrl.contains("other"))
        assertTrue(version.versions.isEmpty())
        assertTrue(page.tracks.last().versions.isEmpty())
        assertEquals("2", page.nextCursor)
    }
    @Test fun kugouCollectsGrpAndLegacyGroupRecursivelyUsingIdOrHash() {
        val raw = """{"status":1,"error_code":0,"data":{"lists":[
            {"ID":"1","FileHash":"mainHash","SongName":"歌曲","AlbumName":"原专辑",
             "Grp":[
                {"ID":"2","FileHash":"secondHash","SongName":"歌曲","AlbumName":"精选集",
                 "Grp":[{"ID":null,"FileHash":"hashOnly","SongName":"歌曲","Duration":250}]},
                {"ID":"1","FileHash":"mainHash","SongName":"主记录自身"}
             ],
             "group":[
                {"ID":"2","FileHash":"duplicateHash","SongName":"重复版本"},
                {"ID":"4","FileHash":"legacyHash","SongName":"旧版字段","AlbumName":"旧专辑"}
             ]},
            {"ID":"5","FileHash":"liveHash","SongName":"歌曲","AlbumName":"Live"}
        ]}}"""
        val page = KuGouProvider().parseCatalogResponse(raw, 2, 1)
        assertEquals(listOf("1", "5"), page.tracks.map { it.key })
        val versions = page.tracks.first().versions
        assertEquals(listOf("2", "hashOnly", "4"), versions.map { it.key })
        assertEquals("secondHash", versions.first().hints["hash"])
        assertEquals("精选集", versions.first().metadata.albumName)
        assertEquals("", versions[1].hints["audioId"])
        assertTrue(versions.all { it.versions.isEmpty() })
        assertTrue(page.tracks.last().versions.isEmpty())
        assertEquals("2", page.nextCursor)
    }
}
