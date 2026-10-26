package io.github.proify.lyricon.localprovider.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Small stroke icons with consistent bounds, including menu arrows. */
class ProviderIcon(private val name: String, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.7f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        fun path(vararg points: Float) {
            val p = Path(); p.moveTo(points[0], points[1]); var i = 2
            while (i < points.size) { p.lineTo(points[i], points[i + 1]); i += 2 }
            canvas.drawPath(p, paint)
        }
        when (name) {
            "back" -> { path(14f,5f,7f,12f,14f,19f); path(7f,12f,21f,12f) }
            "chevron" -> path(9f,6f,15f,12f,9f,18f)
            "down" -> path(6f,9f,12f,15f,18f,9f)
            "up" -> path(6f,15f,12f,9f,18f,15f)
            "home" -> { path(3f,10f,12f,3f,21f,10f); path(5f,9f,5f,21f,10f,21f,10f,14f,14f,14f,14f,21f,19f,21f,19f,9f) }
            "library" -> { path(5f,3f,21f,3f,21f,20f,5f,20f,5f,3f); path(2f,6f,2f,22f,18f,22f); path(9f,7f,17f,7f); path(9f,11f,15f,11f) }
            "settings" -> {
                val gear=Path()
                for(i in 0 until 32) {
                    val angle=i*Math.PI/16-Math.PI/2
                    val radius=if(i%4 in 1..2) 10.2 else 8.1
                    val x=12f+(kotlin.math.cos(angle)*radius).toFloat(); val y=12f+(kotlin.math.sin(angle)*radius).toFloat()
                    if(i==0) gear.moveTo(x,y) else gear.lineTo(x,y)
                }
                gear.close(); canvas.drawPath(gear,paint); canvas.drawCircle(12f,12f,3.2f,paint)
            }
            "sliders" -> { path(3f,6f,21f,6f); path(3f,12f,21f,12f); path(3f,18f,21f,18f); canvas.drawCircle(8f,6f,2.2f,paint); canvas.drawCircle(16f,12f,2.2f,paint); canvas.drawCircle(9f,18f,2.2f,paint) }
            "image" -> { canvas.drawRoundRect(3f,3f,21f,21f,3f,3f,paint); canvas.drawCircle(8f,8f,1.4f,paint); path(4f,17f,10f,11f,15f,17f,18f,14f,21f,18f) }
            "sort" -> { path(6f,3f,6f,21f); path(2f,7f,6f,3f,10f,7f); path(17f,3f,17f,21f); path(13f,17f,17f,21f,21f,17f) }
            "sun" -> { canvas.drawCircle(12f,12f,4f,paint); for(i in 0 until 8) {
                val a=i*Math.PI/4; path(12f+(kotlin.math.cos(a)*7).toFloat(),12f+(kotlin.math.sin(a)*7).toFloat(),12f+(kotlin.math.cos(a)*10).toFloat(),12f+(kotlin.math.sin(a)*10).toFloat())
            } }
            "levels" -> { path(4f,10f,4f,16f); path(9f,5f,9f,20f); path(14f,8f,14f,18f); path(19f,4f,19f,21f) }
            "list" -> { canvas.drawRoundRect(3f,2f,21f,22f,3f,3f,paint); path(8f,7f,17f,7f); path(8f,12f,17f,12f); path(8f,17f,14f,17f) }
            "drop" -> { val p=Path(); p.moveTo(12f,2f); p.cubicTo(8f,8f,3f,12f,5f,17f); p.cubicTo(8f,25f,21f,21f,19f,14f); p.cubicTo(18f,10f,14f,5f,12f,2f); canvas.drawPath(p,paint) }
            "blur" -> { for(i in 0 until 8) canvas.drawArc(3f,3f,21f,21f,i*45f,25f,false,paint) }
            "corners" -> { path(9f,3f,5f,3f,3f,5f,3f,9f); path(15f,3f,19f,3f,21f,5f,21f,9f); path(21f,15f,21f,19f,19f,21f,15f,21f); path(9f,21f,5f,21f,3f,19f,3f,15f) }
            "upload" -> { path(12f,16f,12f,3f); path(7f,8f,12f,3f,17f,8f); path(3f,14f,3f,21f,21f,21f,21f,14f) }
            "close" -> { path(6f,6f,18f,18f); path(18f,6f,6f,18f) }
            "locate" -> { canvas.drawCircle(12f,10f,3f,paint); canvas.drawOval(5f,3f,19f,17f,paint); path(6f,14f,12f,22f,18f,14f) }
            "folder" -> path(3f,7f,3f,20f,21f,20f,21f,7f,12f,7f,10f,4f,3f,4f,3f,7f)
            "music" -> { path(9f,17f,9f,5f,20f,3f,20f,15f); canvas.drawOval(3f,15f,9f,20f,paint); canvas.drawOval(14f,13f,20f,18f,paint) }
            "history" -> { canvas.drawArc(4f,4f,21f,21f,-130f,300f,false,paint); path(3f,3f,3f,9f,9f,9f); path(12f,8f,12f,13f,16f,15f) }
            "search" -> { canvas.drawCircle(10f,10f,6.5f,paint); path(15f,15f,21f,21f) }
            "refresh" -> { canvas.drawArc(4f,4f,20f,20f,-50f,290f,false,paint); path(20f,3f,20f,9f,14f,9f) }
            "edit" -> { path(4f,16f,4f,21f,9f,21f,21f,9f,16f,4f,4f,16f); path(14f,6f,19f,11f) }
            "info" -> { canvas.drawCircle(12f,12f,9f,paint); path(12f,11f,12f,17f); canvas.drawCircle(12f,7f,.6f,paint) }
            "filter" -> path(3f,4f,21f,4f,14f,12f,14f,21f,10f,18f,10f,12f,3f,4f)
            "save" -> { path(5f,3f,17f,3f,21f,7f,21f,21f,3f,21f,3f,3f,5f,3f); path(7f,3f,7f,9f,16f,9f,16f,3f); path(7f,21f,7f,15f,17f,15f,17f,21f) }
            "player" -> { canvas.drawArc(4f,4f,20f,20f,180f,180f,false,paint); path(4f,12f,4f,20f,8f,20f,8f,13f,4f,13f); path(20f,12f,20f,20f,16f,20f,16f,13f,20f,13f) }
            "palette" -> { canvas.drawOval(3f,3f,21f,21f,paint); canvas.drawCircle(8f,8f,1f,paint); canvas.drawCircle(14f,6f,1f,paint); canvas.drawCircle(18f,11f,1f,paint); path(14f,20f,12f,15f,17f,15f) }
            "cloud" -> { canvas.drawArc(5f,5f,17f,17f,170f,250f,false,paint); canvas.drawArc(13f,10f,23f,20f,-95f,210f,false,paint); path(5f,20f,18f,20f); canvas.drawArc(1f,12f,10f,20f,90f,190f,false,paint) }
            else -> { path(12f,5f,12f,19f); path(5f,12f,19f,12f) }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
