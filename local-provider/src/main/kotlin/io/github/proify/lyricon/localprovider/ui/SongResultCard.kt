package io.github.proify.lyricon.localprovider.ui

import io.github.proify.lyricon.localprovider.lyrics.LyricTiming
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import io.github.proify.lyricon.lyric.model.Song

/** Lyrico-style row shared by search results, expanded versions and the playing song. */
class SongResultCard(
    context: Context, song: Song, album: String, source: String,
    score: Int? = null, moreVersions: Int = 0, expanded: Boolean = false, artwork: String = "",
    onPreview: () -> Unit, onScore: (() -> Unit)? = null, onToggleVersions: (() -> Unit)? = null,
    lyricStatus: String = "", heroHeader: Boolean = false
) : LinearLayout(context) {
    private val palette = ProviderTheme.colors(context)
    private val ink = palette.text
    private val muted = palette.muted
    private val accent = palette.primary
    private val tint = palette.tint

    init {
        orientation = HORIZONTAL; gravity = Gravity.TOP
        if (!heroHeader) {
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                setColor(palette.surface); cornerRadius = dp(20).toFloat()
            }
        }
        isClickable = true; isFocusable = true; setOnClickListener { onPreview() }
        contentDescription = listOfNotNull(song.name, song.artist, album.takeIf { it.isNotBlank() }, source).joinToString("，")
        addView(ImageView(context).apply {
            tag = "songCover"; setImageDrawable(ProviderIcon("music", muted))
            background = pill(tint, 8); setPadding(dp(16), dp(16), dp(16), dp(16))
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            clipToOutline = true
            io.github.proify.lyricon.localprovider.artwork.ArtworkLoader.bind(this, artwork)
        }, LayoutParams(dp(if (heroHeader) 62 else 56), dp(if (heroHeader) 62 else 56)).apply { marginEnd = dp(12) })
        val info = LinearLayout(context).apply { orientation = VERTICAL; tag = "songInfo" }
        addView(info, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(label(song.name.orEmpty(), if (heroHeader) 20 else 16, ink).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); tag = "songTitle"
            if (heroHeader) { maxLines = 2; ellipsize = TextUtils.TruncateAt.END } else enableMarquee()
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        info.addView(header)
        val artistAlbum = listOf(song.artist.orEmpty(), album).filter { it.isNotBlank() }.joinToString(" ・ ")
        if (artistAlbum.isNotBlank()) info.addView(label(artistAlbum, 13, muted).apply { tag = "songArtistAlbum"; enableMarquee() },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
        val lines = song.lyrics.orEmpty()
        val features = buildList {
            if (lines.isNotEmpty()) add(when {
                LyricTiming.hasWordTiming(lines) -> "逐字"
                lines.any { it.begin > 0 || it.end > it.begin } -> "逐行"
                else -> "纯文本"
            })
            if (lines.any { !it.translation.isNullOrBlank() }) add("翻译")
            if (lines.any { !it.roma.isNullOrBlank() }) add("罗马音")
        }.joinToString(" | ")
        val details = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (song.duration > 0) details.addView(label("%02d:%02d".format(song.duration / 60000, song.duration / 1000 % 60), 11, muted)
            .apply { tag = "songDuration" }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        if (features.isNotBlank() || lyricStatus.isNotBlank()) details.addView(label(features.ifBlank { lyricStatus }, 11,
            if (features.isNotBlank()) accent else muted).apply { tag = "songFeatures" },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (!heroHeader && details.childCount > 0) info.addView(details, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })
        if (source.isNotBlank() && !heroHeader) info.addView(badge(source).apply { maxWidth = dp(160); tag = "songSource" },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        val footer = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(36) }
        if (moreVersions > 0 && onToggleVersions != null) {
            footer.minimumHeight = dp(44)
            footer.addView(label(if (expanded) "收起版本" else "更多版本 $moreVersions", 10, accent).apply {
                tag = "songVersions"; gravity = Gravity.CENTER; minimumHeight = dp(44)
                setPadding(dp(6), 0, dp(4), 0)
                // Explicit bounds keep the arrow centered even though Drawable has no intrinsic size.
                setCompoundDrawablesRelative(null, null,
                    ProviderIcon(if (expanded) "up" else "down", accent).apply { setBounds(0, 0, dp(12), dp(12)) }, null)
                compoundDrawablePadding = dp(3); isClickable = true; isFocusable = true
                setOnClickListener { onToggleVersions() }
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        } else footer.addView(Space(context), LayoutParams(0, 1, 1f))
        if (moreVersions > 0 && onToggleVersions != null) info.addView(footer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        if (score != null) addView(TextView(context).apply {
            tag = "songMatchScore"; textSize = 19f; setTextColor(accent); gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val value = "$score\n分"
            text = SpannableString(value).apply { setSpan(RelativeSizeSpan(.58f), value.lastIndex, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            minimumHeight = dp(52); contentDescription = "匹配 $score 分，查看评分明细"
            if (onScore != null) { isClickable = true; isFocusable = true; setOnClickListener { onScore() } }
        }, LayoutParams(dp(44), LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8); gravity = Gravity.CENTER_VERTICAL })
    }
    private fun label(value: String, size: Int, color: Int) = TextView(context).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); maxLines = 1
        ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
    }
    private fun TextView.enableMarquee() {
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isSelected = true
        setHorizontallyScrolling(true)
    }
    private fun badge(value: String) = label(value, 10, accent).apply {
        background = pill(tint, 99); setPadding(dp(8), dp(3), dp(8), dp(3))
    }
    private fun pill(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}
