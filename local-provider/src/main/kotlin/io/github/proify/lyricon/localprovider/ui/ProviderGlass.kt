package io.github.proify.lyricon.localprovider.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import androidx.core.graphics.ColorUtils
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Shared background-only capture: panels never record themselves or another window's view tree. */
object ProviderGlass {
    private val backdrops=WeakHashMap<Context,Backdrop>()
    private fun backdrop(c: Context): Backdrop {
        val size=c.resources.displayMetrics
        val key="${ProviderTheme.signature(c)}:${size.widthPixels}:${size.heightPixels}"
        return backdrops[c]?.takeIf { it.key==key } ?: Backdrop(c,key).also { backdrops[c]=it }
    }
    fun page(c: Context): Drawable = object : Drawable() {
        override fun draw(canvas: Canvas) { backdrop(c).draw(canvas) }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(filter: ColorFilter?) {}
        @Deprecated("Deprecated") override fun getOpacity()=PixelFormat.OPAQUE
    }
    fun panel(c: Context,view: View?=null,radius: Int=16,glass: Boolean=ProviderAppearance.glass(c)): Drawable = Panel(c,view,radius,glass)
    fun surface(view: View,radius: Int=16) { view.background=panel(view.context,view,radius); view.clipToOutline=true }
    fun feedback(view: View,radius: Int=12) {
        val d=view.resources.displayMetrics.density
        val corner=radius*d*(if(radius<40) ProviderAppearance.corner(view.context)/16f else 1f)
        view.foreground=RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(ProviderTheme.colors(view.context).primary,26)),null,
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius=corner })
        view.clipToOutline=true
    }
    fun solid(c: Context,color: Int,radius: Int=16,border: Boolean=true): Drawable = GradientDrawable().apply {
        setColor(color); cornerRadius=radius*c.resources.displayMetrics.density*(if(radius<40) ProviderAppearance.corner(c)/16f else 1f)
        if(border) setStroke((c.resources.displayMetrics.density*.7f).toInt().coerceAtLeast(1),
            ProviderAppearance.border(c) ?: ColorUtils.setAlphaComponent(ProviderTheme.colors(c).line,140))
    }

    private class Backdrop(c: Context,val key: String) {
        private val width=c.resources.displayMetrics.widthPixels
        private val height=c.resources.displayMetrics.heightPixels
        private val palette=ProviderTheme.colors(c)
        private val glass=ProviderAppearance.glass(c)
        private val dark=ColorUtils.calculateLuminance(palette.background)<.3
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private val glow=ProviderAppearance.background(c) ?: 0xFF8CD4FF.toInt()
        private val blur=ProviderAppearance.blur(c)*c.resources.displayMetrics.density
        private var node: Any?=null
        fun draw(canvas: Canvas) {
            canvas.drawColor(palette.background)
            if(!glass) return
            paint.shader=RadialGradient(width*.08f,height*.18f,height*.65f,
                intArrayOf(ColorUtils.setAlphaComponent(glow,if(dark) 24 else 40),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
            paint.shader=RadialGradient(width*.95f,height*.78f,height*.5f,
                intArrayOf(ColorUtils.setAlphaComponent(palette.primary,if(dark) 12 else 25),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
            paint.shader=RadialGradient(width*.75f,height*.24f,height*.30f,
                intArrayOf(ColorUtils.setAlphaComponent(Color.WHITE,if(dark) 5 else 100),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
            paint.shader=RadialGradient(width*.12f,height*.87f,height*.24f,
                intArrayOf(ColorUtils.setAlphaComponent(Color.WHITE,if(dark) 4 else 70),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint); paint.shader=null
        }
        fun drawBlurred(canvas: Canvas) {
            if(Build.VERSION.SDK_INT>=31 && canvas.isHardwareAccelerated && blur>0) {
                val n=(node as? RenderNode) ?: RenderNode("Provider background").also { value ->
                    value.setPosition(0,0,width,height)
                    val recording=value.beginRecording(width,height); draw(recording); value.endRecording()
                    value.setRenderEffect(RenderEffect.createBlurEffect(blur,blur,Shader.TileMode.CLAMP)); node=value
                }
                canvas.drawRenderNode(n)
            } else draw(canvas)
        }
    }

    private class Panel(private val context: Context,view: View?,radius: Int,private val glass: Boolean): Drawable() {
        private val owner=WeakReference(view)
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        private val path=Path()
        private val box=RectF()
        private val position=IntArray(2)
        private val density=context.resources.displayMetrics.density
        private val radius=radius*density*(if(radius<40) ProviderAppearance.corner(context)/16f else 1f)
        override fun getOutline(outline: Outline) { outline.setRoundRect(bounds,radius) }
        override fun draw(canvas: Canvas) {
            if(bounds.isEmpty) return
            val p=ProviderTheme.colors(context)
            box.set(bounds); path.reset(); path.addRoundRect(box,radius,radius,Path.Direction.CW)
            canvas.save(); canvas.clipPath(path)
            if(glass) {
                val view=owner.get()
                if(view!=null && view.isAttachedToWindow) view.getLocationOnScreen(position) else {
                    position[0]=(context.resources.displayMetrics.widthPixels-bounds.width())/2
                    position[1]=(context.resources.displayMetrics.heightPixels-bounds.height())/2
                }
                canvas.save(); canvas.translate(-position[0].toFloat(),-position[1].toFloat())
                canvas.scale(1.016f,1.016f,position[0]+bounds.exactCenterX(),position[1]+bounds.exactCenterY())
                backdrop(context).drawBlurred(canvas); canvas.restore()
                paint.color=ColorUtils.setAlphaComponent(p.surface,(ProviderAppearance.opacity(context)*2.55f).toInt())
                canvas.drawRect(box,paint)
                paint.shader=LinearGradient(box.left,box.top,box.right,box.bottom,
                    intArrayOf(ColorUtils.setAlphaComponent(Color.WHITE,if(ColorUtils.calculateLuminance(p.background)<.3) 12 else 22),Color.TRANSPARENT),null,Shader.TileMode.CLAMP)
                canvas.drawRect(box,paint); paint.shader=null
            } else { paint.color=p.surface; canvas.drawRect(box,paint) }
            canvas.restore()
            paint.style=Paint.Style.STROKE; paint.strokeWidth=density*.75f
            paint.color=ProviderAppearance.border(context) ?: if(glass) ColorUtils.setAlphaComponent(if(ColorUtils.calculateLuminance(p.background)<.3) p.muted else Color.WHITE,160)
                else ColorUtils.setAlphaComponent(p.line,120)
            box.inset(paint.strokeWidth/2,paint.strokeWidth/2)
            canvas.drawRoundRect(box,radius,radius,paint); paint.style=Paint.Style.FILL
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(filter: ColorFilter?) {}
        @Deprecated("Deprecated") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}
