package io.github.proify.lyricon.localprovider.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.widget.CompoundButton
import androidx.core.graphics.ColorUtils

/** A small visual capsule with a full 52×44dp accessible switch touch target. */
class CapsuleSwitch(context: Context) : CompoundButton(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val density=resources.displayMetrics.density
    init { buttonDrawable=null; minWidth=(52*density).toInt(); minHeight=(44*density).toInt(); isClickable=true; isFocusable=true }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"
    override fun onMeasure(w: Int,h: Int) { setMeasuredDimension(resolveSize((52*density).toInt(),w),resolveSize((44*density).toInt(),h)) }
    override fun onDraw(canvas: Canvas) {
        val p=ProviderTheme.colors(context)
        val left=(width-40*density)/2f; val top=(height-22*density)/2f
        paint.color=if(isChecked) p.primary else ColorUtils.blendARGB(p.surface,p.muted,.22f)
        if(!isEnabled) paint.alpha=90
        canvas.drawRoundRect(left,top,left+40*density,top+22*density,11*density,11*density,paint)
        paint.alpha=255; paint.color=Color.WHITE
        val rtl=layoutDirection==LAYOUT_DIRECTION_RTL
        val right=if(rtl) !isChecked else isChecked
        canvas.drawCircle(left+(if(right) 29 else 11)*density,top+11*density,8*density,paint)
    }
}
