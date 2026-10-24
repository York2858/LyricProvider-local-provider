package io.github.proify.lyricon.localprovider.preview

import io.github.proify.lyricon.localprovider.ui.ProviderTheme
import org.junit.Assert.*
import org.junit.Test

class ProviderPaletteTest {
    @Test fun changingSeedUpdatesSurfacesAsWellAsAccent() {
        val blue = ProviderTheme.tonal(0xFF3482FF.toInt(), false)
        val green = ProviderTheme.tonal(0xFF36D167.toInt(), false)
        assertNotEquals(blue.primary, green.primary)
        assertNotEquals(blue.surface, green.surface)
        assertNotEquals(blue.background, green.background)
        assertNotEquals(blue.text, green.text)
        assertNotEquals(blue.popup, green.popup)
    }
    @Test fun lightAndDarkSchemesKeepButtonAndTextContrast() {
        ProviderTheme.seeds.values.filter { it != 0 }.forEach { seed ->
            listOf(false, true).forEach { dark ->
                val palette = ProviderTheme.tonal(seed, dark)
                assertTrue(contrast(palette.primary, palette.onPrimary) >= 4.5)
                assertTrue(contrast(palette.text, palette.background) >= 4.5)
                assertTrue(contrast(palette.muted, palette.surface) >= 4.5)
            }
        }
    }
    private fun contrast(a: Int, b: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val c = ((color ushr shift) and 255) / 255.0
                return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }
}
