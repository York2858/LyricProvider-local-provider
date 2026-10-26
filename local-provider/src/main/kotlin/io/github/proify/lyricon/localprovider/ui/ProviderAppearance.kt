package io.github.proify.lyricon.localprovider.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.graphics.ColorUtils

/** Appearance stays separate from provider settings, credentials and document grants. */
object ProviderAppearance {
    private fun prefs(c: Context) = c.getSharedPreferences("provider_appearance", Context.MODE_PRIVATE)
    fun glass(c: Context) = prefs(c).getBoolean("glass", false)
    fun blur(c: Context) = prefs(c).getInt("glass_blur", 16).coerceIn(0,32)
    fun opacity(c: Context) = prefs(c).getInt("glass_opacity", 58).coerceIn(25,90)
    fun corner(c: Context) = prefs(c).getInt("corner", 16).coerceIn(8,24)
    fun background(c: Context): Int? = if (prefs(c).contains("background")) prefs(c).getInt("background",0) else null
    fun border(c: Context): Int? = if (prefs(c).contains("border")) prefs(c).getInt("border",0) else null
    fun setGlass(c: Context, value: Boolean) { prefs(c).edit().putBoolean("glass",value).apply() }
    fun setBlur(c: Context,value: Int) { prefs(c).edit().putInt("glass_blur",value.coerceIn(0,32)).apply() }
    fun setOpacity(c: Context,value: Int) { prefs(c).edit().putInt("glass_opacity",value.coerceIn(25,90)).apply() }
    fun setCorner(c: Context,value: Int) { prefs(c).edit().putInt("corner",value.coerceIn(8,24)).apply() }
    fun setColor(c: Context,key: String,value: Int?) {
        require(key in setOf("background","border"))
        prefs(c).edit().apply { if(value==null) remove(key) else putInt(key,value or Color.BLACK) }.apply()
    }
    fun reset(c: Context) {
        prefs(c).edit().remove("mode").remove("monet").remove("seed").remove("background").remove("border")
            .remove("glass").remove("glass_blur").remove("glass_opacity").remove("corner").apply()
    }
    fun signature(c: Context) = listOf(glass(c),blur(c),opacity(c),corner(c),background(c),border(c)).joinToString(":")
    fun dark(c: Context) = c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    fun palette(c: Context, base: ProviderPalette): ProviderPalette {
        val seed = background(c) ?: return base
        val dark = ColorUtils.calculateLuminance(base.background)<.3
        val background = ColorUtils.blendARGB(seed,if(dark) 0xFF0B1420.toInt() else Color.WHITE,if(dark) .91f else .24f)
        val surface = ColorUtils.blendARGB(background,if(dark) Color.WHITE else Color.WHITE,if(dark) .055f else .30f)
        return base.copy(background=background,surface=surface,popup=ColorUtils.blendARGB(surface,if(dark) Color.WHITE else Color.WHITE,.035f),
            line=border(c) ?: ColorUtils.blendARGB(background,base.muted,.24f))
    }
}
