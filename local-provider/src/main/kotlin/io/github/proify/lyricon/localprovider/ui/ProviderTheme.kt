package io.github.proify.lyricon.localprovider.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.ColorResourcesOverride
import com.google.android.material.color.MaterialColorUtilitiesHelper
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeTonalSpot
import io.github.proify.lyricon.localprovider.R
import java.util.WeakHashMap

data class ProviderPalette(val primary: Int, val onPrimary: Int, val background: Int, val surface: Int,
    val popup: Int, val text: Int, val muted: Int, val tint: Int, val line: Int)

/** Mirrors Lyrico's seed -> HCT -> Tonal Spot (contrast 0) and surface-role mapping. */
object ProviderTheme {
    private val installed = WeakHashMap<Context, ProviderPalette>()
    private fun prefs(context: Context) = context.getSharedPreferences("provider_appearance", Context.MODE_PRIVATE)
    val seeds = linkedMapOf("跟随系统" to 0, "蓝色" to 0xFF3482FF.toInt(), "绿色" to 0xFF36D167.toInt(),
        "黄色" to 0xFFFFB21D.toInt(), "橙色" to 0xFFFF5722.toInt(), "紫色" to 0xFF7C4DFF.toInt(),
        "粉色" to 0xFFE91E63.toInt(), "青色" to 0xFF00BCD4.toInt())
    fun mode(context: Context) = prefs(context).getString("mode", "auto") ?: "auto"
    fun monet(context: Context) = prefs(context).getBoolean("monet", true)
    fun seed(context: Context) = prefs(context).getInt("seed", 0)
    fun setMode(context: Context, value: String) { prefs(context).edit().putString("mode", value).apply() }
    fun setMonet(context: Context, value: Boolean) { prefs(context).edit().putBoolean("monet", value).apply() }
    fun setSeed(context: Context, value: Int) { prefs(context).edit().putInt("seed", value).apply() }
    fun nightMode(context: Context) = when (mode(context)) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }
    private fun dark(context: Context) = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun resolvedSeed(context: Context): Int {
        seed(context).takeIf { it != 0 }?.let { return it }
        if (Build.VERSION.SDK_INT >= 31) {
            val name = if (Build.VERSION.SDK_INT >= 34) {
                if (dark(context)) "system_primary_dark" else "system_primary_light"
            } else if (dark(context)) "system_accent1_200" else "system_accent1_600"
            val id = context.resources.getIdentifier(name, "color", "android")
            if (id != 0) return context.getColor(id)
        }
        return seeds.getValue("蓝色")
    }
    fun signature(context: Context) = listOf(mode(context), monet(context), resolvedSeed(context), dark(context)).joinToString(":")
    fun colors(context: Context): ProviderPalette = installed[context] ?: create(context).also { installed[context] = it }
    internal fun tonal(seed: Int, dark: Boolean): ProviderPalette = palette(SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0))
    private fun palette(s: SchemeTonalSpot) = ProviderPalette(s.primary, s.onPrimary, s.surface,
        s.surfaceContainer, s.surfaceContainerHigh, s.onSurface, s.onSurfaceVariant, s.primaryContainer, s.outlineVariant)
    private fun create(context: Context) = if (monet(context)) tonal(resolvedSeed(context), dark(context))
        else if (dark(context)) ProviderPalette(0xFF66AFFF.toInt(), 0xFF09131B.toInt(), 0xFF111B25.toInt(),
            0xFF18232F.toInt(), 0xFF1C2936.toInt(), 0xFFF2F6FA.toInt(), 0xFF8E9DAC.toInt(), 0xFF203D59.toInt(), 0xFF2B3948.toInt())
        else ProviderPalette(0xFF3390EC.toInt(), -1, 0xFFF4F6F8.toInt(), -1, -1, 0xFF1C2733.toInt(),
            0xFF7B8894.toInt(), 0xFFE3EFFB.toInt(), 0xFFD7DFE8.toInt())

    @SuppressLint("RestrictedApi") // Material's resource override also themes dialogs, menus and native controls.
    fun install(context: Context): ProviderPalette {
        val colors = create(context)
        installed[context] = colors
        if (Build.VERSION.SDK_INT >= 30) {
            val scheme = com.google.android.material.color.utilities.SchemeTonalSpot(
                com.google.android.material.color.utilities.Hct.fromInt(resolvedSeed(context)), dark(context), 0.0)
            val resources = MaterialColorUtilitiesHelper.createColorResourcesIdsToColorValues(scheme).toMutableMap()
            resources.putAll(mapOf(R.color.provider_primary to colors.primary, R.color.provider_on_primary to colors.onPrimary,
                R.color.provider_background to colors.background, R.color.provider_surface to colors.surface,
                R.color.provider_popup to colors.popup, R.color.provider_text to colors.text,
                R.color.provider_muted to colors.muted, R.color.provider_tint to colors.tint,
                R.color.provider_line to colors.line, R.color.provider_success to colors.primary,
                com.google.android.material.R.color.material_personalized_color_primary to colors.primary,
                com.google.android.material.R.color.material_personalized_color_on_primary to colors.onPrimary,
                com.google.android.material.R.color.material_personalized_color_surface to colors.background,
                com.google.android.material.R.color.material_personalized_color_surface_container to colors.surface,
                com.google.android.material.R.color.material_personalized_color_surface_container_high to colors.popup,
                com.google.android.material.R.color.material_personalized_color_on_surface to colors.text,
                com.google.android.material.R.color.material_personalized_color_on_surface_variant to colors.muted,
                com.google.android.material.R.color.material_personalized_color_outline_variant to colors.line))
            ColorResourcesOverride.getInstance()?.applyIfPossible(context, resources)
        }
        return colors
    }
}
