/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.kugou

import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class KugouKrcParserTest {

    @Test
    fun parse_extractsWordsTranslationAndRoma() {
        val languageJson = """
            {
              "content": [
                {
                  "type": 0,
                  "lyricContent": [
                    ["ko", "n"],
                    ["ni", "chi"]
                  ]
                },
                {
                  "type": 1,
                  "lyricContent": [
                    ["你好"],
                    ["世界"]
                  ]
                }
              ]
            }
        """.trimIndent()
        val encodedLanguage = Base64.getEncoder()
            .encodeToString(languageJson.toByteArray(StandardCharsets.UTF_8))

        val raw = """
            [ti:Test]
            [language:$encodedLanguage]
            [0,1000]<0,500,0>こ<500,500,0>ん
            [1000,1000]<0,500,0>に<500,500,0>ち
        """.trimIndent()

        val lines = KugouKrcParser.parse(raw)

        assertEquals(2, lines.size)

        assertEquals("こん", lines[0].text)
        assertEquals("你好", lines[0].translation)
        assertEquals("ko n", lines[0].roma)
        assertEquals(2, lines[0].words.orEmpty().size)
        assertEquals(0L, lines[0].begin)
        assertEquals(1000L, lines[0].end)

        assertEquals("にち", lines[1].text)
        assertEquals("世界", lines[1].translation)
        assertEquals("ni chi", lines[1].roma)
        assertEquals(1000L, lines[1].begin)
        assertEquals(2000L, lines[1].end)
    }
}
