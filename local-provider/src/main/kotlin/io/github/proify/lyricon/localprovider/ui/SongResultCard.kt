package io.github.proify.lyricon.localprovider.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.proify.lyricon.localprovider.artwork.ArtworkLoader
import io.github.proify.lyricon.lyric.model.Song

/** One row component, with the reference's distinct information positions for each list. */
class SongResultCard(
    context: Context, song: Song, album: String, source: String,
    score: Int? = null, moreVersions: Int = 0, expanded: Boolean = false, artwork: String = "",
    onPreview: () -> Unit, onScore: (() -> Unit)? = null, onToggleVersions: (() -> Unit)? = null,
    lyricStatus: String = "", heroHeader: Boolean = false,
    layoutStyle: LayoutStyle = if(heroHeader) LayoutStyle.SUMMARY else LayoutStyle.RESULT,
    versionCount: Int? = null, capabilityLabels: List<String>? = null
) : LinearLayout(context) {
    enum class LayoutStyle { RESULT, RECENT, LIBRARY, SUMMARY }
    private val style=layoutStyle
    private val palette=ProviderTheme.colors(context)
    private val ink=palette.text
    private val muted=palette.muted
    private val accent=palette.primary
    private var sideMetadata: LinearLayout?=null
    private val largeText get()=resources.configuration.fontScale>1.2f

    init {
        tag="songRow:${style.name}"
        orientation=HORIZONTAL; gravity=Gravity.CENTER_VERTICAL
        setPadding(dp(10),dp(8),dp(8),dp(8)); ProviderGlass.surface(this,12)
        isClickable=true; isFocusable=true; setOnClickListener { onPreview() }; ProviderGlass.feedback(this,12)
        val features=capabilityLabels ?: ProviderSongMetadata.capabilities(song)
        contentDescription=listOfNotNull(song.name,song.artist,album.takeIf { it.isNotBlank() },source,
            features.joinToString("、").takeIf { it.isNotBlank() },
            song.duration.takeIf { it>0 }?.let { "%02d:%02d".format(it/60000,it/1000%60) },
            versionCount?.let { "$it 份歌词" }).joinToString("，")
        addView(ImageView(context).apply {
            tag="songCover"; background=ProviderGlass.solid(context,palette.tint,8,false); clipToOutline=true
            importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO; ArtworkLoader.bind(this,artwork)
        },LayoutParams(dp(if(style==LayoutStyle.SUMMARY) 64 else 52),dp(if(style==LayoutStyle.SUMMARY) 64 else 52)).apply { marginEnd=dp(10) })
        val info=column().apply { tag="songInfo" }
        addView(info,LayoutParams(0,-2,1f))
        info.addView(label(song.name.orEmpty(),if(style==LayoutStyle.SUMMARY) 16 else 15,ink).apply {
            tag="songTitle"; typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        },LayoutParams(-1,-2))
        info.addView(label(listOf(song.artist.orEmpty(),album).filter { it.isNotBlank() }.joinToString(" · "),12,muted).apply {
            tag="songArtistAlbum"
        },LayoutParams(-1,-2).apply { topMargin=dp(3) })

        when(style) {
            LayoutStyle.RESULT -> {
                val metadata=row().apply { tag="songMetadata" }
                if(features.isNotEmpty()) {
                    metadata.addView(featureGroup(features),LayoutParams(-2,-2).apply { marginEnd=dp(5) })
                    if(source.isNotBlank()) metadata.addView(sourcePill(source),LayoutParams(-2,-2))
                } else {
                    if(lyricStatus.isNotBlank()) metadata.addView(label(lyricStatus,10,muted).apply { tag="songFeatures" },LayoutParams(0,-2,1f))
                    else if(source.isNotBlank()) metadata.addView(sourcePill(source),LayoutParams(-2,-2))
                }
                info.addView(metadata,LayoutParams(-1,-2).apply { topMargin=dp(3) })
            }
            LayoutStyle.SUMMARY -> if(source.isNotBlank()) {
                info.addView(sourcePill(source),LayoutParams(-2,-2).apply { topMargin=dp(3) })
            }
            LayoutStyle.RECENT -> {
                val side=row().apply {
                    tag="songSideMetadata"; orientation=if(largeText) VERTICAL else HORIZONTAL
                    if(features.isNotEmpty()) addView(featureGroup(features.take(1)),LayoutParams(-2,-2).apply { marginEnd=dp(5) })
                    if(source.isNotBlank()) addView(sourcePill(source),LayoutParams(if(largeText) -1 else 0,-2,if(largeText) 0f else 1f))
                }
                sideMetadata=side
                addView(side,LayoutParams(dp(130),-2).apply { marginStart=dp(8) })
            }
            LayoutStyle.LIBRARY -> {
                val side=row().apply {
                    tag="songSideMetadata"; orientation=if(largeText) VERTICAL else HORIZONTAL
                    addView(featureGroup(features),LayoutParams(if(largeText) -2 else 0,-2,if(largeText) 0f else 1f))
                    addView(label("${versionCount ?: 1} 份",11,muted).apply { tag="songVersionCount" },LayoutParams(-2,-2).apply { marginStart=dp(8) })
                }
                sideMetadata=side
                addView(side,LayoutParams(dp(130),-2).apply { marginStart=dp(8) })
            }
        }
        if(moreVersions>0 && onToggleVersions!=null) {
            info.addView(label(if(expanded) "收起版本" else "更多版本 $moreVersions",11,accent).apply {
                tag="songVersions"; minimumHeight=dp(40); gravity=Gravity.CENTER_VERTICAL
                isClickable=true; isFocusable=true; ProviderGlass.feedback(this,10)
                setOnClickListener { onToggleVersions() }
            },LayoutParams(-1,-2))
        }
        if(score!=null) addView(label("$score 分",11,accent).apply {
            tag="songMatchScore"; minimumHeight=dp(44)
            val upper=style==LayoutStyle.RESULT
            gravity=if(upper) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
            setPadding(0,if(upper) dp(7) else 0,0,0)
            background=InsetDrawable(ProviderGlass.solid(context,palette.tint,12),0,dp(if(upper) 3 else 11),0,dp(if(upper) 19 else 11))
            contentDescription="匹配 $score 分，查看评分明细"; ProviderGlass.feedback(this,12)
            if(onScore!=null) { isClickable=true; isFocusable=true; setOnClickListener { onScore() } }
        },LayoutParams(dp(48),dp(44)).apply { marginStart=dp(8); if(style==LayoutStyle.RESULT) gravity=Gravity.TOP })
        addView(ImageView(context).apply { tag="songChevron"; setImageDrawable(ProviderIcon("chevron",muted)); importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO },
            LayoutParams(dp(14),dp(14)).apply { marginStart=dp(3) })
    }

    override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
        sideMetadata?.let { side ->
            val remaining=MeasureSpec.getSize(widthMeasureSpec)-paddingLeft-paddingRight-dp(52+10+8+14+3)
            // Keep the two text lines readable, including at 320dp; trailing metadata truncates first.
            side.layoutParams.width=minOf(dp(142),maxOf(dp(if(style==LayoutStyle.LIBRARY) 96 else 0),(remaining*.48f).toInt()),(remaining-dp(72)).coerceAtLeast(0))
        }
        super.onMeasure(widthMeasureSpec,heightMeasureSpec)
    }
    private fun featureGroup(features: List<String>)=ProviderSongMetadata.features(context,features)
    private fun sourcePill(source: String)=ProviderSongMetadata.source(context,source)
    private fun label(value: String,size: Int,color: Int)=TextView(context).apply {
        text=value; textSize=size.toFloat(); setTextColor(color); maxLines=1; includeFontPadding=false; ellipsize=TextUtils.TruncateAt.END
    }
    private fun row()=LinearLayout(context).apply { orientation=HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun column()=LinearLayout(context).apply { orientation=VERTICAL }
    private fun dp(value: Int)=(resources.displayMetrics.density*value).toInt()
}
