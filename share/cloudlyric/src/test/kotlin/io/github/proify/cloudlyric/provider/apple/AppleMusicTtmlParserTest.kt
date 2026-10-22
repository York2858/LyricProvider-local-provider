/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.cloudlyric.provider.apple

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppleMusicTtmlParserTest {

    @Test
    fun parse_extractsTranslationBackgroundAndDuetAlignment() {
        val ttml = """
            <tt xmlns:itunes="http://itunes.apple.com/lyric-ttml-extensions" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <head>
                <metadata>
                  <ttm:agent xml:id="v1" type="1" />
                  <ttm:agent xml:id="v2" type="1" />
                  <translations>
                    <text for="l1" text="你好" />
                    <text for="l2" text="世界" />
                  </translations>
                  <transliterations>
                    <text for="l1" text="ni hao" />
                  </transliterations>
                </metadata>
              </head>
              <body>
                <div>
                  <p itunes:key="l1" begin="0.0s" end="2.0s" ttm:agent="v1">
                    <span begin="0.0s" end="0.8s">He</span>
                    <span begin="0.8s" end="1.4s">llo</span>
                    <span ttm:role="x-bg" begin="1.4s" end="2.0s">!</span>
                  </p>
                  <p itunes:key="l2" begin="2.0s" end="4.0s" ttm:agent="v2">
                    <span begin="2.0s" end="3.0s">World</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lines = AppleMusicTtmlParser.parse(ttml)

        assertEquals(2, lines.size)

        assertEquals("Hello", lines[0].text)
        assertEquals("!", lines[0].secondary)
        assertEquals("你好", lines[0].translation)
        assertEquals("ni hao", lines[0].roma)
        assertFalse(lines[0].isAlignedRight)
        assertEquals(2, lines[0].words.orEmpty().size)
        assertEquals(1, lines[0].secondaryWords.orEmpty().size)

        assertEquals("World", lines[1].text)
        assertEquals("世界", lines[1].translation)
        assertTrue(lines[1].isAlignedRight)
    }
}
