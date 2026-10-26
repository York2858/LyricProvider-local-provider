package io.github.proify.lyricon.localprovider.ui

import android.content.Context
import android.widget.ImageView
import io.github.proify.lyricon.localprovider.R

object ProviderSourceIcons {
    fun view(c: Context,source: String): ImageView {
        val name=source.lowercase()
        val res=when {
            name.contains("qq") -> R.drawable.ic_source_qq
            name.contains("网易") || name.contains("netease") -> R.drawable.ic_source_neteasecloudmusic
            name.contains("酷狗") || name.contains("kugou") -> R.drawable.ic_source_kugou
            name.contains("汽水") || name.contains("soda") -> R.drawable.ic_source_soda
            name.contains("apple") -> R.drawable.ic_source_applemusic
            else -> 0
        }
        val color=when {
            name.contains("网易") || name.contains("netease") -> 0xFFE53945.toInt()
            name.contains("酷狗") || name.contains("kugou") -> 0xFF268BEA.toInt()
            name.contains("apple") -> 0xFFEF5570.toInt()
            else -> ProviderTheme.colors(c).tint
        }
        return ImageView(c).apply {
            if(res!=0) setImageResource(res) else setImageDrawable(ProviderIcon(if(name.contains("本地") || name.contains("内嵌")) "folder" else "music",ProviderTheme.colors(c).primary))
            background=ProviderGlass.solid(c,color,5,false); clipToOutline=true
            val inset=if(res==R.drawable.ic_source_qq || res==R.drawable.ic_source_soda) 0 else (2*resources.displayMetrics.density).toInt()
            setPadding(inset,inset,inset,inset); importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
}
