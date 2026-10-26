package io.github.proify.lyricon.localprovider.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** One material and rounded feedback for configuration, choices and confirmation dialogs. */
class ProviderDialogBuilder(context: Context): MaterialAlertDialogBuilder(context) {
    override fun setView(view: View?): ProviderDialogBuilder {
        val body=if(view is EditText) LinearLayout(context).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(24),dp(8),dp(24),0)
            addView(view,LinearLayout.LayoutParams(-1,-2))
        } else view
        super.setView(body)
        return this
    }
    override fun create(): AlertDialog {
        setBackground(ProviderGlass.panel(context,radius=24))
        val dialog=super.create()
        dialog.window?.decorView?.addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { view.post { style(view) } }
            override fun onViewDetachedFromWindow(view: View) {}
        })
        return dialog
    }
    private fun style(view: View) {
        if(view is EditText) {
            ProviderGlass.surface(view,12)
            view.setPadding(dp(12),dp(10),dp(12),dp(10)); view.setTextColor(ProviderTheme.colors(context).text)
            view.setHintTextColor(ProviderTheme.colors(context).muted); view.textSize=14f; view.minimumHeight=dp(44)
        }
        if(view is MaterialButton) { view.cornerRadius=(dp(14)*ProviderAppearance.corner(context)/16f).toInt(); view.strokeColor=android.content.res.ColorStateList.valueOf(ProviderAppearance.border(context) ?: ProviderTheme.colors(context).line); ProviderGlass.feedback(view,14) }
        if(view is ViewGroup) for(i in 0 until view.childCount) style(view.getChildAt(i))
    }
    private fun dp(value: Int)=(context.resources.displayMetrics.density*value).toInt()
}
