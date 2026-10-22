package io.github.proify.lyricon.localprovider.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/** Button toolbars keep clear touch gaps and wrap instead of squeezing their labels. */
class SpacedButtonLayout(context: Context) : ViewGroup(context) {
    private val horizontalGap = dp(10)
    private val verticalGap = dp(8)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 4
            else (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        var rowWidth = 0; var rowHeight = 0; var usedWidth = 0; var usedHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            val lp = child.layoutParams as MarginLayoutParams
            val width = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val height = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (rowWidth > 0 && rowWidth + horizontalGap + width > available) {
                usedWidth = maxOf(usedWidth, rowWidth); usedHeight += rowHeight + verticalGap
                rowWidth = 0; rowHeight = 0
            }
            rowWidth += (if (rowWidth > 0) horizontalGap else 0) + width
            rowHeight = maxOf(rowHeight, height)
        }
        usedWidth = maxOf(usedWidth, rowWidth)
        setMeasuredDimension(resolveSize(usedWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(usedHeight + rowHeight + paddingTop + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        var x = 0; var y = paddingTop; var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val childWidth = child.measuredWidth + lp.leftMargin + lp.rightMargin
            val childHeight = child.measuredHeight + lp.topMargin + lp.bottomMargin
            if (x > 0 && x + horizontalGap + childWidth > available) { x = 0; y += rowHeight + verticalGap; rowHeight = 0 }
            if (x > 0) x += horizontalGap
            val childLeft = if (layoutDirection == View.LAYOUT_DIRECTION_RTL)
                width - paddingRight - x - lp.rightMargin - child.measuredWidth else paddingLeft + x + lp.leftMargin
            child.layout(childLeft, y + lp.topMargin, childLeft + child.measuredWidth, y + lp.topMargin + child.measuredHeight)
            x += childWidth; rowHeight = maxOf(rowHeight, childHeight)
        }
    }
    override fun generateDefaultLayoutParams() = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(attrs: AttributeSet) = MarginLayoutParams(context, attrs)
    override fun generateLayoutParams(p: LayoutParams) = MarginLayoutParams(p)
    override fun checkLayoutParams(p: LayoutParams) = p is MarginLayoutParams
    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}
