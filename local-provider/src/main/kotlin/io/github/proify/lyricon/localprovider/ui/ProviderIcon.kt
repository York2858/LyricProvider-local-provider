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
            "library" -> { path(4f,4f,4f,20f); path(8f,4f,8f,20f); path(12f,5f,17f,20f,21f,19f,16f,4f,12f,5f) }
            "settings" -> { path(3f,6f,21f,6f); path(3f,12f,21f,12f); path(3f,18f,21f,18f); canvas.drawCircle(8f,6f,2.2f,paint); canvas.drawCircle(16f,12f,2.2f,paint); canvas.drawCircle(9f,18f,2.2f,paint) }
            "folder" -> path(3f,7f,3f,20f,21f,20f,21f,7f,12f,7f,10f,4f,3f,4f,3f,7f)
            "music" -> { path(9f,17f,9f,5f,20f,3f,20f,15f); canvas.drawOval(3f,15f,9f,20f,paint); canvas.drawOval(14f,13f,20f,18f,paint) }
            "history" -> { canvas.drawArc(4f,4f,21f,21f,-130f,300f,false,paint); path(3f,3f,3f,9f,9f,9f); path(12f,8f,12f,13f,16f,15f) }
            "search" -> { canvas.drawCircle(10f,10f,6.5f,paint); path(15f,15f,21f,21f) }
            "refresh" -> { canvas.drawArc(4f,4f,20f,20f,-50f,290f,false,paint); path(20f,3f,20f,9f,14f,9f) }
            else -> { path(12f,5f,12f,19f); path(5f,12f,19f,12f) }
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
