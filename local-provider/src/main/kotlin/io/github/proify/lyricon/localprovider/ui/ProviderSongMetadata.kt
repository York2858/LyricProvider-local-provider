package io.github.proify.lyricon.localprovider.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import io.github.proify.lyricon.localprovider.lyrics.LyricTiming
import io.github.proify.lyricon.lyric.model.Song

/** The same separate capability chips and source capsule are used by every song surface. */
object ProviderSongMetadata {
    fun capabilities(song: Song): List<String> {
        val lines=song.lyrics.orEmpty()
        if(lines.isEmpty()) return emptyList()
        val roma=lines.any { !it.roma.isNullOrBlank() }; val translated=lines.any { !it.translation.isNullOrBlank() }
        return buildList {
            add(when { LyricTiming.hasWordTiming(lines)->"逐字"; roma && translated->"三行"; else->"逐行" })
            if(translated) add("翻译") else if(roma) add("罗马音")
        }
    }
    fun features(c: Context,labels: List<String>)=row(c).apply {
        tag="songFeatures"
        val p=ProviderTheme.colors(c)
        labels.take(2).forEachIndexed { i,value ->
            addView(label(c,value,9).apply {
                tag="songFeature:$value"; gravity=Gravity.CENTER
                background=ProviderGlass.solid(c,p.tint,10,false); setPadding(dp(c,6),dp(c,2),dp(c,6),dp(c,2))
            },LinearLayout.LayoutParams(-2,-2).apply { if(i>0) marginStart=dp(c,4) })
        }
    }
    fun source(c: Context,value: String)=row(c).apply {
        tag="songSourcePill"; minimumHeight=dp(c,18)
        val p=ProviderTheme.colors(c)
        background=ProviderGlass.solid(c,ColorUtils.setAlphaComponent(p.tint,110),12)
        setPadding(dp(c,5),dp(c,1),dp(c,5),dp(c,1))
        addView(ProviderSourceIcons.view(c,value),LinearLayout.LayoutParams(dp(c,12),dp(c,12)).apply { marginEnd=dp(c,4) })
        addView(label(c,value,9).apply { tag="songSource"; maxWidth=dp(c,80) },LinearLayout.LayoutParams(-2,-2))
    }
    private fun row(c: Context)=LinearLayout(c).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun label(c: Context,value: String,size: Int)=TextView(c).apply {
        text=value; textSize=size.toFloat(); setTextColor(ProviderTheme.colors(c).muted)
        maxLines=1; ellipsize=TextUtils.TruncateAt.END; includeFontPadding=false
    }
    private fun dp(c: Context,value: Int)=(c.resources.displayMetrics.density*value).toInt()
}
