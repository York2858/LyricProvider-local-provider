/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.soda

import kotlin.test.Test
import kotlin.test.assertEquals

class SodaLyricParserTest {

    @Test
    fun parse_extractsTimedWords() {
        val raw = """
            [1000,1200]<0,400,0>He<400,400,0>llo<800,400,0>!
        """.trimIndent()

        val lines = SodaLyricParser.parse(raw)

        assertEquals(1, lines.size)
        assertEquals("Hello!", lines[0].text)
        assertEquals(3, lines[0].words.orEmpty().size)
        assertEquals(1000L, lines[0].begin)
        assertEquals(2200L, lines[0].end)
        assertEquals("He", lines[0].words.orEmpty()[0].text)
        assertEquals(1000L, lines[0].words.orEmpty()[0].begin)
        assertEquals(1400L, lines[0].words.orEmpty()[0].end)
    }

    @Test
    fun parse_fallsBackToWholeLineWhenNoWordTagsExist() {
        val raw = """
            [3000,800]No timing tags
        """.trimIndent()

        val lines = SodaLyricParser.parse(raw)

        assertEquals(1, lines.size)
        assertEquals("No timing tags", lines[0].text)
        assertEquals(1, lines[0].words.orEmpty().size)
        assertEquals(3000L, lines[0].begin)
        assertEquals(3800L, lines[0].end)
    }
}
